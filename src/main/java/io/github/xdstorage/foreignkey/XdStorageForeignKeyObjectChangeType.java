package io.github.xdstorage.foreignkey;

/**
 * The object change kind the FK engine's trigger processes.
 */
public enum XdStorageForeignKeyObjectChangeType {

    /** A new object is being saved: its FK pairs register (possibly deferred). */
    INSERT,
    /** An existing object is being updated: pair bookkeeping runs through the parent-update diff. */
    UPDATE,
    /** An object is being deleted: its FK pairs dissolve and the delete waiters resolve. */
    DELETE
}
