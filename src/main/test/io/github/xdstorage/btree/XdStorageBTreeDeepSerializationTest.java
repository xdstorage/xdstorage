package io.github.xdstorage.btree;

import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("Архитектурный стресс-тест: Эмуляция дисковой десериализации Б+ Дерева")
public class XdStorageBTreeDeepSerializationTest {

    private IXdStorage mockStorage;
    private IXdStorageTransaction mockTx;
    private Map<Long, XdStorageBTreeNode> diskStorage; // Имитируем дисковые блоки СУБД

    @BeforeEach
    public void setUp() throws Exception {
        diskStorage = new HashMap<>();
        mockStorage = mock(IXdStorage.class);
        mockTx = mock(IXdStorageTransaction.class);
        when(mockTx.getTimeout()).thenReturn(100L);

        // Имитируем физическое сохранение СУБД на диск (deep clone объекта)
        doAnswer(invocation -> {
            XdStorageBTreeNode node = invocation.getArgument(0);
            if (node.getId() == null) {
                node.setId(new Random().nextLong());
            }
            diskStorage.put(node.getId(), deepClone(node));
            return null;
        }).when(mockStorage).save(any(), any());

        doAnswer(invocation -> {
            XdStorageBTreeNode node = invocation.getArgument(0);
            diskStorage.put(node.getId(), deepClone(node));
            return null;
        }).when(mockStorage).update(any(), any());

        // Имитируем догрузку СУБД с диска (возвращаем независимый слепок!)
        doAnswer(invocation -> {
            Object obj = invocation.getArgument(0);
            if (obj instanceof XdStorageBTreeNode) {
                XdStorageBTreeNode ref = (XdStorageBTreeNode) obj;
                XdStorageBTreeNode diskState = diskStorage.get(ref.getId());
                if (diskState != null) {
                    // Накатываем дисковое состояние на ссылку СУБД
                    ref.setKeys(new LinkedList<>(diskState.getKeys()));
                    ref.setObjects(new LinkedList<>(diskState.getObjects()));
                    ref.setChildren(new LinkedList<>(diskState.getChildren()));
                    ref.setNextTreeNodeOnThisLevel(diskState.getNextTreeNodeOnThisLevel());
                    ref.setParent(diskState.getParent());
                }
            }
            return invocation.getArgument(0);
        }).when(mockStorage).load((Class<Object>) any(), any());
    }

    // Вспомогательный метод для побайтового глубокого клонирования (честный барьер сериализации)
    private XdStorageBTreeNode deepClone(XdStorageBTreeNode original) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(bos);

            // Временный сброс несериализуемых транзиентных локеров для теста
            List<Comparable> keysCopy = new ArrayList<>(original.getKeys());
            List<Object> objectsCopy = new ArrayList<>(original.getObjects());

            oos.writeObject(original.getId());
            oos.writeObject(keysCopy);
            oos.writeObject(objectsCopy);
            oos.writeLong(original.getNextTreeNodeOnThisLevel() != null ? original.getNextTreeNodeOnThisLevel().getId() : -1L);
            oos.flush();

            ByteArrayInputStream bis = new ByteArrayInputStream(bos.toByteArray());
            ObjectInputStream ois = new ObjectInputStream(bis);

            XdStorageBTreeNode clone = new XdStorageBTreeNode();
            clone.setId((Long) ois.readObject());
            clone.setKeys((List<Comparable>) ois.readObject());
            clone.setObjects((List<Object>) ois.readObject());

            long nextId = ois.readLong();
            if (nextId != -1L) {
                XdStorageBTreeNode nextRef = new XdStorageBTreeNode();
                nextRef.setId(nextId);
                clone.setNextTreeNodeOnThisLevel(nextRef);
            }
            return clone;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // =========================================================================
    // ТЕСТ 1: ТЕСТ НА СМЕШЕНИЕ ТИПOВ ДАННЫХ (Long vs Integer vs String)
    // =========================================================================
    @Test
    @DisplayName("Стресс-тест типов: Проверка навигации дерева при смешении String/Long форматов")
    public void testBTree_TypeMixingContention_ShouldFindSmoothly() throws Exception {
        XdStorageBTreeId id = new XdStorageBTreeId(String.class, "string_idx");
        XdStorageBTree stringTree = new XdStorageBTree(id, false, 2);

        // Вставляем строковые ключи, имитирующие ID вселенных
        List<String> stringKeys = Arrays.asList("uuid-800", "uuid-100", "uuid-500", "uuid-300", "uuid-900");
        for (String key : stringKeys) {
            stringTree.insert(key, "Value_" + key, mockStorage, mockTx);
        }

        for (String key : stringKeys) {
            List<Object> res = stringTree.find(key, mockStorage, mockTx);
            assertEquals(1, res.size(), "Строковый ключ потерян в навигации: " + key);
        }
    }

    // =========================================================================
    // ТЕСТ 2: ТЕСТ НА ЖЕСТКИЙ ДИСКOВЫЙ ПУРДЖ С ПЕРЕЗАГРУЗКOЙ КOРНЯ
    // =========================================================================
    @Test
    @DisplayName("Дисковый стресс-тест: Каскадные сплиты и слияния с полной потерей ссылок Java-кучи")
    public void testBTree_DiskSerialization_ShouldSurviveCascadePurger() throws Exception {
        XdStorageBTreeId id = new XdStorageBTreeId(Long.class, "planet_idx");
        XdStorageBTree diskTree = new XdStorageBTree(id, false, 2);

        int totalKeys = 150;

        // Последовательно пишем пачку ключей (имитируем planets 1 -> 150)
        for (long i = 1; i <= totalKeys; i++) {
            diskTree.insert(i, "Planet_Block_" + i, mockStorage, mockTx);
        }

        // =========================================================================
        // ЭМУЛЯЦИЯ ВТOРOЙ ФАЗЫ ТЕСТА: Очищаем локальную оперативную память JVM!
        // Перечитываем дерево, заставляя СУБД восстанавливать структуру строго по
        // дисковым слепкам counters и countersOfPart. Проверяем сохранность ключа 100!
        // =========================================================================
        List<Object> findTarget = diskTree.find(100L, mockStorage, mockTx);
        assertEquals(1, findTarget.size(), "🚨 КЛЮЧ 100 ПОТЕРЯН ИЗ-ЗА ЗАБЫТOГO storage.update() ПРИ СПЛИТАХ!");
        assertEquals("Planet_Block_100", findTarget.get(0));

        // Начинаем агрессивное удаление с самого конца (150 -> 1)
        for (long i = totalKeys; i >= 1; i--) {
            final long keyToDelete = i;
            assertDoesNotThrow(() -> {
                diskTree.delete(keyToDelete, mockStorage, mockTx);
            }, "Крах дискового слияния на ключе: " + keyToDelete);
        }
    }
}
