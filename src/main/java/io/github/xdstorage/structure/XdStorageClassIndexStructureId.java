package io.github.xdstorage.structure;

import java.util.Objects;

/**
 * The composite identifier of a search index structure record: the stored class the index
 * belongs to plus the index name. Carries value semantics — equality and the hash code are
 * computed from both parts — so it can serve as the object id of the persisted record.
 *
 * <p>Instances are created by the structure manager while building a class structure
 * snapshot. Engine-internal — not part of the public API.</p>
 */
public class XdStorageClassIndexStructureId {

    private String name;

    // BUG-B (docs/tasks/2.1.0/16): the FQCN of the owning class — the identity belongs to
    // the NAME (two live generations of one FQCN are one stored class, one index record).
    private String type;

    /**
     * Creates an empty identifier.
     */
    public XdStorageClassIndexStructureId() {
    }

    /**
     * Returns the search index name part of the identifier.
     *
     * @return the search index name
     */
    public String getName() {
        return name;
    }

    /**
     * Sets the search index name part of the identifier.
     *
     * @param name the search index name
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * Returns the FQCN of the owning class part of the identifier.
     *
     * @return the fully-qualified name of the stored class the index belongs to
     */
    public String getType() {
        return type;
    }

    /**
     * Sets the FQCN of the owning class part of the identifier.
     *
     * @param type the fully-qualified name of the stored class the index belongs to
     */
    public void setType(String type) {
        this.type = type;
    }

    /**
     * Identifiers are equal when both the index name and the owning class match.
     *
     * @param o the object to compare with
     * @return {@code true} when {@code o} is a {@code XdStorageClassIndexStructureId} with
     *         equal name and type
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        final XdStorageClassIndexStructureId that = (XdStorageClassIndexStructureId) o;
        return Objects.equals(name, that.name) &&
                Objects.equals(type, that.type);
    }

    /**
     * @return a hash code derived from the index name and the owning class
     */
    @Override
    public int hashCode() {
        return Objects.hash(name, type);
    }

    /**
     * Renders the identifier as {@code SimpleClassName:indexName}.
     *
     * @return the owning class simple name, a colon and the index name
     */
    @Override
    public String toString() {
        final String fqcn = type;
        final String simple = fqcn != null && fqcn.contains(".")
                ? fqcn.substring(fqcn.lastIndexOf('.') + 1)
                : fqcn;
        return simple + ":" + name;
    }
}
