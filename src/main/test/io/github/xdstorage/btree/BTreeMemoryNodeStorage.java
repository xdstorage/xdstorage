package io.github.xdstorage.btree;

import io.github.xdstorage.MockStorage;
import io.github.xdstorage.transaction.IXdStorageTransaction;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe in-memory node store for the B+tree tests: assigns node ids on save and keeps
 * exactly the nodes the tree saved/updated and did not delete — the basis of the "no orphan
 * node files" invariant (INV-13 of {@link BTreeStructureChecker}). An update of a node the
 * tree already deleted re-stores it (like a file write would), so such zombie writes surface
 * as orphans.
 */
final class BTreeMemoryNodeStorage extends MockStorage {

    private final Map<Long, XdStorageBTreeNode> nodes = new ConcurrentHashMap<>();
    private final AtomicLong idSequence = new AtomicLong(1000L);

    @Override
    public void save(final Object object, final IXdStorageTransaction transaction) {
        if (object instanceof XdStorageBTreeNode) {
            final XdStorageBTreeNode node = (XdStorageBTreeNode) object;
            if (node.getId() == null) {
                node.setId(idSequence.incrementAndGet());
            }
            nodes.put(node.getId(), node);
        }
    }

    @Override
    public void update(final Object object, final IXdStorageTransaction transaction) {
        if (object instanceof XdStorageBTreeNode && ((XdStorageBTreeNode) object).getId() != null) {
            nodes.put(((XdStorageBTreeNode) object).getId(), (XdStorageBTreeNode) object);
        }
    }

    @Override
    public void delete(final Object reference, final IXdStorageTransaction transaction) {
        if (reference instanceof XdStorageBTreeNode && ((XdStorageBTreeNode) reference).getId() != null) {
            nodes.remove(((XdStorageBTreeNode) reference).getId());
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T load(final Class<T> cl, final Object id, final IXdStorageTransaction transaction) {
        if (cl == XdStorageBTreeNode.class && id instanceof Long) {
            return (T) nodes.get(id);
        }
        return null;
    }

    Set<Long> storedNodeIds() {
        return new HashSet<>(nodes.keySet());
    }
}
