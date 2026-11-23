package io.github.xdstorage.properties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** PROPTEST Этап 2: the container-jungle entity (the embedded default policy). */
public class PropJungle {
    private List<List<String>> deepList = new ArrayList<>();
    private Map<String, List<Long>> listMap = new HashMap<>();
    private List<Map<String, String>> mapList = new ArrayList<>();
    private String[] stringArray = new String[0];
    private Long[] longArray = new Long[0];
    private int[] intArray = new int[0];

    public List<List<String>> getDeepList() {
        return deepList;
    }

    public void setDeepList(final List<List<String>> deepList) {
        this.deepList = deepList;
    }

    public Map<String, List<Long>> getListMap() {
        return listMap;
    }

    public void setListMap(final Map<String, List<Long>> listMap) {
        this.listMap = listMap;
    }

    public List<Map<String, String>> getMapList() {
        return mapList;
    }

    public void setMapList(final List<Map<String, String>> mapList) {
        this.mapList = mapList;
    }

    public String[] getStringArray() {
        return stringArray;
    }

    public void setStringArray(final String[] stringArray) {
        this.stringArray = stringArray;
    }

    public Long[] getLongArray() {
        return longArray;
    }

    public void setLongArray(final Long[] longArray) {
        this.longArray = longArray;
    }

    public int[] getIntArray() {
        return intArray;
    }

    public void setIntArray(final int[] intArray) {
        this.intArray = intArray;
    }
}
