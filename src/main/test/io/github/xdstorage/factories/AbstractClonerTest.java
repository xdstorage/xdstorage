package io.github.xdstorage.factories;

import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.transaction.XdStorageTransaction;
import io.github.xdstorage.utils.XdStorageClassInfo;
import io.github.xdstorage.utils.XdStorageObjectField;
import org.junit.jupiter.api.BeforeEach;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.Mockito.*;

public abstract class AbstractClonerTest {

    protected IXdStorageReferenceProvider mockProvider;
    protected IXdStorage mockStorage;
    protected XdStorageTransaction mockTx;
    protected XdStorageSmartCloner smartCloner;

    @BeforeEach
    public void setUp() {
        mockProvider = mock(IXdStorageReferenceProvider.class);
        mockStorage = mock(IXdStorage.class);
        mockTx = mock(XdStorageTransaction.class);

        when(mockTx.getTransactionId()).thenReturn("tx-cloner-test-123");
        when(mockTx.getTimeout()).thenReturn(100L);

        smartCloner = new XdStorageSmartCloner(mockProvider);
    }

    // Хелперы для сборки циклических POJO сущностей
    public static class CyclicParent {
        private Long id;
        private CyclicChild child;
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public CyclicChild getChild() { return child; }
        public void setChild(CyclicChild child) { this.child = child; }
    }

    public static class CyclicChild {
        private Long id;
        private CyclicParent parent;
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public CyclicParent getParent() { return parent; }
        public void setParent(CyclicParent parent) { this.parent = parent; }
    }
}
