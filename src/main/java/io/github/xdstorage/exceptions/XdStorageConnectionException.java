package io.github.xdstorage.exceptions;

/** The checked exception for storage-level I/O failures: the file system refused or
 * interrupted a read/write (a missing file, a full disk, a lock conflict). Thrown by
 * every CRUD method declared on {@link io.github.xdstorage.IXdStorage} alongside
 * {@link XdStorageException}, which it does NOT extend — catch both (or catch this
 * first: it means "retry may help", not "your data is wrong"). */
public class XdStorageConnectionException extends Exception {

    /** Creates an exception without a message or cause. */
    public XdStorageConnectionException() {
        super();
    }

    /** Protected constructor for subclasses needing full control (suppression, stack-trace wiring).
     *
     * @param message            the detail message
     * @param cause              the underlying cause
     * @param enableSuppression  whether suppression is enabled
     * @param writableStackTrace whether the stack trace is writable
     */
    protected XdStorageConnectionException(final String message, final Throwable cause, final boolean enableSuppression,
            final boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }

    /** Creates an exception with a message and a cause.
     *
     * @param message the detail message
     * @param cause   the underlying cause
     */
    public XdStorageConnectionException(final String message, final Throwable cause) {
        super(message, cause);
    }

    /** Creates an exception with a message.
     *
     * @param message the detail message
     */
    public XdStorageConnectionException(final String message) {
        super(message);
    }

    /** Creates an exception with a cause.
     *
     * @param cause the underlying cause
     */
    public XdStorageConnectionException(final Throwable cause) {
        super(cause);
    }
}
