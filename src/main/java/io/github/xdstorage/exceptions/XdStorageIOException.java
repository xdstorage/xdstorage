package io.github.xdstorage.exceptions;

/** The unchecked wrapper for unexpected {@code java.io} failures inside engine
 * internals that do not declare checked exceptions. */
public class XdStorageIOException extends RuntimeException {

    /**
     *
     */
    private static final long serialVersionUID = -8734889070625205556L;

    /** Creates an exception without a message or cause. */
    public XdStorageIOException() {
        super();
    }

    /** Protected constructor for subclasses needing full control (suppression, stack-trace wiring).
     *
     * @param message            the detail message
     * @param cause              the underlying cause
     * @param enableSuppression  whether suppression is enabled
     * @param writableStackTrace whether the stack trace is writable
     */
    protected XdStorageIOException(String message, Throwable cause, boolean enableSuppression, boolean
            writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }

    /** Creates an exception with a message and a cause.
     *
     * @param message the detail message
     * @param cause   the underlying cause
     */
    public XdStorageIOException(String message, Throwable cause) {
        super(message, cause);
    }

    /** Creates an exception with a message.
     *
     * @param message the detail message
     */
    public XdStorageIOException(String message) {
        super(message);
    }

    /** Creates an exception with a cause.
     *
     * @param cause the underlying cause
     */
    public XdStorageIOException(Throwable cause) {
        super(cause);
    }
}
