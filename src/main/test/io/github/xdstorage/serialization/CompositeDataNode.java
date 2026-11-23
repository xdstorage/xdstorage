package io.github.xdstorage.serialization;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;


// Детерминированный составной POJO-объект для проверки маршалинга полей СУБД
@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
public class CompositeDataNode {
    // Ядро СУБД ищет аннотацию идентификатора или поле id/idgeneration для рефлексии
    @XdStorageObjectId
    private Long idgeneration;
    private String nodeName;
    private int version;
    private double threshold;
    private boolean isActive;

    // Пустой конструктор обязателен для рефлексивной десериализации (newInstance)
    public CompositeDataNode() {
    }

    public CompositeDataNode(Long idgeneration, String nodeName, int version, double threshold, boolean isActive) {
        this.idgeneration = idgeneration;
        this.nodeName = nodeName;
        this.version = version;
        this.threshold = threshold;
        this.isActive = isActive;
    }

    public Long getIdgeneration() {
        return idgeneration;
    }

    public String getNodeName() {
        return nodeName;
    }

    public int getVersion() {
        return version;
    }

    public double getThreshold() {
        return threshold;
    }

    public boolean getIsActive() {
        return isActive;
    }

    public void setIdgeneration(Long idgeneration) {
        this.idgeneration = idgeneration;
    }

    public void setNodeName(String nodeName) {
        this.nodeName = nodeName;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public void setThreshold(double threshold) {
        this.threshold = threshold;
    }

    public void setIsActive(boolean active) {
        isActive = active;
    }
}
