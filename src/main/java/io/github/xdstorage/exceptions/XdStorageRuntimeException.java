package io.github.xdstorage.exceptions;

/** The unchecked engine exception: contract violations and hard engine-state failures
 * that the API chose not to declare — calling a shut-down storage, a non-YAML format
 * with journaling, a malformed query (negative LIMIT, a non-{@code Comparable} range
 * comparison), a folder lock or format-marker refusal at open. */
public class XdStorageRuntimeException extends RuntimeException {

    /**
     *
     */
    private static final long serialVersionUID = -4833619775508098085L;

    /** Creates an exception without a message or cause. */
    public XdStorageRuntimeException() {
        super();
    }

    /** Protected constructor for subclasses needing full control (suppression, stack-trace wiring).
     *
     * @param message            the detail message
     * @param cause              the underlying cause
     * @param enableSuppression  whether suppression is enabled
     * @param writableStackTrace whether the stack trace is writable
     */
    protected XdStorageRuntimeException(String message, Throwable cause, boolean enableSuppression, boolean
            writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }

    /** Creates an exception with a message and a cause.
     *
     * @param message the detail message
     * @param cause   the underlying cause
     */
    public XdStorageRuntimeException(String message, Throwable cause) {
        super(message, cause);
    }

    /** Creates an exception with a message.
     *
     * @param message the detail message
     */
    public XdStorageRuntimeException(String message) {
        super(message);
    }

    /** Creates an exception with a cause.
     *
     * @param cause the underlying cause
     */
    public XdStorageRuntimeException(Throwable cause) {
        super(cause);
    }
}
