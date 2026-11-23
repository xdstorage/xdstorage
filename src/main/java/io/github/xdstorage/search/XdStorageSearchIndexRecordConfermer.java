package io.github.xdstorage.search;


import java.util.*;

/** The id-conferment coordinator of one index-record assembly: nested child entities read
 * off a parent may not have their id assigned yet at indexing time, so the assembler
 * registers them here and defers the record's identity stamping until an observer reports
 * every deferred object got its id. When the last deferred id arrives, the record's id and
 * data-journal resource id are written (or the deferred root id is substituted).
 *
 * <p>Created fresh per {@code buildIndexRecord} run and touched only by that run's
 * observers — not shared across threads.
 *
 * <p>Engine-internal — not part of the public API. */
public class XdStorageSearchIndexRecordConfermer {

    private Object id;

    private Map<Class<?>, Set<Object>> objectsWithoutIdentifier = new HashMap<>();

    /** Creates an empty tracker with no deferred objects and no deferred root id. */
    public XdStorageSearchIndexRecordConfermer() {
    }

    /**
     * Registers a nested object whose id is not assigned yet, deferring its part of the
     * record until the id arrives.
     *
     * @param object the not-yet-identified object
     */
    public void registerObjectWithoutIdentifier(final Object object) {
        final Class<?> cl = object.getClass();

        Set<Object> references = objectsWithoutIdentifier.get(cl);
        if(references == null) {
            objectsWithoutIdentifier.put(cl, references = new HashSet<>());
        }

        references.add(object);
    }

    /**
     * Marks a previously registered object as identified; when the last deferred object of
     * the assembly is unregistered, {@link #noObjectsWithoutIdentifier()} turns {@code true}.
     * Must be paired with a prior {@link #registerObjectWithoutIdentifier(Object)}.
     *
     * @param object the now-identified object
     * @throws NullPointerException when the object's class was never registered — the
     *         unregister path assumes a paired register
     */
    public void unregisterObjectWithoutIdentifier(final Object object) {
        final Class<?> cl = object.getClass();

        Set<Object> references = objectsWithoutIdentifier.get(cl);
        references.remove(object);

        if(references.isEmpty()) {
            objectsWithoutIdentifier.remove(cl);
        }
    }

    /**
     * Whether every deferred object of the assembly got its id.
     *
     * @return {@code true} when no registered-but-unidentified object remains
     */
    public boolean noObjectsWithoutIdentifier() {
        return objectsWithoutIdentifier.isEmpty();
    }

    /**
     * Defers the root record's id: used when the root itself was registered unidentified
     * and a child's id arrives first.
     *
     * @param id the root object's id once assigned
     */
    public void setIdForRootObject(final Object id) {
        this.id = id;
    }

    /**
     * The deferred root id.
     *
     * @return the root object's id, or {@code null} when the root already had one
     */
    public Object getIdForRootObject() {
        return id;
    }
}
