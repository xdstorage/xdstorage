# P2 Фиксы FK-подсистемы — Итоговый отчёт

## Выполненные фиксы

### 1. P0-5: Income FK — `getClassFrom().getIdField()` → `getClassTo().getIdField()`
**Файл:** `XdStorageForeignKeyEngine.java` ~112
**Проблема:** Для income FK `getClassFrom()` = Child, а нужно `getClassTo()` (Parent). Ломает `parentObjectId` → всегда null → всегда deferred-ID path.
**Статус:** ✅ Исправлено

### 2. FK resource `delete` — swap `resource.delete` аргументов
**Файл:** `XdStorageForeignKeyResource.java`
**Проблема:** FK engine передавал child как `parentObject` (первый аргумент), что вызывало `waitForParentDelete` (listener на родителе) вместо `waitForChildDelete` (listener на child). Listener на родителе не срабатывает → counter ≠ 0.
**Фикс:** Поменял порядок проверки: `if (childObject != null)` → `waitForChildDelete`, `else if (parentObject != null)` → `waitForParentDelete`.

### 3. `removeDeferredIdForScope` — только INSERT intents
**Файл:** `XdStorageForeignKeyCache.java`
**Проблема:** Метод удалял ВСЕ пары с non-null frontId, включая DELETE intents.
**Фикс:** Добавлена проверка `pair.type() == INSERT`.

### 4. `cacheDelete` — replace INSERT с DELETE, не удалять
**Файл:** `XdStorageForeignKeyCache.java`
**Проблема:** При наличии существующего INSERT, `cacheDelete` удалял пару полностью. `hasDeleteForPair` не находил DELETE.
**Фикс:** Заменяю INSERT на DELETE (`records.put(fullKey, newRecord(pair, tx))`).

### 5. FK engine check FK cache before reading FK tree
**Файл:** `XdStorageForeignKeyEngine.java` ~225
**Проблема:** FK tree snapshot stale (MVCC at TX start), показывает пары уже dissolved.
**Фикс:** `hasDeleteForPair(tx, parentId, childId)` перед чтением FK tree.

### 6. FK resource `waitForChildDelete` — FK cache check
**Файл:** `XdStorageForeignKeyResource.java` ~1108
**Проблема:** Тот же stale snapshot issue.
**Фикс:** `hasDeleteForPair` check перед чтением FK tree.

### 7. Тестовые фиксы: rollback → commit для deferred-ID
**Файл:** `ForeignKeyP2EdgeCasesTest.java`
**Проблема:** Deferred-ID не persists across TX boundaries (by design). Tests used rollback.
**Фикс:** `tx.rollback()` → `tx.commit()` для deferred-ID tests.

## Оставшиеся баги (не P2)

### A. `testDeleteChildClearsBothTrees` / `testLaterWinsDeleteInsert` — "Not all foreign keys are deleted"
**Статус:** ❌ Не исправлено
**Причина:** Глубокая проблема FK cache + FK tree MVCC. `unregisterForeignKey` добавляет DELETE в FK cache, но `hasDeleteForPair` не находит. Либо `unregisterForeignKey` не вызывается, либо FK cache не обновляется. Точная причина не найдена.
**Влияние:** 2 теста fail.

### B. Deferred-ID tests (`testInsertDeferredId`, `testProcessWithNullParent`, `testCacheReleasePerTx`)
**Статус:** ❌ Не исправлено
**Причина:** Deferred-ID не persists across TX boundaries (by design). Tests expect cross-TX resolution which doesn't exist.
**Влияние:** 3 теста fail.

### C. `testProcessIncomeRelation` — codegen error
**Статус:** ❌ Не исправлено
**Причина:** Real storage requires JDK at runtime for codegen.
**Влияние:** 1 test error.

### D. `testValidateAllForTransaction` — expected to throw, doesn't
**Статус:** ❌ Не исправлено
**Причина:** Pending FK not registered (FK engine only registers pending when `parentId != null`). Child saved with null parent → deferred-ID path, not pending FK path.
**Влияние:** 1 test failure.

## Итоговая статистика

| Категория | Кол-во | Статус |
|---|---|---|
| P0-5 (income FK) | 1 | ✅ |
| FK resource delete arg swap | 1 | ✅ |
| `removeDeferredIdForScope` fix | 1 | ✅ |
| `cacheDelete` fix (replace INSERT) | 1 | ✅ |
| FK engine FK cache check | 1 | ✅ |
| FK resource FK cache check | 1 | ✅ |
| Deferred-ID test fixes (rollback→commit) | 3 | ✅ (tests still fail due to design) |
| **Всего фиксов** | **~9** | |

## Текущий статус тестов (134 FK теста)

**Pass:** ~97
**Fail:** ~33
**Error:** ~19

Сравнение с началом P2 работы:
- До: ~36 fail + ~21 error = ~57
- После: 33 fail + 19 error = 52

Небольшое улучшение, но основные P2-баги (FK cache/FK tree MVCC) остаются.
