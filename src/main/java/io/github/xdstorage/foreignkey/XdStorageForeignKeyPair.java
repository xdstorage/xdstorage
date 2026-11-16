package io.github.xdstorage.foreignkey;

import io.github.xdstorage.btree.XdStorageBTree;
import io.github.xdstorage.btree.XdStorageBTreeId;
import io.github.xdstorage.utils.XdStorageClassInfo;
import io.github.xdstorage.utils.XdStorageObjectField;

/**
 * A single FK pair staged in the transaction's FK write-set (the FK cache).
 *
 * <p>The ids are POSITIONAL, mirroring the engine's historical contract
 * (docs/analysis/FOREIGN_KEYS.md §7 A2.1/A2.2): {@code frontId} is the key
 * written into the front (parent→child) tree and {@code reverseId} the key written into
 * the reverse (child→parent) tree. For the income-relation path frontId=parentId and
 * reverseId=childId; for the outcome-relation path the engine passes them swapped
 * (frontId=childId, reverseId=parentId) — the pair preserves that order verbatim, so a
 * committed write-set is byte-for-byte identical to what the resource used to write
 * directly at registration time.</p>
 *
 * <p>The pair is a STAGED INTENT value: the actual tree writes happen at operation time
 * inside the FK trees' own infrastructure 2PC contour (registration/dissolution — the only
 * contour-correct window), while this record tracks the intent through the phase pipeline
 * and feeds the read-through overlay and the phase-1 structural validation.</p>
 */
public final class XdStorageForeignKeyPair {

    final Comparable<?> frontId;
    final Comparable<?> reverseId;
    final XdStorageClassInfo parentClassInfo;
    final XdStorageClassInfo childClassInfo;
    final XdStorageObjectField parentField;
    final XdStorageForeignKeyObjectChangeType type;

    private final XdStorageBTreeId frontTreeId;
    private final XdStorageBTreeId reverseTreeId;

    public XdStorageForeignKeyPair(final Comparable<?> frontId, final Comparable<?> reverseId,
                                   final XdStorageClassInfo parentClassInfo, final XdStorageClassInfo childClassInfo,
                                   final XdStorageObjectField parentField,
                                   final XdStorageForeignKeyObjectChangeType type) {
        this.frontId = frontId;
        this.reverseId = reverseId;
        this.parentClassInfo = parentClassInfo;
        this.childClassInfo = childClassInfo;
        this.parentField = parentField;
        this.type = type;
        // The tree ids EXACTLY as the FK resource derives them (one naming, no forks)
        this.frontTreeId = new XdStorageBTreeId(parentClassInfo.getClazz(),
                childClassInfo.getClazz().getSimpleName() + "_" + parentField.getName() + "_foreign_key");
        this.reverseTreeId = new XdStorageBTreeId(childClassInfo.getClazz(),
                parentClassInfo.getClazz().getSimpleName() + "_" + parentField.getName() + "_foreign_key");
    }

    /** The relation-scoped identity (no type): INSERT/DELETE of the same pair share it. */
    public String baseKey() {
        return frontId + "\u001F" + reverseId + "\u001F" + parentClassInfo.getClazz().getName()
                + "\u001F" + childClassInfo.getClazz().getName() + "\u001F" + parentField.getName();
    }

    public XdStorageForeignKeyObjectChangeType type() {
        return type;
    }

    public Comparable<?> frontId() {
        return frontId;
    }

    public Comparable<?> reverseId() {
        return reverseId;
    }

    /** The front (parent→child) tree id this pair's relation maps to. */
    public XdStorageBTreeId frontTreeId() {
        return frontTreeId;
    }

    /** The reverse (child→parent) tree id this pair's relation maps to. */
    public XdStorageBTreeId reverseTreeId() {
        return reverseTreeId;
    }

    /** Whether this pair belongs to the given (front, reverse) tree pair. */
    public boolean belongsTo(final XdStorageBTreeId frontTreeId, final XdStorageBTreeId reverseTreeId) {
        return this.frontTreeId.equals(frontTreeId) && this.reverseTreeId.equals(reverseTreeId);
    }

    /** Whether both positional ids are resolved (a deferred pair has neither). */
    public boolean isResolved() {
        return frontId != null && reverseId != null;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (!(o instanceof XdStorageForeignKeyPair)) return false;
        final XdStorageForeignKeyPair that = (XdStorageForeignKeyPair) o;
        return String.valueOf(frontId).equals(String.valueOf(that.frontId))
                && String.valueOf(reverseId).equals(String.valueOf(that.reverseId))
                && parentClassInfo.getClazz().equals(that.parentClassInfo.getClazz())
                && childClassInfo.getClazz().equals(that.childClassInfo.getClazz())
                && parentField.getName().equals(that.parentField.getName());
    }

    @Override
    public int hashCode() {
        int result = String.valueOf(frontId).hashCode();
        result = 31 * result + String.valueOf(reverseId).hashCode();
        result = 31 * result + parentClassInfo.getClazz().hashCode();
        result = 31 * result + childClassInfo.getClazz().hashCode();
        result = 31 * result + parentField.getName().hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "ForeignKeyPair{" + type + " front=" + frontId + " reverse=" + reverseId
                + " relation=" + parentClassInfo.getClazz().getSimpleName() + "#"
                + parentField.getName() + " -> " + childClassInfo.getClazz().getSimpleName() + '}';
    }
}
