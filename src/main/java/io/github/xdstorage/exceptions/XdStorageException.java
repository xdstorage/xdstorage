package io.github.xdstorage.exceptions;

/** The checked storage-engine exception: a data or contract failure inside a storage
 * operation (a missing record, a class without an id field, a commit failure, a
 * rollback-only commit). Thrown by every CRUD method declared on
 * {@link io.github.xdstorage.IXdStorage}.
 *
 * <p>A failed operation throws but does NOT roll the transaction back — the caller
 * decides ({@code rollbackTransaction} or retry); commit of a rollback-only
 * transaction throws this. */
public class XdStorageException extends Exception {

    /**
     *
     */
    private static final long serialVersionUID = -2872726377110939735L;

    /** Creates an exception without a message or cause. */
    public XdStorageException() {
        super();
    }

    /** Protected constructor for subclasses needing full control (suppression, stack-trace wiring).
     *
     * @param message            the detail message
     * @param cause              the underlying cause
     * @param enableSuppression  whether suppression is enabled
     * @param writableStackTrace whether the stack trace is writable
     */
    protected XdStorageException(final String message, final Throwable cause, final boolean enableSuppression, final
            boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }

    /** Creates an exception with a message and a cause.
     *
     * @param message the detail message
     * @param cause   the underlying cause
     */
    public XdStorageException(final String message, final Throwable cause) {
        super(XdStorageWrapperGenerationException.withHint(message, cause), cause);
    }

    /** Creates an exception with a message.
     *
     * @param message the detail message
     */
    public XdStorageException(final String message) {
        super(message);
    }

    /** Creates an exception with a cause.
     *
     * @param cause the underlying cause
     */
    public XdStorageException(final Throwable cause) {
        super(XdStorageWrapperGenerationException.withHint(cause == null ? null : cause.toString(), cause), cause);
    }
}
