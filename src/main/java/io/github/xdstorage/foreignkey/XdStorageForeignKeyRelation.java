package io.github.xdstorage.foreignkey;

import io.github.xdstorage.utils.XdStorageClassInfo;
import io.github.xdstorage.utils.XdStorageObjectField;

public class XdStorageForeignKeyRelation {

    private final XdStorageClassInfo classFrom;
    private final XdStorageClassInfo classTo;
    private final boolean reverseRelation;
    private final XdStorageObjectField field;

    XdStorageForeignKeyRelation(XdStorageClassInfo classFrom, XdStorageClassInfo classTo,
                                boolean reverseRelation, XdStorageObjectField field) {
        this.classFrom = classFrom;
        this.classTo = classTo;
        this.reverseRelation = reverseRelation;
        this.field = field;
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
}
