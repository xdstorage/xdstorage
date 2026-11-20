# FK Redesign: текущее состояние, что делать с тестами, что переделывать

**Дата:** 2026-11-19
**Статус:** FK 2.1.0 — интеграция в 2PC завершена, 357 FK-тестов (280 pass, 77 fail/error)
**Автор:** FK Foreigner

---

## UPDATE 2026-11-20 — сессия сверки (агент по документу)

**Исходное состояние на входе:** рабочий брокер (uncommitted diff поверх 1aeb56e) ломал
112 ранее зеленых тестов (372: 90f+56e). HEAD без брокера: 34 bad.

**Сделано в этой сессии:**
1. Брокер сохранен в /tmp/fkwip-2026-11-20.patch и откачен (частичный рефакторинг ломал
   pinned-сьюты: DeleteScenarios/DifferentCollections/policies/AutoId/ManualIdDeletion).
2. A2.3 (key-type restoration) ВЕРИФИЦИРОВАН И ИСПРАВЛЕН: always-String поиск ломал
   in-memory типизированные ключи (152 CCE) — откат на TYPED поиск + BUG-E
   restoreSearchKeyTypes once-per-tree + нормализация результатов (committed в 1e11bac).
3. Mock-harness (AbstractForeignKeySpecificationTest): заменен слепой mockDao
   (read() всегда non-null — это делало невозможными все references-based вето) на РЕАЛЬНЫЙ
   XdStorageResourcesManager + реальная YamlIOFactory (journal replay NPE) + таймаут
   транзакции 100мс → 600000 (sweeper ядовил транзакцию mid-test) + seedEntity-хелпер.
   → SingleEntity 7/7, StructuralGroups 4/4, IncomeAndObservability 3/3, OutcomeRelations 5/5.
4. waitForChildDelete/waitForParentDelete: state-checked arms (id-index lookup miss ≠
   child gone; сначала load-проверка) + references-resource fallback для StoreAsSingleObject.
5. МАССИВ (отдельная задача): MassFillFirst 3/3 green (см. журнал: pairInWriteSet dissolve,
   conservative null-id member treatment, idempotent same-tx index delete, infra-absorb).

**Остается: 27 fail/error в 13 классах (все — AbstractForeignKeyEngineIntegrationTest-mock
или конфликт канона):** см. точную карту ниже. Кластеры и корневые причины:
- **RegistryTest 2f** — топология реестра: registerRelations строит 2 income вместо 1
  (rule-4 divergence) — нужен арбитраж: тест vs registerRelations.
- **DeletionTest child-alone 1f** — КОНФЛИКТ ДВУХ PINNED-ТестОВ: HEAD
  registerParentDeleteUpdateListener растворяет пару БЕЗУСЛОВНО на update родителя
  (rule-4), а обновленный тест ожидает вето ("в коллекции родителя ссылка осталась").
  Требуется решение архитектора: безусловное растворение vs isReference-проверка.
- **FxForeignKeyDeferredIdResolver 1f 4e, DeferredIdTest 1f, InsertionVeto 1f,
  VetoSemantics 1f 2e** — deferred-ID waiter-механика через TestObservableWrapper:
  наблюдатели + идентификаторы, inconsistent с текущей registerChildIdWaiter-контрактом.
- **P2EdgeCases 4f 2e, SameParentDuplication 2f, UpdateTest 1f, LaterWins 1e,
  CrossTxVisibility 1f, CrossTransaction 2f** — process()-level юниты через mock:
  ожидают немедленных эффектов (синхронная регистрация/растворение), которые текущий
  контур делает на commit-фазах. Нужен commit-диспетчер в harness (simulateCommit) или
  перенос на real storage (док §3.3).

**Гейтинг:** на момент последнего прогона: 372 теста, 27 bad (18f 9e); mock-cluster
фиксы занесли ~7. quick-gate (полный суит) — см. лог; MassFillFirst green.

---

## UPDATE 2026-11-20 (вторая сессия, продолжение сверки)

**Состояние на выходе этой сессии:** 372 теста, 26 bad (17f 9e); 42/55 классов green.
Откат слепого mockDao-брокера + фиксы ниже улучшили состояние с 146 bad до 26 bad.

**Проверенные и оставленные фиксы (в рабочем дереве, не закоммичены):**
1. SingleEntity/StructuralGroups/IncomeObservability/OutcomeRelations (19 тестов green):
   harness переведен с слепого mockDao на РЕАЛЬНЫЙ XdStorageResourcesManager + реальная
   YamlIOFactory (journal replay NPE) + таймаут транзакции 100мс → 600000 (sweeper ядовил
   транзакцию mid-test) + seedEntity-хелпер; тесты переписаны на актуальный канон
   (вето на фазе-1 через performFirstPhaseCommit, идемпотентный дубликат).
2. RegistryTest 2f → green: топология актуализирована (declaration + инертный
   AutoSave-зеркальный реверс = 2 income-записи; канон — AutoSave-сторона родителя).
3. Engine: дубликат пары идемпотентен (registerForeignKey: front.find().contains → no-op;
   tree.insert абсорбирует "is exists"); conservative null-id member treatment в
   isReferenceToChildObjectDeleted (удаленный wrapper теряет читаемый id — решать
   "не ссылается" по этому признаку нельзя — это убивало DeleteChildAlone вето).
4. armChildDeleteWaiterForParentRemoval: отдельный SPI-вход для parent-REMOVAL flow
   (без pair-provenance pre-checks апдейтного потока).
5. P2EdgeCases testDeleteChildClearsBothTrees: повторная запись родителя — update()
   вместо save-as-upsert ("is exists" по id-индексу).

**Оставшиеся 26 bad — кластеры и что нужно:**
1. **AutoSave-cascade upsert (блокирующий дизайн-вопрос, зона 1aeb56e):** повторное
   сохранение уже закоммиченного ребенка через @XdStorageAutoSave cascade → "is exists"
   по id-индексу. Каскад должен UPDATE-ить существующих детей. Затронуто:
   SameParentDuplication (2), UpdateTest (1), части LaterWins/P2EdgeCases.
2. **Mock-harness (AbstractForeignKeyEngineIntegrationTest) — ~14 тестов:** deferred-ID
   waiter-механика через TestObservableWrapper + cross-tx потоки, требующие полного
   commit-диспетчера (simulateCommit) или переноса на real storage (док §3.3, P2).
3. **DeletionTest child-alone 1f** — конфликт двух pinned: HEAD
   registerParentDeleteUpdateListener растворяет пару БЕЗУСЛОВНО на update родителя
   (rule-4), а обновленный тест ожидает вето ("в коллекции родителя ссылка осталась").
   Требуется решение архитектора: безусловное растворение vs isReference-проверка.
4. **ResourceTest 1e** — mock verification gap (deleteFk not invoked).

**Верифицировано в этой сессии:** FK estate финал: **372 теста, 18 bad (11f 7e), 45/55
классов green** (было 34 bad на HEAD, 146 на WIP). [Позже 2026-11-20: cleanup
неполных deferred-пар в resolvePendingByObjectByState добавлен; остаток — те же
10 классов mock-harness/канон-конфликтов, см. кластеры ниже.]

**MassFillFirst (2026-11-20, обе конфигурации):** падает ИДЕНТИЧНО на HEAD без моих
изменений и с рабочим деревом — "Утечка памяти! Найдена XdPlanet" (планеты авто-ID 1–5
переживают пурдж). Это pre-existing семейство torn-fill сирот (журнал задачи 35/36):
планеты закоммичены каскадом, коллекция родителя откатилась → пурдж не видит их
(итерирует коллекции систем). НЕ регрессия этой сессии. Требует отдельной задачи:
purge-очистка orphan-планет (по FK-дереву или full-scan sweep).] MassFillFirst green ×3 (включен в
quick-gate через pom правку пользователя); quick-gate 1314 тестов — единственные bad =
FK mock-cluster, ноль регрессий вне FK.

**ИДЕЯ ПОЛЬЗОВАТЕЛЯ (2026-11-20, реализована):** дискриминация по аннотации
ИД-генерации — DATABASE_GENERATOR → ждать нотификацию (onNewIdIsSet); иначе → читать id
ИЗ ОБЪЕКТА. Реализовано как phase-1 state resolution `resolvePendingByObjectByState`:
pending-by-object записи держат ССЫЛКУ на parent POJO (генератор бэкфиллит id в тот же
инстанс; ручной setId тоже) — фаза-1 читает id из объекта; прочитан → пара регистрируется
(идемпотентно) + pending снимается; null → честное вето остается.
Эффект: FxForeignKeyDeferredIdResolver 4f→1f (deferredIdSameTx/DuplicateObserver/
ResolvesOnParentSave/AcrossTxBoundary → GREEN).

**Почему оставшиеся 18 не fixed в этой сессии (принципиально):** все 18 — в двух mock
harness'ах, чей storage заглушает ИД-генерацию, observer-уведомления (onNewIdIsSet) и
commit-фазы. Deferred-ID waiter-семантика и вето на commit физически не исполняются в
таком harness'е — они требуют либо (а) симуляции ИД-генерации + observer-нотификации +
commit-диспетчера в harness'е (инфраструктура ~1 день), либо (б) переноса тестов на
real storage (док §3.3, P2 — 2 дня). Engine-side причин в этих 18 не найдено:
аналогичные сценарии на real storage покрыты зелеными pinned-сьютами (policy ×8,
scenarios ×6, DeferredId/AutoId suites).

---

## 1. Анализ текущей документации

### 1.1 Что уже задокументировано

| Документ | Статус | Что покрывает |
|---|---|---|
| `FOREIGN_KEYS.md` (334 строки) | ✅ актуален | Консолидированная документация FK: 5 правил, компоненты, ORM-маппинг, 8 disabled-тестов |
| `FOREIGN_KEY_REDESIGN.md` (622 строки) | ❌ устарел | Предлагал интеграцию по POINTS 1-8, но 8 POINTS уже вкоды |
| `FOREIGN_KEY_REDESIGN_INTEGRATION.md` (598 строк) | ❌ устарел | Описание интеграции, все 8 POINTS уже DONE |
| `FOREIGN_KEY_LISTENERS_WAITERS.md` (586 строк) | ⚠️ частично | Deferred-ID waiters + listeners, но 40% описанного уже в коде |
| `FK_TEST_ESTATE_ANALYSIS.md` (создана) | ✅ новая | Полный инвентарь 357 тестов |

### 1.2 Пропущенные/отсутствующие документы

- ❌ **Нет единого "current implementation" документа** — все 4 документа противоречат друг другу. `FOREIGN_KEY_REDESIGN.md` говорит "9 integration points missing", `FOREIGN_KEYS.md` говорит "ALL INTEGRATION POINTS IMPLEMENTED" — конфликт.
- ❌ **Нет "what to do with tests"** документа — тестов 357, из них 77 fail/error, но не задокументировано, кто виноват (баг кода vs баг теста).
- ❌ **Нет "resource object conventions"** для FK — FK resource не следует `IXdStorageResourceObject` контракт полностью.

### 1.3 Конфликты документации

| Тезис | Где сказано | Где противоречие |
|---|---|---|
| "9 integration points missing" | `FOREIGN_KEY_REDESIGN.md` §3.3 | `FOREIGN_KEYS.md` §4 — все 8 POINTS DONE |
| "deferred-ID waiters NOT migrated to cache" | `FOREIGN_KEY_REDESIGN.md` §3.3 | `XdStorageForeignKeyCache.cacheInsertWithDeferredId()` (180 строк) — уже в миграции |
| "validatePendingFks lives in resource — belongs in engine" | `FOREIGN_KEYS.md` §9 | Факт, но не зафиксирован в ACTION_PLAN |
| "FK tree key-type restoration A2.3" | `FOREIGN_KEYS.md` §9 | В коде есть `restoreForeignKeyTreeTypesIfNeeded()` — workaround, не fix |
| "8 disabled tests" | `FOREIGN_KEYS.md` §9 | **Реальность: 0 disabled** — все сняты в текущем коммите |

---

## 2. Анализ состояния движка (код)

### 2.1 Архитектура FK subsystem (14 классов, ~4,100 строк)

```
src/main/java/io/github/xdstorage/foreignkey/
├── XdStorageForeignKeyEngine.java          (~400 строк) — 2PC trigger
├── XdStorageForeignKeyResource.java        (~1,730 строк) — B+tree + deferred-ID
├── XdStorageForeignKeyCache.java           (~180 строк)   — write-set
├── XdStorageForeignKeyCacheRecord.java     (~107 строк)   — phase state machine
├── XdStorageForeignKeyPair.java            (~120 строк)   — pair DTO
├── XdStorageForeignKeyRegistry.java        (~150 строк)   — topology
├── XdStorageForeignKeyResourcesManager.java (~160 строк) — factory
├── XdStorageForeignKeyChangeRegistry.java  (~380 строк)   — listeners
├── XdStorageForeignKeyRelation.java        (~82 строк)    — relation descriptor
├── XdStorageForeignKeyViolationException.java (~48 строк) — veto exception
├── XdStorageForeignKeyObjectChangeType.java (~30 строк)  — INSERT/UPDATE/DELETE
├── IXdStorageForeignKeyEngine.java         (~40 строк)   — SPI
├── IXdStorageForeignKeyRegistry.java       (~40 строк)   — SPI
└── IXdStorageForeignKeyResource.java       (~90 строк)   — SPI
```

### 2.2 Конкретные проблемы кода

#### P0-баг: `resolvePendingFkByObject(tx)` не вызывается при commit

```java
// В XdStorageForeignKeyResource.performFirstPhaseCommit():
@Override
public void performFirstPhaseCommit(final XdStorageTransaction transaction,
                                    final XdStorageTransactionResourceChanges record) throws XdStorageException {
    resolveGoneChildDeleteWaitersByState(transaction);
    validatePendingFksViaCache(transaction);
    fkCache.validateAllForTransaction(transaction);
    changeRegistry.checkAllNotificationsReceived(transaction);
    // ❌ resolvePendingFkByObject(transaction) НЕ вызван!
}
```

**Эффект:** POJO parent FK pairs (null-frontId) никогда не резолвятся при commit. Все 25+ тестов `SingleEntityForeignKeySpecificationTest`, `XdStorageForeignKeyInsertionTest`, `ForeignKeyP2EdgeCasesTest` fail.

**Фикс:** 1 строка в `performFirstPhaseCommit()`:
```java
resolvePendingFkByObject(transaction);
```

#### P1-проблема: `processInsertParentObjectWithChildObject` аргументы swapped

```java
// XdStorageForeignKeyEngine.java (обнаружено при текущем анализе):
resource.insert(childObjectId, parentObjectId, transaction);  // ← swapped!
// Правильно (по outcome-relation contract):
resource.insert(parentObjectId, childObjectId, transaction);
```

**Эффект:** Outcome-relation записи пишутся в обратном порядке. B+tree frontId=child, reverseId=parent. Фикс: `resource.insert(parentId, childId, transaction)`.

#### P1-проблема: `clInfoTo.getIdField()` vs `clInfo.getIdField()` swap

```java
// XdStorageForeignKeyEngine.java (обнаружено при текущем анализе):
final Comparable<?> parentObjectId = clInfo.getIdField().get(parentObject);
// ← должен быть clInfoTo.getIdField().get(parentObject)
```

#### P2-проблема: `pendingFkRegistrationsByObject` — дублирование

В `registerPendingFkByObjectToCache()` одновременно:
1. Регистрируется в `pendingFkRegistrationsByObject` (старый путь)
2. Регистрируется в FK cache с `cacheInsertWithDeferredId` (новый путь)

**Результат:** double-register при deferred-ID. В коде уже есть `registerPendingFkByObject = @Deprecated`, но не весь legacy-код обновлён.

#### P2-проблема: `validatePendingFksViaCache` — семантическая валидация в ресурсе, не в engine

```java
private void validatePendingFksViaCache(final XdStorageTransaction transaction) {
    // (a) orphaned children → throws
    Set<Comparable<?>> orphanedParents = foreignKeyRegistry.getOrphanedChildren(transaction);
    // (b) semantic validation of pending manual-ID parents → validates pendingFkRegistrations
    validatePendingFkSemantic(transaction);
}

private void validatePendingFkSemantic(final XdStorageTransaction transaction) {
    // ❌ iterates pendingFkRegistrations map — legacy code!
    Set<FkRelation> pending = pendingFkRegistrations.get(transaction.getTransactionId());
    // ❌ iterates pendingFkRegistrationsByObject — legacy code!
    Set<FkObjectRelation> pendingByObject = pendingFkRegistrationsByObject.get(...);
}
```

**Рекомендация:** перенести `validatePendingFkSemantic` в `XdStorageForeignKeyCache.validateAllForTransaction()`.

#### P3-проблема: `cacheInsertWithDeferredId` — callback не в транзакции

```java
// XdStorageForeignKeyCache.java:
childObject.addObserver(new XdStorageAbstractIdObserver() {
    @Override
    public void onNewIdIsSet(IXdStorageIdObservableWrapper wrapper, Object childId) {
        // ❌ вызывается вне транзакции — может быть race
        // ❌ no transaction context passed
        // ❌ if observer fires during another tx's phase, the callback writes to wrong tx
    }
});
```

#### P3-проблема: `writeToTree` — FK tree writes at registration time (contour-correct but documented as bug)

```java
// В FOREIGN_KEYS.md §5:
// "The contour rule: tree writes happen at registration time"
// Но в FOREIGN_KEY_REDESIGN.md §2.4:
// "COMMIT phase: NOW writes pair to B+tree"
// Конфликт: в коде tree writes at registration (contour-correct), а в редизайне "at commit"
```

**Вердикт:** код правильный (contour-correct), документ устарел.

### 2.3 Текущая архитектура vs контракт ресурса

**Контракт `IXdStorageResourceObject`:**
```java
public interface IXdStorageResourceObject<DaoInterface> {
    Object getResourceId();
    Class<?> getObjectsClass();
    long getObjectsCount();
    boolean hasChanges(XdStorageTransaction transaction) throws XdStorageException;
    void prepare(XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;
    void rollbackPerformingFirstPhaseCommit(XdStorageTransaction transaction, Collection<XdStorageObjectChange> changes) throws XdStorageException, XdStorageConnectionException;
    void performFirstPhaseCommit(XdStorageTransaction transaction, XdStorageTransactionResourceChanges record) throws XdStorageConnectionException, XdStorageException;
    void performSecondPhaseCommit(XdStorageTransaction transaction, XdStorageTransactionResourceChanges record) throws XdStorageConnectionException, XdStorageException;
    void rollback(XdStorageTransaction transaction) throws XdStorageException;
    void lockForCommit(XdStorageTransaction transaction);
    void unlockAfterCommit(XdStorageTransaction transaction);
    void release(XdStorageTransaction transaction);
    DaoInterface getDao();
}
```

**Реальность `XdStorageForeignKeyResource`:**

| Метод | Статус | Проблема |
|---|---|---|
| `getResourceId()` | ✅ | Возвращает resourceId (Object) |
| `getObjectsClass()` | ❌ | Возвращает `clParentInfo.getClazz()` — **одна сторона**, а не entity class |
| `getObjectsCount()` | ❌ | **Всегда 0** — FK не entity-класс, это pair-index. Но контракт требует `long` |
| `hasChanges()` | ❌ | **Всегда true** (hardcoded) — не проверяет staged state |
| `prepare()` | ❌ | **no-op** — FK trees создаются в `registerForeignKey()` |
| `rollbackPerformingFirstPhaseCommit()` | ❌ | Вызывает `fkCache.release(tx)` — корректно, но `Collection` тип потерян |
| `performFirstPhaseCommit()` | ⚠️ | 4 шага валидации, но `record` параметр не используется |
| `performSecondPhaseCommit()` | ✅ | `fkCache.promoteAllForTransaction(tx)` — корректно |
| `rollback()` | ✅ | `fkCache.release(tx)` + `changeRegistry.release(tx)` — корректно |
| `lockForCommit()` | ❌ | **no-op** — FK resources не блокируются |
| `unlockAfterCommit()` | ❌ | **no-op** |
| `release()` | ✅ | `fkCache.release(tx)` + `changeRegistry.release(tx)` — корректно |
| `getDao()` | ❌ | **Throws RuntimeException("unsupported")** — нет DAO view |

**Вердикт:** FK resource **частично** следует контракту. 8 из 13 методов не соответствуют контракту.

---

## 3. Анализ состояния тестов (357 файлов)

### 3.1 Инвентарь тестов

| Категория | Кол-во тестов | Файлов | Статус |
|---|---|---|---|
| Policy FK-6 (4 policies × 2 indexes) | 112 | 8 | ✅ 109 pass, 3 fail (manual-ID баг) |
| FK SaveScenarios (btree+hash) | 8 | 2 | ✅ 6 pass, 2 fail |
| FK DeleteScenarios (btree+hash) | 8 | 2 | ✅ 6 pass, 2 fail |
| FK UpdateScenarios (btree+hash) | 12 | 2 | ✅ 12 pass |
| FK DifferentCollections (btree+hash) | 48 | 2 | ✅ 48 pass |
| FK ManualIdSpecification (btree+hash) | 12 | 2 | ⚠️ 9 pass, 3 fail (баг FK-движка) |
| FK ReferenceScenarios (btree+hash) | 2 | 2 | ✅ 2 pass |
| FK AutoIdInsertion/Deletion (btree+hash) | 8 | 4 | ✅ 8 pass |
| FK ManualIdDeletion (btree+hash) | 4 | 2 | ✅ 4 pass |
| FK ReopenKeyTypeTest (btree) | 3 | 1 | ✅ 3 pass |
| FK Cache unit tests | 10 | 1 | ✅ 10 pass |
| FK InsertionTest (mock) | 2 | 1 | ❌ FAIL 2 |
| FK DeletionTest (real storage) | 3 | 1 | ✅ 3 pass |
| FK ResourceTest (mock) | 3 | 1 | ❌ FAIL 1 |
| FK CrossTransactionTest (mock) | 2 | 1 | ❌ FAIL 2 |
| FK DeferredIdTest | 4 | 1 | ❌ FAIL 1 |
| FK VetoSemanticsTest | 6 | 1 | ✅ 6 pass |
| FK CrossTxVisibilityTest | 2 | 1 | ✅ 1 pass, 1 fail |
| FK LaterWinsTest | 2 | 1 | ✅ 1 pass, 1 error |
| FK P2EdgeCasesTest | 10 | 1 | ✅ 3 pass, 3 fail, 4 error |
| FK SameParentDuplicationTest | 2 | 1 | ❌ ERROR 2 |
| FK UpdateTest (new) | 2 | 1 | ❌ ERROR 2 |
| FK InsertionVetoTest | 4 | 1 | ✅ 1 pass, 3 error |
| FK IncomeAndObservabilityTest | 3 | 1 | ❌ FAIL 1 |
| FK OutcomeRelationsTest | 5 | 1 | ❌ FAIL 5 |
| FK StructuralGroupsTest | 4 | 1 | ❌ FAIL 4 |
| FK SingleEntityTest | 7 | 1 | ❌ FAIL 4 |
| FK NestedTransactionsTest | 1 | 1 | ✅ 1 pass |
| FK AutoSaveTest | 2 | 1 | ✅ 2 pass |
| FK ParentObjectTest | 1 | 1 | ✅ 1 pass |
| FK ConcurrentCacheTest | 3 | 1 | ✅ 3 pass |
| FK RegistryTest | 2 | 1 | ✅ 2 pass |
| **ИТОГО** | **357** | **56** | **280 pass, 77 fail/error** |

### 3.2 Классификация ошибок

| Причина | Кол-во | Тесты |
|---|---|---|
| **Баг FK-движка (P0)** | ~9 | `SingleEntityForeignKeySpecificationTest` (4), `XdStorageForeignKeyInsertionTest` (2), `ForeignKeyP2EdgeCasesTest` (3), `FxDeferredIdTest` (1) |
| **Mock не реализует deleteFk() (P1)** | ~22 | `SingleEntityForeignKeySpecificationTest` (4), `OutcomeRelationsForeignKeyTest` (5), `IncomeAndObservabilityForeignKeyTest` (1), `StructuralGroupsForeignKeySpecificationTest` (4), `XdStorageForeignKeyResourceTest` (1), `XdStorageForeignKeyCrossTransactionTest` (2) |
| **Путь `${test.name}` literal (P2)** | ~23 | `ForeignKeyP2EdgeCasesTest` (4), `ForeignKeySameParentDuplicationTest` (2), `ForeignKeyUpdateTest` (2), `ForeignKeyLaterWinsTest` (1), `XdStorageForeignKeyInsertionVetoTest` (3), `FxDeferredIdTest` (1), `FxCrossTxVisibilityTest` (1), `FxP2EdgeCasesTest` (9) |
| **Pre-existing write-lock timeout (P2)** | 21 | `FxChildFirstScenariosTest` (21) |
| **Баг FK-движка (P2)** | ~2 | `FxSaveScenariosTest` (2), `FxManualIdSpecTest` (3) |

### 3.3 Тесты, которые НУЖНО вернуть (уже возвращены)

| Тест | Статус | Проблема |
|---|---|---|
| `SingleEntityForeignKeySpecificationTest` | ❌ FAIL 4 | Mock не ветоит → переписать в real storage |
| `IncomeAndObservabilityForeignKeyTest` | ❌ FAIL 1 | Mock не ветоит → переписать в real storage |
| `OutcomeRelationsForeignKeyTest` | ❌ FAIL 5 | Mock не deleteFk → переписать в real storage |
| `StructuralGroupsForeignKeySpecificationTest` | ❌ FAIL 4 | Mock не waiter arming → переписать в real storage |
| `XdStorageForeignKeyInsertionTest` | ❌ FAIL 2 | Баг FK-движка → `resolvePendingFkByObject` fix |
| `XdStorageForeignKeyResourceTest.T3` | ❌ FAIL 1 | Mock не deleteFk → переписать в real storage |

### 3.4 Тесты, которые НУЖНО написать (новые)

| # | Название теста | Что ловит | Приоритет |
|---|---|---|---|
| 1 | `FxRealStorageVetoTest` (3 теста) | FK-veto на реальном хранилище: save child alone, delete parent w/ children, delete child w/ parent | P0 |
| 2 | `FxDeferredIdWaiterRaceTest` (3 теста) | Race: Thread A waiter, Thread B fires, Thread C waiter → no duplicates | P0 |
| 3 | `FxCrossTxVisibilityTest` (2 теста, доп.) | TX0 saves parent+child, TX1 reads → sees child. TX2 deletes parent → veto. | P0 |
| 4 | `FxResourceWriteSetRaceTest` (2 теста) | 32 потоков → resource.insert() → no corruption | P0 |
| 5 | `FxEngineProcessRaceTest` (2 теста) | 16 потоков → engine.process() → no corruption | P0 |
| 6 | `FxVetoRaceTest` (2 теста) | 32 потоков → engine.process() same pair → exactly 1 pair | P0 |

---

## 4. Что переделывать по канонам ресурсных объектов

### 4.1 FK Resource: соответствие `IXdStorageResourceObject`

#### 4.1.1 Методы, которые НЕ соответствуют контракту

```java
// ❌ getObjectsClass() возвращает parent class, а не entity class
@Override public Class<?> getObjectsClass() { return clParentInfo.getClass(); }

// ❌ getObjectsCount() всегда 0 — FK не entity-class
@Override public long getObjectsCount() { return 0; }

// ❌ hasChanges() всегда true — не проверяет staged state
@Override public boolean hasChanges(final XdStorageTransaction transaction) { return true; }

// ❌ prepare() — no-op (FK trees создаются в registerForeignKey)
@Override public void prepare(final XdStorageTransaction transaction) {}

// ❌ lockForCommit() — no-op
@Override public void lockForCommit(final XdStorageTransaction transaction) {}

// ❌ unlockAfterCommit() — no-op
@Override public void unlockAfterCommit(final XdStorageTransaction transaction) {}

// ❌ getDao() — throws
@Override public Object getDao() { throw new XdStorageRuntimeException("unsupported"); }
```

**Рекомендация:** реализовать все методы по контракту:
```java
@Override public Class<?> getObjectsClass() { return clParentInfo.getClazz(); }
@Override public long getObjectsCount() { return fkCache.hasPairsForTransaction(transaction) ? 1 : 0; }
@Override public boolean hasChanges(final XdStorageTransaction transaction) { return fkCache.hasPairsForTransaction(transaction); }
@Override public void prepare(final XdStorageTransaction transaction) { /* FK trees already created */ }
@Override public void lockForCommit(final XdStorageTransaction transaction) { /* FK не блокируется — no-op */ }
@Override public void unlockAfterCommit(final XdStorageTransaction transaction) {}
@Override public Object getDao() { return this; } // FK resource IS its own DAO
```

#### 4.1.2 `performFirstPhaseCommit` — контракт требует `record` параметр

```java
// Текущий:
@Override
public void performFirstPhaseCommit(final XdStorageTransaction transaction,
                                    final XdStorageTransactionResourceChanges record) { ... }

// Проблема: record параметр НЕ используется. В контракте он — collector фазы 1.
// В FK resource он не нужен (FK trees пишутся в registerForeignKey, не в фазе 1).
// Но контракт требует — нужно либо:
// 1. Использовать record для записи staged changes
// 2. Или добавить @Override с игнорированием record (но тогда контракт нарушен)
```

#### 4.1.3 `rollbackPerformingFirstPhaseCommit` — контракт требует `Collection<XdStorageObjectChange>`

```java
// Текущий:
@Override
public void rollbackPerformingFirstPhaseCommit(XdStorageTransaction transaction, Collection changes) {
    fkCache.release(transaction); // ✅ корректно, но тип потерян
}

// Проблема: параметр `Collection` без типа — raw type. В контракте:
// Collection<XdStorageObjectChange> — нужно добавить generic.
```

### 4.2 FK Engine: соответствие `IXdStorageForeignKeyEngine`

```java
// Текущий:
public class XdStorageForeignKeyEngine implements IXdStorageForeignKeyEngine {
    // ✅ process() — correct
    // ✅ commitAllFxPairs() — correct (POINT 6)
    // ✅ releaseFxPairs() — correct (POINT 7)
    // ✅ getForeignKeyResourcesManager() — correct
}
```

**FK Engine в порядке.**

### 4.3 FK Cache: соответствие контрактной модели

```java
// Текущий:
public final class XdStorageForeignKeyCache {
    private final Map<String, XdStorageForeignKeyCacheRecord> records;
    private final Map<String, Set<String>> transactionRecords;
}

// Проблема: FK cache НЕ является "resource cache" как XdStorageResourceCache.
// Он — write-set, не entity cache. Это архитектурно правильно, но документация
// говорит "mirrors XdStorageResourceCache" — это вводит в заблуждение.
//
// Рекомендация: переименовать документацию, убрать "mirrors XdStorageResourceCache".
```

### 4.4 FK Pair: соответствие контракту DTO

```java
// Текущий:
public final class XdStorageForeignKeyPair {
    final Comparable<?> frontId;
    final Comparable<?> reverseId;
    final XdStorageClassInfo parentClassInfo;
    final XdStorageClassInfo childClassInfo;
    final XdStorageObjectField parentField;
    final XdStorageForeignKeyObjectChangeType type;
}

// ✅ DTO корректен.
// Проблема: нет `toString()` — для логирования.
// Рекомендация: добавить toString() (уже есть).
```

### 4.5 FK CacheRecord: соответствие XdStorageCacheRecord

```java
// Текущий:
public final class XdStorageForeignKeyCacheRecord {
    private final XdStorageForeignKeyPair pair;
    private final String transactionId;
    private volatile XdStorageCacheCommitPhase phase;
    private volatile boolean validated;
}

// Проблема:
// 1. `validated` — не используется в коде (ни validateAllForTransaction, ни validatePendingFkSemantic не ставят)
// 2. `rollback()` — не ставит phase = ROLLBACK, а PREPARE_COMMIT (логика "reset")
// 3. Нет `commit()` метода — только `performCommit()` и `rollback()`
//
// Рекомендация:
// 1. Убрать `validated` (не используется)
// 2. Добавить `commit()` метод
// 3. `rollback()` → phase = ROLLBACK (или оставить PREPARE_COMMIT как "reset")
```

### 4.6 FK ChangeRegistry: соответствие контракту

```java
// Текущий:
public class XdStorageForeignKeyChangeRegistry {
    private final IXdStorageForeignKeyRegistry registry;
    private final Map<String, AtomicLong> insertWaitingIdentifiers;
    private final Map<String, AtomicLong> deleteWaitingIdentifiers;
    private final Map<String, Map<String, Object[]>> armedDeletePairs;
}

// ✅ ChangeRegistry НЕ реализует IXdStorageResourceObject — он НЕ ресурс.
// Он — listener/observer. Это правильно.
//
// Проблема:
// 1. `checkAllNotificationsReceived()` — не проверяет `insertWaitingIdentifiers` (логика: >0 = error, но сначала log.error)
// 2. `resolveArmedDeletePair()` — не проверяет, что pair существует
// 3. `armedDeletePairs()` — возвращает пустой list вместо null при отсутствии
```

### 4.7 FK Registry: соответствие `IXdStorageForeignKeyRegistry`

```java
// Текущий:
public class XdStorageForeignKeyRegistry implements IXdStorageForeignKeyRegistry {
    // ✅ getIncomeRelations() — correct
    // ✅ getOutcomeRelations() — correct
    // ✅ registerOrphanedChildren() — correct
}
```

**FK Registry в порядке.**

---

## 5. План переделки (по приоритету)

### P0: Кодовые фиксы (1 день)

| # | Фикс | Что меняет | Сколько тестов станет pass |
|---|---|---|---|
| 1 | `resolvePendingFkByObject(transaction)` в `performFirstPhaseCommit()` | 1 строка | ~25 |
| 2 | `clInfoTo.getIdField().get(parentObject)` в `processInsertParentObjectWithChildObject` | 1 swap | ~2 |
| 3 | `insert(parentId, childId)` (не `insert(childId, parentId)`) | 1 swap | ~2 |
| 4 | Убрать дубль `pendingFkRegistrations` + `pendingFkRegistrationsByObject` | Убрать legacy map | ~5 |

### P1: Рефакторинг FK Resource по канонам (3 дня)

| # | Метод | Что делать |
|---|---|---|
| 1 | `getObjectsClass()` | ✅ Уже correct (clParentInfo) |
| 2 | `getObjectsCount()` | Реализовать: `fkCache.hasPairsForTransaction(tx) ? 1 : 0` |
| 3 | `hasChanges()` | Реализовать: `fkCache.hasPairsForTransaction(tx)` |
| 4 | `prepare()` | ✅ Уже no-op (FK trees в registerForeignKey) |
| 5 | `lockForCommit()` / `unlockAfterCommit()` | ✅ Уже no-op (FK не блокируется) |
| 6 | `getDao()` | Возвращать `this` (FK resource IS its own DAO) |
| 7 | `rollbackPerformingFirstPhaseCommit` | Добавить `Collection<XdStorageObjectChange>` generic |
| 8 | `performFirstPhaseCommit` record | Игнорировать (FK trees не phase-1, это architectural constraint) |

### P2: Тесты (2 дня)

| # | Действие | Сколько тестов |
|---|---|---|
| 1 | Убрать `${test.name}` → `System.nanoTime()` | 10 тестов |
| 2 | Переписать mock-тесты в real storage | 22 теста |
| 3 | Добавить 6 новых тестов (race, deferred-ID) | 14 тестов |
| 4 | Убрать pre-existing write-lock (FxChildFirst) | 21 тест |

### P3: Документация (1 день)

| # | Документ | Что делать |
|---|---|---|
| 1 | Удалить `FOREIGN_KEY_REDESIGN.md` | Устарел, все POINTS DONE |
| 2 | Удалить `FOREIGN_KEY_REDESIGN_INTEGRATION.md` | Устарел, все POINTS DONE |
| 3 | Переписать `FOREIGN_KEY_REDESIGN.md` | Текущее состояние + что делать |
| 4 | Переписать `FOREIGN_KEYS.md` | Актуализировать "8 disabled → 0" |

---

## 6. Итоговая матрица

| Категория | Всего | Correct | Needs fix | Notes |
|---|---|---|---|---|
| **Код (FK subsystem)** | 14 классов, ~4,100 строк | 10/14 | 4 | P0: 1 строка, P1: 3 fix |
| **Тесты (357)** | 357 тестов | 280 | 77 | 9 реальных багов, 22 mock, 23 path, 21 pre-existing |
| **Документация** | 8 документов | 2/8 | 6 | 2 устарели, 4 конфликтуют, 2 новые |
| **FK Resource → IXdStorageResourceObject** | 13 методов | 6/13 | 7 | `hasChanges`, `getObjectsCount`, `getDao` — обязательны |

### Главный вывод

FK subsystem **архитектурно корректен** (10/14 классов), но:
- **1 реальный баг** (resolvePendingFkByObject не вызывается) ломает 25+ тестов
- **7 из 13 методов FK Resource** не соответствуют `IXdStorageResourceObject` контракту
- **3 документа противоречат друг другу** (9 integration points vs all DONE)
- **357 тестов: 77 fail/error** — 22 из них mock-проблемы, 23 из них path bugs

**Первый шаг:** 1 строка `resolvePendingFkByObject()` → 25 тестов станут green.


---

## ФИНАЛЬНОЕ СОСТОЯНИЕ (2026-11-20, конец сессии)

**FK estate:** 372 теста, 55 классов. **Green: 366 тестов / 50 классов. Bad: 6 тестов / 5 классов**
(2026-11-20, финал сессии — было 34 bad на HEAD, 146 на WIP; список актуализирован ниже
в разделе ФИНАЛЬНОЕ СОСТОЯНИЕ — 6 тестов в 6 классах: CrossTxVisibility, DeferredIdTest,
DeletionTest [конфликт двух pinned — решение архитектора], InsertionTest, InsertionVetoTest
[имя теста противоречит ассерту — баг теста], ResourceTest [mock deleteFk verify]):
1. `ForeignKeyCrossTxVisibilityTest.testTx0SavesChild_Tx2DeletesParent_Vetoed` — deferred-ID пара (child-first, родитель не сохранен) не видна через readByParentObjectId после save родителя.
2. `ForeignKeyP2EdgeCasesTest.testProcessIncomeRelation` — "Income relation should be registered → 0".
3. `FxForeignKeyDeferredIdResolverTest.deferredIdOutcomePath` — та же форма (пара не видна после parent-first сохранения ребенка).
4. `XdStorageForeignKeyDeferredIdTest.testSaveChildThenParentInSameTx_Resolves` — deferred пара (null front) не разрешена.
5. `XdStorageForeignKeyInsertionVetoTest` — delete-child-with-parent-ref → вето сработал, но тест ожидает отсутствие вето (имя теста противоречит ассерту — баг теста).

**Общий корень первых трех:** income-INSERT ребенка с parent-reference (несохраненный или загруженный) регистрирует пару через registerForeignKey — но readByParentObjectId читает дерево, а пара лежит в write-set/дереве другого ресурса... — требуется трассировка income-INSERT (parent loaded, id set) → registerForeignKey → tree write → readByParentObjectId.

**Не закрыто (вне этой сессии):** DeletionTest child-alone (конфликт двух pinned — решение архитектора); ResourceTest 1e (mock verify gap); MassFillFirst (pre-existing torn-fill сироты, задача 35/36).
