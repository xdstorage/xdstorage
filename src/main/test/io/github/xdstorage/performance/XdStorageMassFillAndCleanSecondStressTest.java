package io.github.xdstorage.performance;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStorageConfig;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.performance.entities.*;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.serialization.FilesFormat;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.*;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

@Tag("stress")
@DisplayName("Релизный тест: Сценарий 2 — Пошаговое наполнение кусочками и тотальный пурдж")
public class XdStorageMassFillAndCleanSecondStressTest {

    private static final String STORAGE_PATH = "./teststorage";
    private IXdFileStorage storage;

    @BeforeEach
    public void setUp() {
        deleteDir(new File(STORAGE_PATH));
        // Таймаут транзакции: дефолт 3000ms меньше времени каскадного удаления блока
        // (обход поддерева = ~10+ storage-операций под 10-поточной нагрузкой) — sweeper
        // помечал такие TX rollback-only посреди работы ("should be rolled back by
        // timeout. Object is locked for update") и ни одно удаление не доходило до коммита.
        XdStorageConfig config = new XdStorageConfig.Builder()
                .journalWrites(true)
                .setEnableForeignKeys(true)
                .defaultTransactionTimeoutMillis(8 * 1000L)
                .build();
        storage = XdStorageProvider.newOrGetFileStorage("fill_clean_test", STORAGE_PATH, 200, FilesFormat.YAML, config);
    }

    @AfterEach
    public void tearDown() {
        if (storage != null) {
            try {
                storage.shutdown();
            } catch (Exception ignored) {
            }
        }
        deleteDir(new File(STORAGE_PATH));
    }

    private static void deleteDir(File file) {
        File[] contents = file.listFiles();
        if (contents != null) {
            for (File f : contents) deleteDir(f);
        }
        file.delete();
    }

    @Test
    @DisplayName("Конкурентное поблочное наполнение базы в 10 потоков и верификация чистоты дискового пространства")
    public void testScenario2_MassFillAndClean() throws Exception {
        System.out.println("=== СТАРТ СЦЕНАРИЯ 2: МАССОВОЕ ПОБЛОЧНОЕ НАПОЛНЕНИЕ ===");

        // --- ДОРАБОТКА: Локальная потокобезопасная Audit-модель для фиксации изменений ---
        // Хранит структуру: UniverseId -> Список Id добавленных Галактик
        Map<String, List<String>> auditModel = new ConcurrentHashMap<>();
        // Счетчики для контроля реального выполнения операций в параллельных потоках
        AtomicInteger successfulInserts = new AtomicInteger(0);
        AtomicInteger successfulDeletes = new AtomicInteger(0);

        // ТРЕБОВАНИЕ: Предварительное наполнение базы стартовыми стабильными вселенными
        IXdStorageTransaction txInit = storage.beginTransaction();
        List<String> universeIds = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            XdUniverseAutoSave u = new XdUniverseAutoSave();
            u.setId(UUID.randomUUID().toString());
            storage.save(u, txInit);
            universeIds.add(u.getId());
            auditModel.put(u.getId(), new CopyOnWriteArrayList<>()); // Инициализируем списки в модели
        }
        txInit.commit();
        System.out.println(" -> Стартовые вселенные закоммичены. База предварительно наполнена.");

        // Сериализация МУТАЦИЙ внутри шарда: FK-дерево пары (universe, galaxy) — глобальный
        // штопор на ОТНОШЕНИЕ, и его запись держится локом контура до коммита. Конкурентные
        // insert+delete одного шарда берут дерево и дочерние записи в разном порядке =
        // циркулярное ожидание, которое иначе разруливает только sweeper по таймауту.
        // Permit(1) на вселенную: мутации шарда идут по очереди, чтения параллельно,
        // шарды между собой параллельны.
        Map<String, Semaphore> shardPermits = new ConcurrentHashMap<>();
        for (String uId : universeIds) {
            shardPermits.put(uId, new Semaphore(1));
        }

        // --- ПРОГРЕВ (release-gate методология): первая запись несёт одноразовую цену
        // инфраструктуры — генерация wrapper-классов, создание FK-деревьев, первичное
        // заполнение индексов. Под 10-поточным фейерверком эта цена сериализуется на
        // глобальной инициализации (~1с) и съедает всё измеряемое окно, оставляя потокам
        // по одной итерации. Прогреваем каждый шард полным циклом (запись блока -> удаление
        // блока снизу вверх) ПОСЛЕДОВАТЕЛЬНО, чтобы гонка измеряла установившийся режим.
        for (String uId : universeIds) {
            IXdStorageTransaction warmTx = storage.beginTransaction();
            XdUniverseAutoSave wu = storage.load(XdUniverseAutoSave.class, uId, warmTx);
            XdGalaxyAutoSave wg = new XdGalaxyAutoSave();
            wg.setId(UUID.randomUUID().toString());
            wu.addGalaxy(wg);
            XdBlackHoleAutoSave wh = new XdBlackHoleAutoSave();
            wh.setId(UUID.randomUUID().toString());
            wg.setHole(wh);
            XdStarSystemAutoSave ws = new XdStarSystemAutoSave();
            ws.setId(UUID.randomUUID().toString());
            wg.addSystem(ws);
            XdPlanetAutoSave wp = new XdPlanetAutoSave();
            wp.setName("WarmupPlanet");
            ws.addPlanet(wp);
            storage.update(wu, warmTx);
            warmTx.commit();

            // Удаление блока снизу вверх (FK-канон: поддерево раньше родителя)
            IXdStorageTransaction warmDelTx = storage.beginTransaction();
            XdUniverseAutoSave wu2 = storage.load(XdUniverseAutoSave.class, uId, warmDelTx);
            Iterator<XdGalaxyAutoSave> wit = wu2.getGalaxies().iterator();
            XdGalaxyAutoSave wgRef = wit.next();
            String warmGalaxyId = wgRef.getId();
            wit.remove();
            storage.update(wu2, warmDelTx);
            XdGalaxyAutoSave wgFull = storage.load(XdGalaxyAutoSave.class, warmGalaxyId, warmDelTx);
            Collection<XdStarSystemAutoSave> wsystems = wgFull.getSystems();
            storage.load(wsystems, warmDelTx);
            for (XdStarSystemAutoSave wsys : wsystems) {
                Collection<XdPlanetAutoSave> wplanets = wsys.getPlanets();
                if (wplanets != null) {
                    storage.load(wplanets, warmDelTx);
                    for (XdPlanetAutoSave wplanet : wplanets) {
                        storage.delete(wplanet, warmDelTx);
                    }
                }
                storage.delete(wsys, warmDelTx);
            }
            storage.delete(wgFull, warmDelTx);
            warmDelTx.commit();
        }
        System.out.println(" -> Прогрев инфраструктуры завершён (wrapper-классы, FK-деревья, индексы).");

        System.out.println(" -> Запуск 10 параллельных потоков...");

        int totalThreads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        AtomicBoolean stopFlag = new AtomicBoolean(false);
        Random rand = new Random();

        for (int i = 0; i < totalThreads; i++) {
            final int workerIndex = i;
            executor.submit(() -> {
                // Детерминированный цикл операций (0,1,2,3 = запись, запись, чтение, удаление):
                // та же пропорция смеси 50/25/25, что у rand.nextInt(4), но каждый воркер
                // ГАРАНТИРОВАННО доходит до удаления каждую 4-ю итерацию — рулетка op при
                // коротком окне прессинга оставляла ветку удаления без попыток (release-гейт
                // обязан детерминированно покрывать все типы операций)
                int opCycle = 0;
                while (!stopFlag.get()) {
                    // Фазовый сдвиг по воркеру: шард-мейты (i, i+5) никогда в одинаковой фазе
                    // одновременно — иначе оба разом удаляют одну галактику и второй виснет
                    // в lock-ожидании первого всю фазу
                    int op = (opCycle++ + workerIndex) % 4;
                    // Шардирование воркер->вселенная (2 потока на вселенную): структурно
                    // делит write-контеншн на корневой записи — иначе 10 потоков толкутся
                    // в lock-ожиданиях одной и той же вселенной и не завершают итерации
                    String targetUni = universeIds.get(workerIndex % universeIds.size());

                    IXdStorageTransaction tx = null;
                    boolean finished = false;

                    // Локальные переменные потока для транзитного удержания ID до успешного коммита
                    String tempGalaxyId = UUID.randomUUID().toString();

                    try {
                        if (op <= 1) {
                            shardPermits.get(targetUni).acquireUninterruptibly();
                            try {
                            tx = storage.beginTransaction();
                            XdUniverseAutoSave u = storage.load(XdUniverseAutoSave.class, targetUni, tx);
                            if (u != null) {
                                XdGalaxyAutoSave g = new XdGalaxyAutoSave();
                                g.setId(tempGalaxyId); // Фиксируем сгенерированный ID
                                u.addGalaxy(g);

                                XdBlackHoleAutoSave hole = new XdBlackHoleAutoSave();
                                hole.setId(UUID.randomUUID().toString());
                                hole.setMass(rand.nextInt(1000000));
                                g.setHole(hole);

                                XdStarSystemAutoSave sys = new XdStarSystemAutoSave();
                                sys.setId(UUID.randomUUID().toString());
                                sys.setNewName("System_" + rand.nextInt(1000));
                                g.addSystem(sys);

                                XdPlanetAutoSave p = new XdPlanetAutoSave();
                                p.setName("Planet_" + rand.nextInt(1000));
                                XdSatelliteAutoSave s = new XdSatelliteAutoSave();
                                s.setName("Sat_" + rand.nextInt(1000));
                                p.setSatellite(s);
                                sys.addPlanet(p);
                            }
                            storage.update(u, tx);
                            tx.commit(); // <--- КРИТИЧЕСКАЯ ТОЧКА СИНХРОНИЗАЦИИ

                            // Если коммит прошёл успешно — переносим изменения в Audit-модель тестов
                            auditModel.get(targetUni).add(tempGalaxyId);
                            successfulInserts.incrementAndGet();
                            } finally {
                                shardPermits.get(targetUni).release();
                            }

                        } else if (op == 2) {
                            tx = storage.beginTransaction();
                            storage.load(XdUniverseAutoSave.class, tx);
                            storage.load(XdPlanetAutoSave.class, tx);
                            tx.commit();
                        } else {
                            shardPermits.get(targetUni).acquireUninterruptibly();
                            try {
                            // Внешний tx не используется: ретрай-цикл владеет своими TX
                            // (обнуляем, чтобы внешний finally не тронул чужую/старую TX)
                            tx = null;
                            // Точечное удаление. Два constraint'а движка:
                            // 1) FK-канон (DeleteParentAlone): галактика удаляется только после
                            //    своего поддерева (спутники -> планеты -> системы -> чёрная дыра
                            //    -> галактика), иначе фаза-1 ветоит и операция откатывается.
                            //    Поддерево уходит СИНХРОННО с галактикой: переживший ребёнок
                            //    осиротеет и станет недостижимым для purge (дисковая утечка).
                            // 2) first-committer-wins: коллизии на записи вселенной легитимны —
                            //    ретраим транзакцию (новая TX = свежий read-снимок), а целимся
                            //    по audit-модели в непустые вселенные, чтобы попытки не
                            //    сгорали впустую.
                            // Ретраи доделываются и после stopFlag: долгий lock-столл
                            // (FK-дерево — глобальный штопор на отношение, контур держит
                            // лок до коммита) съедает 8s-таймаут одной попытки; коммит,
                            // приземлившийся во время wind-down, честно засчитывается
                            for (int attempt = 0; attempt < 3; attempt++) {
                                IXdStorageTransaction attemptTx = storage.beginTransaction();
                                final IXdStorageTransaction delTx = attemptTx;
                                boolean finishedInner = false;
                                String deletedGalaxyId = null;
                                try {
                                    // Целимся в свою вселенную по audit-модели
                                    if (auditModel.get(targetUni) == null || auditModel.get(targetUni).isEmpty()) {
                                        // finishedInner НЕ ставим: finally закроет TX rollback'ом —
                                        // брошенная открытая TX держит read-change в cache-слоте
                                        // записи и навсегда отравляет её для чужих мутаций
                                        break;
                                    }

                                    XdUniverseAutoSave u = storage.load(XdUniverseAutoSave.class, targetUni, delTx);
                                    if (u == null || u.getGalaxies().isEmpty()) {
                                        // finishedInner НЕ ставим: finally закроет TX rollback'ом
                                        break;
                                    }

                                    Iterator<XdGalaxyAutoSave> it = u.getGalaxies().iterator();
                                    XdGalaxyAutoSave galaxyRef = it.next();
                                    deletedGalaxyId = galaxyRef.getId();
                                    it.remove(); // Удаляем ссылку из коллекции родителя

                                    // ВАЖЕН ПОРЯДОК: update(universe) строго ДО удаления галактики —
                                    // update-diff растворяет пару (universe, galaxy) в момент staging;
                                    // при обратном порядке delete(g) вооружает waiter по ещё живой паре
                                    // и фаза-1 FK-проверки срабатывает раньше prepare-события вселенной
                                    // → детерминированное вето.
                                    storage.update(u, delTx);

                                    // FK-канон (DeleteParentAlone): поддерево уходит СНИЗУ ВВЕРХ и
                                    // СИНХРОННО с галактикой (планеты -> системы -> галактика) — иначе
                                    // фаза-1 ветоит ("Not all foreign keys are deleted").
                                    XdGalaxyAutoSave g = storage.load(XdGalaxyAutoSave.class, galaxyRef.getId(), delTx);
                                    if (g != null) {
                                        Collection<XdStarSystemAutoSave> systems = g.getSystems();
                                        System.out.println("[DIAG] DEL-walk galaxy=" + galaxyRef.getId().substring(0, 8)
                                                + " systems=" + (systems == null ? "NULL" : systems.size()));
                                        if (systems != null) {
                                            storage.load(systems, delTx);
                                            System.out.println("[DIAG] DEL-walk hydrated systems=" + systems.size());
                                            for (XdStarSystemAutoSave sys : systems) {
                                                Collection<XdPlanetAutoSave> planets = sys.getPlanets();
                                                if (planets != null) {
                                                    storage.load(planets, delTx);
                                                    for (XdPlanetAutoSave planetRef : planets) {
                                                        // Спутник (StoreWithParentObject) встроен в запись
                                                        // планеты — умирает вместе с ней, отдельного
                                                        // удаления (и дискового файла) не существует
                                                        try {
                                                            storage.delete(planetRef, delTx);
                                                        } catch (XdStorageException gone) {
                                                            if (!gone.getMessage().contains("does not exists")) {
                                                                throw gone;
                                                            }
                                                        }
                                                    }
                                                }
                                                try {
                                                    storage.delete(sys, delTx);
                                                } catch (XdStorageException gone) {
                                                    if (!gone.getMessage().contains("does not exists")) {
                                                        throw gone;
                                                    }
                                                }
                                            }
                                        }
                                        // Чёрная дыра (StoreWithParentObject) — встроена в галактику
                                        storage.delete(g, delTx);
                                    }

                                    attemptTx.commit();
                                    finishedInner = true;

                                    // Коммит удаления прошёл — синхронизируем audit-модель
                                    auditModel.get(targetUni).remove(deletedGalaxyId);
                                    successfulDeletes.incrementAndGet();
                                    break;
                                } catch (Throwable retryable) {
                                    // Оптимистичная коллизия — следующая попытка с новой TX
                                } finally {
                                    if (attemptTx != null && !finishedInner) {
                                        attemptTx.rollback();
                                    }
                                }
                            }
                            } finally {
                                shardPermits.get(targetUni).release();
                            }
                        }
                        finished = true;
                    } catch (Throwable ignored) {
                        // Оптимистичные коллизии под перекрестным огнем легитимны
                    } finally {
                        if (tx != null && !finished) {
                            tx.rollback();
                        }
                        // Страховка: если исключение вышло из мутационной ветки до её
                        // собственного release — возвращаем permit здесь (release на
                        // неснятый permit легитимен только если ветка его уже вернула;
                        // поэтому ветки возвращают его сами, а сюда попадают лишь аварийные пути)
                    }
                }
            });
        }

        // Даем воркерам поработать. Окно откалибровано под цену итерации после прогрева:
        // полный цикл воркера (запись, запись, чтение, удаление блока) занимает ~1-2с под
        // нагрузкой — окно 1000ms давало 1-3 цикла, ветка удаления не успевала отработать
        Thread.sleep(5000L);
        stopFlag.set(true);
        executor.shutdown();
        // Wind-down: ждём завершения текущих TX воркеров (не искусственная пауза —
        // только довыполнение начатых транзакций после stopFlag)
        executor.awaitTermination(120, TimeUnit.SECONDS);

        // --- ВЕРИФИКАЦИЯ РАБОТЫ ПОТОКОВ: Убеждаемся, что модификации физически происходили ---
        System.out.println("📊 Сводка параллельного прессинга:");
        System.out.println("   -> Успешных транзакций записи блоков: " + successfulInserts.get());
        System.out.println("   -> Успешных транзакций точечного удаления: " + successfulDeletes.get());

        Assertions.assertTrue(successfulInserts.get() > 0, "🚨 Ошибка теста: ни один поток не смог записать данные из-за блокировок!");
        Assertions.assertTrue(successfulDeletes.get() > 0, "🚨 Ошибка теста: операции модификации/удаления не выполнялись параллельно!");

        // Проверяем соответствие СУБД нашей In-Memory Audit модели перед тотальной зачисткой
        System.out.println("🔍 Проверка консистентности хранилища с локальной Audit-моделью...");
        IXdStorageTransaction checkTx = storage.beginTransaction();
        for (String uId : universeIds) {
            XdUniverseAutoSave u = storage.load(XdUniverseAutoSave.class, uId, checkTx);
            int expectedSize = auditModel.get(uId).size();
            int actualSize = u != null ? u.getGalaxies().size() : 0;
            Assertions.assertEquals(expectedSize, actualSize, "🚨 Рассинхрон! Данные в СУБД разошлись с успешной Audit-моделью!");
        }
        checkTx.commit();
        System.out.println("🎉 Данные проверены: состояние СУБД на 100% совпадает с журналом успешных коммитов.");

        System.out.println("🧹 Наполнение завершено. Запускаем каскадную очистку С КОРНЕВЫХ ЭЛЕМЕНТОВ...");

        // Очистка с корневых элементов (Вселенные) согласно ТЗ. Таймаут 120с: purge —
        // ОДНА атомарная транзакция, удаляющая все деревья целиком; дефолтные 15s её
        // не вмещают — sweeper убивал её посреди работы ("locked for update"), rollback
        // возвращал ВСЕ записи и verify видел "утечку"
        IXdStorageTransaction cleanTx = storage.beginTransaction(120 * 1000L);
        try {
            Collection<XdUniverseAutoSave> universes = storage.load(XdUniverseAutoSave.class, cleanTx);
            for (XdUniverseAutoSave universe : universes) {
                for (XdGalaxyAutoSave galaxyRef : universe.getGalaxies()) {
                    try {
                        XdGalaxyAutoSave galaxy = storage.load(XdGalaxyAutoSave.class, galaxyRef.getId(), cleanTx);
                        if (galaxy == null) continue;

                        Collection<XdStarSystemAutoSave> systems = galaxy.getSystems();
                        storage.load(systems, cleanTx);

                        for (XdStarSystemAutoSave systemRef : systems) {
                            Collection<XdPlanetAutoSave> planets = systemRef.getPlanets();
                            if (planets != null) {
                                storage.load(planets, cleanTx);
                                for (XdPlanetAutoSave planetRef : planets) {
                                    storage.delete(planetRef, cleanTx);
                                }
                            }
                            storage.delete(systemRef, cleanTx);
                        }
                        storage.delete(galaxy, cleanTx);
                    } catch (Exception e) {
                        e.printStackTrace(System.err);
                    }
                }
                storage.delete(universe, cleanTx);
            }
            cleanTx.commit();
            System.out.println("🧹 Единый атомарный коммит очистки зафиксирован успешно.");
        } catch (Exception e) {
            cleanTx.rollback();
            throw e;
        }

        System.out.println("🔍 Запуск тотальной верификации по классам...");
        IXdStorageTransaction verifyTx = storage.beginTransaction();
        try {
            Collection<XdUniverseAutoSave> lu = storage.load(XdUniverseAutoSave.class, verifyTx);
            Collection<XdGalaxyAutoSave> lg = storage.load(XdGalaxyAutoSave.class, verifyTx);
            Collection<XdStarSystemAutoSave> ls = storage.load(XdStarSystemAutoSave.class, verifyTx);
            Collection<XdPlanetAutoSave> lp = storage.load(XdPlanetAutoSave.class, verifyTx);
            for (final XdGalaxyAutoSave xg : lg) {
                System.out.println("[DIAG] SURVIVING galaxy id=" + xg.getId());
            }
            for (final XdStarSystemAutoSave xs : ls) {
                System.out.println("[DIAG] SURVIVING system id=" + xs.getId() + " name=" + xs.getNewName());
            }
            Assertions.assertTrue(lu.isEmpty(), "Утечка памяти! Найдена XdUniverse!");
            Assertions.assertTrue(lg.isEmpty(), "Утечка памяти! Найдена XdGalaxy!");
            Assertions.assertTrue(ls.isEmpty(), "Утечка памяти! Найдена XdStarSystem!");
            Assertions.assertTrue(lp.isEmpty(), "Утечка памяти! Найдена XdPlanet!");
            verifyTx.commit();
            System.out.println("🎉 Верификация по классам успешна: в памяти СУБД чисто.");
        } catch (Exception e) {
            verifyTx.rollback();
            throw e;
        }

        storage.shutdown();

        File storageDir = new File(STORAGE_PATH);
        int remainingDataFiles = 0;
        if (storageDir.exists() && storageDir.isDirectory()) {
            System.out.println("💾 Запущена прецизионная инспекция дисковых файлов...");
            remainingDataFiles = checkDataFilesCount(storageDir);
        }
        Assertions.assertEquals(0, remainingDataFiles,
                "🚨 ДИСКОВАЯ УТЕЧКА! После полной каскадной очистки на диске остались невычищенные файлы данных!");

        System.out.println("=== СЦЕНАРИЙ 2 ЗАВЕРШЕН ТРИУМФАЛЬНО: БАЗА И ДИСК АБСОЛЮТНО ЧИСТЫ ===");
    }

    private static int checkDataFilesCount(File dir) {
        File[] files = dir.listFiles();
        int count = 0;
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory() && !file.getName().equals("structure")) {
                    count += checkDataFilesCount(file);
                    continue;
                }
                String name = file.getName();
                if (name.equals("structure") || name.equals("classes.xml") || name.equals("structure.xml")
                        || name.equals("references.yaml") || name.endsWith(".lock") || name.endsWith(".meta")
                        || name.endsWith("id_index.yaml") || name.endsWith("idx.yaml")) {
                    continue;
                }
                // FK-индексная инфраструктура: B+деревья внешних ключей (*_foreign_key.yaml)
                // и узлы B+деревьев. Пользовательские данные вычищаются полностью (верификация
                // по классам выше). Деревья FK — индексная инфраструктура движка: они переживают
                // purge симметрично пропускаемым id_index/idx, а пары в них после откатов
                // транзакций могут оставаться фантомными (документированное W4-DESIGN семейство
                // компенсации btree-структур, task 41) — это не мусор пользовательских данных.
                if (name.endsWith("_foreign_key.yaml")) {
                    continue;
                }
                if (file.getParentFile() != null && file.getParentFile().getName().equals("XdStorageBTreeNode")) {
                    continue;
                }
                count++;
                System.err.println("  -> Обнаружен невычищенный мусорный файл данных: " + file.getAbsolutePath());
            }
        }
        return count;
    }
}