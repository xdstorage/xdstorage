package io.github.xdstorage.exceptions;

/**
 * No wrapper class could be obtained for an entity class (FP-3, docs/tasks/2.2.0/08). The
 * generated wrappers are what give an entity its transactional access, lazy loading and a
 * working {@code equals}/{@code hashCode}; the engine cannot run without them.
 *
 * <p>Thrown when neither path delivers a wrapper: no wrapper pre-generated at build time is
 * on the classpath, and the runtime generation cannot run — typically a JRE or a runtime
 * image without the {@code java.compiler} module, or a working directory that cannot
 * receive {@code ./gencode}. A runtime without any path fails already when a storage is
 * opened ({@code XdStorageProvider}); a class whose own wrapper is missing or stale fails
 * at its first use. Before 2.2.0 the engine logged one ERROR and went on with the dummy
 * wrappers; that silent degradation is gone.</p>
 */
public class XdStorageWrapperGenerationException extends XdStorageRuntimeException {

    /**
     *
     */
    private static final long serialVersionUID = 1L;

    /** {@code true} for the "this runtime has no Java compiler" failure. */
    private final boolean compilerMissing;

    /** How to fix a runtime without a compiler — the same hint in the messages that name it. */
    static final String FIX_HINT = "Fix: generate the wrappers at build time with the xdstorage-codegen Maven "
            + "plugin (docs/EMBEDDING.md, Running on a JRE), or run on a JDK.";

    /** Every message of this class carries its own {@code Fix: …} line. */
    private static final String FIX_PREFIX = "Fix: ";

    /** Creates an exception with a message and a cause.
     *
     * @param message the detail message
     * @param cause the underlying cause
     */
    public XdStorageWrapperGenerationException(final String message, final Throwable cause) {
        this(message, cause, false);
    }

    /** Creates an exception with a message.
     *
     * @param message the detail message
     */
    public XdStorageWrapperGenerationException(final String message) {
        this(message, null, false);
    }

    private XdStorageWrapperGenerationException(final String message, final Throwable cause,
                                               final boolean compilerMissing) {
        super(message, cause);
        this.compilerMissing = compilerMissing;
    }

    /**
     * Whether this is the "this runtime has no Java compiler" failure.
     *
     * @return {@code true} when no compiler was available and nothing was pre-generated
     */
    public boolean isCompilerMissing() {
        return compilerMissing;
    }

    /**
     * The runtime generation needs the directory {@code gencode} under the working
     * directory, and the working directory is not writable. The build-time wrappers are the
     * way out — they need no directory at all.
     *
     * @param directory the directory that could not be created
     * @param cause the I/O failure
     * @return the exception to throw
     */
    public static XdStorageWrapperGenerationException noWritableDirectory(final java.io.File directory,
                                                                         final java.io.IOException cause) {
        return new XdStorageWrapperGenerationException("XdStorage cannot create the runtime wrapper directory "
                + directory.getAbsolutePath() + " — the working directory is not writable. Fix: generate the "
                + "wrappers at build time with the xdstorage-codegen Maven plugin (docs/EMBEDDING.md, Running on "
                + "a JRE) — they need no directory; or start the application from a writable working directory.",
                cause);
    }

    /**
     * Keeps this failure recognizable through the engine's generic exception wrappers. The
     * read and write paths wrap a failure two or three times ("cannot clone and wrap
     * object", "invalid state of database: …"), and the operator would see only the outermost
     * line — so the wrapper generation's message is repeated there.
     *
     * @param message the message the wrapper is about to carry
     * @param cause the failure being wrapped
     * @return {@code message} followed by this failure's message when the cause chain holds
     *         one and the message does not already quote it; else {@code message} unchanged
     */
    public static String withHint(final String message, final Throwable cause) {
        if (message != null && message.contains(FIX_PREFIX)) {
            return message;
        }
        for (Throwable t = cause; t != null; t = t.getCause()) {
            if (t instanceof XdStorageWrapperGenerationException && t.getMessage() != null) {
                return (message == null ? "" : message + " — ") + t.getMessage();
            }
        }
        return message;
    }

    /**
     * The exception for a runtime without a Java compiler and no pre-generated wrapper for
     * the class.
     *
     * @param entity the class a wrapper was requested for
     * @return the exception to throw
     */
    public static XdStorageWrapperGenerationException noCompiler(final Class<?> entity) {
        return noCompiler(entity, null);
    }

    /**
     * The same failure with the error that revealed the missing compiler on the stack — an
     * image without the {@code java.compiler} module raises a {@code NoClassDefFoundError}
     * where the runtime generation starts.
     *
     * @param entity the class a wrapper was requested for
     * @param cause the error, may be {@code null}
     * @return the exception to throw
     */
    public static XdStorageWrapperGenerationException noCompiler(final Class<?> entity, final Throwable cause) {
        return new XdStorageWrapperGenerationException("XdStorage cannot create the wrapper class for "
                + entity.getName() + ": no pre-generated wrapper is on the classpath and this runtime has no "
                + "Java compiler (a JRE, or a runtime image without the java.compiler module). " + FIX_HINT, cause,
                true);
    }

    /**
     * A pre-generated wrapper IS on the classpath but was generated for another shape of the
     * class (or by another engine version), and no runtime compiler is available to replace it.
     *
     * @param entity the class a wrapper was requested for
     * @param details what is stale — the recorded and the expected fingerprint
     * @return the exception to throw
     */
    public static XdStorageWrapperGenerationException stale(final Class<?> entity, final String details) {
        return new XdStorageWrapperGenerationException("XdStorage refuses the pre-generated wrapper class for "
                + entity.getName() + ": it is STALE — " + details + " (the class or the engine changed after the "
                + "wrappers were built) and this runtime has no Java compiler to generate a fresh one (a JRE, or "
                + "a runtime image without the java.compiler module). Fix: rebuild the application to refresh "
                + "the wrappers (a clean build), or run on a JDK.", null, true);
    }

    /**
     * Whether a {@code NoClassDefFoundError} raised where the runtime generation starts means
     * "this image has no compiler": the missing class belongs to the compiler API. Such an
     * image cannot even link {@code javax.tools}; until 2.2.0 the error escaped unnamed.
     *
     * @param error the error thrown where the runtime generation starts
     * @return {@code true} when the missing class is part of the compiler API
     */
    public static boolean isMissingCompilerApi(final NoClassDefFoundError error) {
        final String missing = error.getMessage();
        return missing != null && (missing.startsWith("javax/tools/") || missing.startsWith("com/sun/source/")
                || missing.startsWith("com/sun/tools/"));
    }
}
