package io.github.xdstorage.foreignkey;

import io.github.xdstorage.utils.XdStorageClassInfo;
import io.github.xdstorage.utils.XdStorageObjectField;

import java.util.Objects;

/**
 * One declared foreign-key relation: the parent (referencing) side, the child
 * (referenced) side, the declaring field and the registration flags. Each declared
 * {@code @XdStorageAutoSave}/{@code @XdStorageParentObject} field yields a canonical
 * relation plus a mirrored ("reverse") relation registered on the counterpart class.
 */
public class XdStorageForeignKeyRelation {

    private final XdStorageClassInfo classFrom;
    private final XdStorageClassInfo classTo;
    private final boolean reverseRelation;
    private final boolean parentAnchored;
    private final XdStorageObjectField field;

    XdStorageForeignKeyRelation(XdStorageClassInfo classFrom, XdStorageClassInfo classTo,
                                boolean reverseRelation, XdStorageObjectField field) {
        this(classFrom, classTo, reverseRelation, true, field);
    }

    XdStorageForeignKeyRelation(XdStorageClassInfo classFrom, XdStorageClassInfo classTo,
                                boolean reverseRelation, boolean parentAnchored, XdStorageObjectField field) {
        this.classFrom = classFrom;
        this.classTo = classTo;
        this.reverseRelation = reverseRelation;
        this.parentAnchored = parentAnchored;
        this.field = field;
    }

    /**
     * FNKEY (docs/tasks/2.1.0/42): whether the NOT-reverse relation is anchored at the
     * parent side (the {@code @XdStorageAutoSave} field) — {@code false} for relations
     * declared only through the child's {@code @XdStorageParentObject} field. The delete
     * invariants (the child-delete / parent-delete expectations) are enforced on
     * parent-anchored relations only; child-anchored relations dissolve blindly.
     *
     * @return {@code true} when the relation is owned by the parent-side annotation
     */
    public boolean isParentAnchored() {
        return parentAnchored;
    }

    /**
     * The relation's From side: the parent (referencing) class on a canonical relation,
     * flipped to the counterpart side on a reverse relation.
     * @return the From-side class info
     */
    public XdStorageClassInfo getClassFrom() {
        return classFrom;
    }

    /**
     * The relation's To side: the child (referenced) class on a canonical relation,
     * flipped to the counterpart side on a reverse relation.
     * @return the To-side class info
     */
    public XdStorageClassInfo getClassTo() {
        return classTo;
    }

    /**
     * Whether this entry is the mirrored relation registered on the counterpart class
     * (with the {@code REVERSE_RELATION_FIELD} placeholder) rather than the canonical
     * relation owned by the declaring field.
     * @return {@code true} for the mirrored (incoming-from-the-other-side) relation
     */
    public boolean isReverseRelation() {
        return reverseRelation;
    }

    /**
     * The field declaring the relation (the {@code @XdStorageAutoSave} or
     * {@code @XdStorageParentObject} field); the placeholder
     * {@code REVERSE_RELATION_FIELD} on reverse relations.
     * @return the declaring field, or the reverse-relation placeholder
     */
    public XdStorageObjectField getField() {
        return field;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        XdStorageForeignKeyRelation that = (XdStorageForeignKeyRelation) o;
        return reverseRelation == that.reverseRelation && parentAnchored == that.parentAnchored && classFrom.equals(that.classFrom) && classTo.equals(that.classTo) && field.equals(that.field);
    }

    @Override
    public int hashCode() {
        return Objects.hash(classFrom, classTo, reverseRelation, parentAnchored, field);
    }

    @Override
    public String toString() {
        return "XdStorageForeignKeyRelation{" +
                "classFrom=" + classFrom +
                ", classTo=" + classTo +
                ", reverseRelation=" + reverseRelation +
                ", parentAnchored=" + parentAnchored +
                ", field=" + field +
                '}';
    }
}
