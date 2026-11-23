package io.github.xdstorage.entities;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;

import java.util.List;
import java.util.Map;

/**
 * YAMLSTR fixture (docs/tasks/2.2.0/40): an independent entity carrying a string in every
 * position the format has — a field, a {@code char}, a list, a map (keys and values) and an
 * array. Top-level on purpose: the runtime wrapper codegen cannot compile nested classes.
 */
@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
public class XdTextSampler {

    @XdStorageObjectId
    private String id;

    private String text;
    private char letter;
    private List<String> tags;
    private Map<String, String> meta;
    private String[] labels;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public char getLetter() { return letter; }
    public void setLetter(char letter) { this.letter = letter; }
    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }
    public Map<String, String> getMeta() { return meta; }
    public void setMeta(Map<String, String> meta) { this.meta = meta; }
    public String[] getLabels() { return labels; }
    public void setLabels(String[] labels) { this.labels = labels; }
}
