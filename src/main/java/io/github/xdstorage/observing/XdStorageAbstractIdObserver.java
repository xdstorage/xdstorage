package io.github.xdstorage.observing;

import io.github.xdstorage.exceptions.XdStorageRuntimeException;

/**
 * Base class of id observers: instances attach themselves to any id-observable wrapper
 * they are asked to observe and get called back when the wrapper's deferred id is set.
 *
 * <p>Thread-safety is the concern of the concrete subclass — the callback
 * {@code onNewIdIsSet} may be invoked from whichever thread mutates the id.
 * Engine-internal — not part of the public API.</p>
 */
public abstract class XdStorageAbstractIdObserver {

    /** Engine-internal callback host: nothing to initialize. */
    public XdStorageAbstractIdObserver() {
    }

    /**
     * Attaches this observer to the given object when it is an id-observable wrapper;
     * plain (non-wrapper) objects are silently ignored. This is the self-subscription
     * entry point, typically called right after an observable wrapper is created.
     *
     * @param object the candidate wrapper to observe; must not be {@code null}
     * @throws XdStorageRuntimeException when {@code object} is {@code null}
     */
    public void observeIfPossible(final Object object) {
        if (object == null)
            throw new XdStorageRuntimeException("cannot observe for null object");

        final Class<?> cl = object.getClass();
        if (IXdStorageIdObservableWrapper.class.isAssignableFrom(cl)) {
            final IXdStorageIdObservableWrapper wrapper = (IXdStorageIdObservableWrapper) object;
            wrapper.addObserver(this);
        }
    }

    /**
     * Callback fired by the wrapper when its id setter runs — the deferred-id
     * notification point.
     *
     * @param wrapper the observable wrapper whose id was set
     * @param id      the id value that was set
     */
    public abstract void onNewIdIsSet(IXdStorageIdObservableWrapper wrapper, Object id);
}