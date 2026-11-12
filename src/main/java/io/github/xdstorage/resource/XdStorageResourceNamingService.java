package io.github.xdstorage.resource;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.btree.XdStorageBTree;
import io.github.xdstorage.btree.XdStorageBTreeNode;
import io.github.xdstorage.utils.XdStorageObjectIdField;
import io.github.xdstorage.utils.XdStorageObjectUtils;
import io.github.xdstorage.utils.XdStorageStringUtils;

/**
 * The default naming service: builds resource ids as relative file paths under the storage
 * folder — data resources under {@code <folder>/data/<Class>/...}, index resources under the
 * same class directory, structure records under {@code <folder>/structure/} — with the
 * configured file suffix. Stateless after construction and thread-safe.
 *
 * <p>Engine-internal — not part of the public API.</p>
 */
public class XdStorageResourceNamingService implements IXdStorageResourceNamingService {

    private static final String STRUCTURE_DIR = "structure";
    private static final String INDEX_DIR = "indexes";
    private static final String DATA_DIR = "data";

    static {
        System.err.println("[NAMING] INDEX_DIR=" + INDEX_DIR);
    }

    private final String folder;

    private final String fileSuffix;

    /**
     * Builds a naming service rooted at the given storage folder.
     *
     * @param folder     the storage folder the paths are relative to (its prefix)
     * @param fileSuffix the file suffix of resource files
     */
    public XdStorageResourceNamingService(final String folder, final String fileSuffix) {
        this.folder = folder;
        this.fileSuffix = fileSuffix;
    }

    @Override
    public <T> Object getResourceId(final XdStoragePolicy policy, final Class<?> cl, final T object, final Object objectId) {
        Object resourceName = null;

        final XdStorageObjectIdField idField = XdStorageObjectUtils.getClassInfo(cl).getIdField();

        if (object == null) {
            resourceName = getObjectResourceName(policy, cl, null, castObjectIdToString(objectId));
        } else if (idField != null) {
            resourceName = getObjectResourceName(policy, cl, castObjectIdToString(idField.get(object)), castObjectIdToString(objectId));
        }

        return resourceName;
    }

    @Override
    public Object getIndexResourceId(final String indexName, final XdStoragePolicy policy, final Class<?> cl) {
        return folder + "/" + INDEX_DIR + "/" + cl.getSimpleName() + "/" + indexName + "." + fileSuffix;
    }

    @Override
    public Object getForeignKeyResourceId(String suffix, Class<?> parentClass, Class<?> childClass, String parentClassField) {
        String foreignKeyName = parentClass.getSimpleName() + "-" + childClass.getSimpleName() + "-" + parentClassField;
        return folder + "/" + INDEX_DIR + "/" + parentClass.getSimpleName() + "/" + foreignKeyName + "-fk" + "." + fileSuffix;
    }

    @Override
    public Object getStructureResourceId(final Class<?> cl) {
        return folder + "/" + STRUCTURE_DIR + "/" + cl.getSimpleName() + "." + fileSuffix;
    }

    private Object getObjectResourceName(final XdStoragePolicy policy, final Class<?> cl, final String objectId, final String fragmentId) {
        Object resourceName = null;

        if (cl == XdStorageBTree.class || cl == XdStorageBTreeNode.class) {
            if (XdStorageStringUtils.isBlank(objectId)) {
                resourceName = folder + "/" + INDEX_DIR + "/" + cl.getSimpleName() + "/" + cl.getSimpleName() + "-" + fragmentId + "." + fileSuffix;
            } else {
                resourceName = folder + "/" + INDEX_DIR + "/" + cl.getSimpleName() + "/" + cl.getSimpleName() + "-" + objectId + "." + fileSuffix;
            }
        } else if (policy == XdStoragePolicy.StoreAsClassObjects) {
            if (XdStorageStringUtils.isBlank(fragmentId)) {
                resourceName = folder + "/" + DATA_DIR + "/" + cl.getSimpleName() + "/" + cl.getSimpleName() + "." + fileSuffix;
            } else {
                resourceName = folder + "/" + DATA_DIR + "/" + cl.getSimpleName() + "/" + cl.getSimpleName() + "-" + fragmentId + "." + fileSuffix;
            }
        } else if (policy == XdStoragePolicy.StoreAsSingleObject) {
            if (XdStorageStringUtils.isBlank(objectId)) {
                resourceName = folder + "/" + DATA_DIR + "/" + cl.getSimpleName() + "/" + cl.getSimpleName() + "-" + fragmentId + "." + fileSuffix;
            } else {
                resourceName = folder + "/" + DATA_DIR + "/" + cl.getSimpleName() + "/" + cl.getSimpleName() + "-" + objectId + "." + fileSuffix;
            }
        }

        return resourceName;
    }

    private String castObjectIdToString(final Object objectId) {
        return objectId == null ? "" : objectId instanceof Class ? ((Class)objectId).getSimpleName() : objectId.toString();
    }
}
