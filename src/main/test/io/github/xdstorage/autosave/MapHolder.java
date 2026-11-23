package io.github.xdstorage.autosave;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageAutoSave;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;

import java.util.Map;

/** MAP-shape fixture: the annotated Map's VALUES are the cascade candidates (keys are not). */
@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
public class MapHolder {
    @XdStorageObjectId
    private String id;

    @XdStorageAutoSave
    private Map<String, OrderLine> linesBySku;

    @XdStorageAutoSave
    private Map<String, String> notes;   // values without an id-field: skipped by the cascade

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Map<String, OrderLine> getLinesBySku() { return linesBySku; }
    public void setLinesBySku(Map<String, OrderLine> linesBySku) { this.linesBySku = linesBySku; }
    public Map<String, String> getNotes() { return notes; }
    public void setNotes(Map<String, String> notes) { this.notes = notes; }
}
