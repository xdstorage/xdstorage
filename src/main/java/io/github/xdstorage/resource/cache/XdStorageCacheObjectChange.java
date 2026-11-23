package io.github.xdstorage.resource.cache;

/**
 * Материализованный (прочитанный/транзитный) объект в контуре транзакции:
 * результат клонирования при чтении или собственная мутация, ожидающая коммита.
 * Вынесен из XdStorageResourceCache при разделении класса (ранее — приватный
 * внутренний класс ObjectChange).
 */
public final class XdStorageCacheObjectChange {

    /** The materialized object instance; replaced in place by later mutations of the same transaction. */
    public Object object;

    /** The kind of change this entry materialized from (read, insert, update). */
    public final XdStorageCacheChange change;

    /** The version baseline of the read (transaction start time, or the previous read's timestamp on refresh). */
    public final long readTimestamp;

    /** Версия записи кэша на момент материализации (для RC-освежения). */
    public volatile long recordVersion = -1;

    /**
     * Creates one materialized-object entry.
     *
     * @param object        the materialized object instance this entry holds
     * @param change        the kind of change this entry materialized from
     * @param readTimestamp the version baseline of the read
     */
    public XdStorageCacheObjectChange(final Object object, final XdStorageCacheChange change, final long readTimestamp)
            {
        this.object = object;
        this.change = change;
        this.readTimestamp = readTimestamp;
    }
}
