package io.github.xdstorage.foreignkey;

import io.github.xdstorage.utils.XdStorageClassInfo;
import io.github.xdstorage.utils.XdStorageObjectField;

import java.util.Objects;

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

    public XdStorageClassInfo getClassFrom() {
        return classFrom;
    }

    public XdStorageClassInfo getClassTo() {
        return classTo;
    }

    public boolean isReverseRelation() {
        return reverseRelation;
    }

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
