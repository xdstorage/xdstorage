---
title: "[2.1.0][TREEOVERLAY] B+tree per-tx overlay: звуковой откат + per-op release change-слота (W4-DESIGN, task 41 + конкурентная запись)"
labels: [2.1.0-train, P0, engine, w4-design, performance]
status: in-progress
---

## Summary

Перевести мутации B+tree (id-индексы + FK-деревья) с модели «in-place мутация +
change-слот записи держится до коммита» на модель **per-tx overlay**:

- мутации TX (`insert`/`delete` ключа) стейджатся в per-tx overlay дерева;
- физическое дерево не мутируется до коммита;
- чтения (`find`/`has`) сливают физический снимок с overlay пишущей TX (read-your-own-write);
- **rollback = discard overlay** — звук по построению (ничего обращать не надо;
  уроки двух проваленных компенсаций task 41 не задействуются);
- **commit = пакетное применение overlay** в phase-1 ресурса-владельца (внутри
  INDEX_RESOURCE_ORDER-окна) через публичный btree-путь с per-op undo
  (undo(insert k) = delete k; undo(delete k,v) = insert(k,v) — одиночные операции,
  не структурные трансформации).

## Почему (квантифицировано, FKBENCH Шаг 0 — docs/tasks/2.1.0/50)

Дефолтный tx-таймаут 3s, профиль MassFill, каждому воркеру своя вселенная
(конкуренция только на классовых B+tree-ресурсах):

```
fk=OFF t=1: 167.4 tx/s p50=5ms  →  fk=OFF t=10: 1.2 tx/s p50=12s  (коллапс ~140×)
fk=ON  t=1: 138.8 tx/s p50=7ms  →  fk=ON  t=10: 0.8 tx/s p50=18s1
```

Отказы называют файлы классовых **id-index** btree-ресурсов и в FK-off плече —
штопор сидит в ядре записи, FK-деревья — второй слой того же механизма.
Оверлей закрывает оба слоя. Ожидаемый эффект: конкурентная запись 1.2 → 100+
tx/s, p99 12-21s → ms; снятие Semaphore-обхода из MassFillSecond.

## Семантические сдвиги (зафиксированы)

1. Межтранзакционный дубликат id: обнаружение переезжает с op-time на commit-time
   (first-committer-wins на id, как unique-index в СУБД). Свой дубликат — по-прежнему
   op-time (has() читает overlay+физику).
2. Uncommitted-пары/ключи невидимы чужим TX до коммита (сегодня видны физически в
   дереве — фактически READ-UNCOMMITTED на уровне дерева; станет READ-COMMITTED).
3. Применение overlay — одна TX за раз (слот дерева берётся на время применения,
   не на длительность TX). Отказ применения: per-op undo в обратном порядке +
   честный veto (TX откатывается целиком).

## План

- [x] Фаза 0: study (btree surface, INDEX_RESOURCE_ORDER, find/has вызыватели)
- [x] Фаза 1: overlay в XdStorageBTree (stageInsert/stageDelete/overlay-merge find/has;
      later-wins стейджинг; isOverlayMode по имени id: "id_index" / "*_foreign_key")
- [x] Фаза 2: apply в commitInternal ДО drain'а очереди ресурсов (storage фиксируется
      при стейджинге; применение регистрирует infra-ресурсы через мутатор — только так
      они попадают в acquisition/фазы; файлы пишутся собственными phase-1 ресурсов).
      САМОДЕДЛОКlesson: applyOverlays НЕ должен брать tree write-latch (нереентерантен,
      doInsert/doDelete берут его сами). Rollback-hook: rollbackInternal → discardAll.
- [x] Фаза 2 верификация: FK estate 372/0f/0e (было 7 bad после первого включения —
      1) Recursive update: overlays.put внутри computeIfAbsent той же CHM — убран;
      2) merge later-wins: stageDelete не вычищал stagedInserts — исправлен;
      3) apply был в phase-1 FK/индексных ресурсов = ПОСЛЕ фаз infra-ресурса
      ("Illegal state of record" — proven повторно) — перенесён в commitInternal;
      4) storage.load дерева в фазе-1 = 3s invisibility-столлы — убраны (реестр инстансов)).
- [ ] Фаза 3: ревив XdStorageTreeStructureRollbackTest (RED→GREEN)
- [ ] Фаза 4: FKBENCH повтор (цель: t=10 ≥ 100 tx/s, p99 < 100ms), снятие Semaphore
      из MassFillSecond, FK estate + full gate ×3
- [ ] Фаза 5: документация (FOREIGN_KEYS.md §9, BENCHMARKS.md, W4-DESIGN bottleneck ledger)

## Остаток (2026-11-20, конец сессии): MassFillSecond — task-40 семья

FK estate 372/0/0 на overlay-движке; MassFillFirst green; MassFillSecond —
интермиттентные выжившие системы (1-2 на прогон, worker-происхождения): их
галактики удалены воркерскими delete-op'ами, walk видел/удалял своё, системы
чужих галактик выживали = **task-40 canonical-mutation contamination**
("rolled-back tx's list mutation survives + later committer makes the orphan
durable", "galaxy with ZERO commits" — задокументированный W-4-class остаток
доменных записей). Overlay вылечил ДЕРЕВЯННУЮ половину W-4; доменно-записная
половина (task 40: read-side cloning / prevObject aliasing) — следующий блок.
Зонд SURVIVING оставлен в тесте (диагностика следующей сессии).

## Уроки, которые дизайн НЕ задействует (все — из task 41)

- не обращаем структурные трансформации (split/move/collapse) — их undo в обратную
  сторону несостоятелен (rebalance budgets, «in-memory mutated node graph»);
- не чистим in-memory children sweep'ами (удаляли живые узлы);
- не guess'им по инстансам (registry first-instance-wins, canonicalTree = stale);
- не применяем overlay после фаз (POINT 6-провал «Illegal state of record to commit») —
  только внутри phase-1 владельца.

## Verification

- FK estate 372 ×3; full gate ×3; FKBENCH: fk=ON t=10 ≥ 100 tx/s;
  XdStorageTreeStructureRollbackTest collapse-rollback GREEN (снята @Disabled);
  MassFillFirst/Second зелёные БЕЗ Semaphore.

Estimate: 2–4 сессии (ядро + интеграция + верификация).
