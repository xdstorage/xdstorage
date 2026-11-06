package io.github.xdstorage.utils;

import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.BeforeEach;

import static org.mockito.Mockito.*;

public abstract class AbstractUtilsTest {

    protected IXdStorage mockStorage;
    protected IXdStorageTransaction mockTx;

    @BeforeEach
    public void baseSetUp() {
        mockStorage = mock(IXdStorage.class);
        mockTx = mock(IXdStorageTransaction.class);
        when(mockTx.getTransactionId()).thenReturn("tx-test-utils-id");
        when(mockTx.getTimeout()).thenReturn(100L);
    }

    // Тестовые JavaBeans сущности для рефлексивной проверки
    public static class SampleEntity {
        private Long id;
        private String value;
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
    }

    public static class CyclicParentEntity {
        private Long id;
        private CyclicChildEntity child;
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public CyclicChildEntity getChild() { return child; }
        public void setChild(CyclicChildEntity child) { this.child = child; }
    }

    public static class CyclicChildEntity {
        private Long id;
        private CyclicParentEntity parent;
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public CyclicParentEntity getParent() { return parent; }
        public void setParent(CyclicParentEntity parent) { this.parent = parent; }
    }
}
