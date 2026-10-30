# 资源领域说明（resource-world）

| 属性 | 内容 |
|---|---|
| 任务 | STU-SIM-02-R1 |
| 版本 | V1.0 |
| 日期 | 2026-10-30 |
| 状态 | FROZEN（跟随固定 8 Action / 8 EXPECT / 6 Oracle） |

## 1. Scope 与 Non-scope

- **Scope**：本地资源世界模型 —— Destination、ServicePoint、VirtualResource、Reservation 的登记、引用链、预约状态机、队列、幂等/冲突、释放/取消、启用/禁用、原子提交、加载校验、重启恢复，以及对应 DSL、Oracle、场景和 Evidence。
- **Non-scope**：真实 M4 / AGV / PLC / MES 合同、容量结论、生产资格、真实外部写、真实设备联调。

## 2. Domain Model

- `Destination(destinationId, name)`
- `ServicePoint(servicePointId, destinationId, pointId)` —— 固定三个字段，旧第四字段已删除
- `VirtualResource(resourceId, servicePointId, availability)`，新建默认 `ENABLED`
- `Reservation(reservationId, applicationId, destinationId, servicePointId, resourceId, state, requestedOffsetMillis, requestedLogicalSequence)`，`reservationId = "RSV-" + applicationId`
- `ReservationDecision(type, reservation, detail)`，`type ∈ HELD / WAITING / IDEMPOTENT / CONFLICT`

构造即校验：ID 语法、非空枚举、offset ≥ 0、logicalSequence ≥ 1、状态非 null；非法标量在构造边界拒绝。

## 3. Reference Chain

`Reservation → VirtualResource → ServicePoint → Destination`，且 `ServicePoint.pointId` 必须存在于点位表。

写入口按固定顺序检查：Destination → ServicePoint → Resource → 引用链；错误码分别为 `RESOURCE_DESTINATION_NOT_FOUND`、`RESOURCE_SERVICE_POINT_NOT_FOUND`、`RESOURCE_NOT_FOUND`、`RESOURCE_TARGET_MISMATCH`。

## 4. Reservation State Machine

```text
(无预约) --request--> HELD
(无预约) --request--> WAITING（资源被占用或 DISABLED）
WAITING --release/取消前驱--> HELD
HELD --release--> RELEASED
HELD/WAITING --cancel--> CANCELLED
RELEASED/CANCELLED --request--> RESERVATION_TERMINAL（拒绝）
```

终态 `RELEASED` / `CANCELLED` 不可重开；`isTerminal()` 是唯一判定。

## 5. Request / Idempotent / Conflict

`requestReservation(applicationId, destinationId, servicePointId, resourceId, offset, seq)` 固定顺序：

1. 参数与排序键校验（`RESERVATION_INVALID_ORDER`）；
2. application 存在（`RESERVATION_APPLICATION_NOT_FOUND`）；
3. 既有预约：终态 → `RESERVATION_TERMINAL`；同目标 → `IDEMPOTENT`（不提交）；不同目标 → `CONFLICT`（不抛异常、不提交）；
4. 无既有才检查 `CREATED`（`RESERVATION_APPLICATION_STATE`）；
5. 目标存在性与引用链；
6. availability 与 holder 决定 HELD/WAITING；
7. 候选快照单次 `commitState`，成功后才发布内存。

同目标幂等判断不参与排序键：重复请求保留第一次的 `requestedOffsetMillis` / `requestedLogicalSequence`。

## 6. Queue Ordering

WAITING 队列按 `requestedOffsetMillis → requestedLogicalSequence → applicationId` 固定升序；只晋升队首；空队列输出 `NONE`。

## 7. Release / Cancel

- `release`：仅 HELD 可释放；释放后晋升该资源队首（若 ENABLED）。
- `cancel`：HELD/WAITING 可取消；取消 HELD 后晋升队首。
- 非 holder release → `RESERVATION_NOT_HELD`；未知预约 → `RESERVATION_NOT_FOUND`。

## 8. Enable / Disable

- `RESOURCE_SET ... DISABLED`：不驱逐 holder；新请求进 WAITING。
- `RESOURCE_SET ... ENABLED`：无 holder 时晋升队首；有 holder 时不越过 holder。

## 9. Atomic Commit

写入口统一：`候选快照 → storage.commitState(整份 RegistryState) → 成功后 publish 内存`。提交失败时内存与已提交状态都不变，不留幽灵预约。`FileStorage` 使用单快照文件替换，不再逐个替换六个文件。

## 10. Load Validation

`ResourceStateValidator` 统一校验 16 步：ID 唯一、引用链完整、单容量、同申请最多一条非终态、队列排序、终态不倒退、字段边界等。`MemoryStorage` / `FileStorage` / `SqliteStorage` 加载时都执行同一校验，非法快照 fail closed。

## 11. Restart

`WORLD_RESTART`：保留同一 Storage / Clock / Fake，丢弃 Registry 与 Target，用 `loadState()` 重建；记录前后规范摘要 `RestartPair`。不触发任何外部调用。ORC-RES-SYS-006 输出 `restartPairs=<n>,mismatches=<m>`。

## 12. Stable Error Codes

`RESERVATION_INVALID_ORDER`、`RESERVATION_APPLICATION_NOT_FOUND`、`RESERVATION_APPLICATION_STATE`、`RESERVATION_TERMINAL`、`RESERVATION_NOT_HELD`、`RESERVATION_NOT_FOUND`、`RESOURCE_DESTINATION_NOT_FOUND`、`RESOURCE_SERVICE_POINT_NOT_FOUND`、`RESOURCE_NOT_FOUND`、`RESOURCE_TARGET_MISMATCH`、`RESOURCE_DUPLICATE_CONFLICT`、`RESOURCE_INVALID_AVAILABILITY`、`RESOURCE_STATE_INVALID`。

## 13. DSL Actions and EXPECT

固定 8 Action：`DESTINATION_ADD`、`SERVICE_POINT_ADD`、`RESOURCE_ADD`、`RESOURCE_SET`、`RESERVATION_REQUEST`、`RESERVATION_RELEASE`、`RESERVATION_CANCEL`、`WORLD_RESTART`。

固定 8 EXPECT：`RESERVATION_STATE`、`RESERVATION_DECISION`、`RESOURCE_AVAILABILITY`、`RESOURCE_HOLDER`、`WAIT_QUEUE`、`HELD_COUNT`、`NO_DOUBLE_HOLD`、`NO_ORPHAN_RESERVATION`。扩展规则使用 `ORC-RES-EXT-*`，不得替代固定 8 项。

## 14. System Oracles

| Oracle | 语义 |
|---|---|
| ORC-RES-SYS-001 | 同一资源任一观察点最多一个 HELD |
| ORC-RES-SYS-002 | 队列按固定键升序，只晋升队首 |
| ORC-RES-SYS-003 | Reservation 引用链完整 |
| ORC-RES-SYS-004 | IDEMPOTENT 请求不改变排序键 |
| ORC-RES-SYS-005 | DISABLED 不驱逐 holder |
| ORC-RES-SYS-006 | WORLD_RESTART 前后规范摘要一致 |

Oracle 只读，不调用 Registry 写入口，输出 `expected` / `actual` / `detail`。

## 15. Scenarios

| 场景 | 语义 |
|---|---|
| SIM-101 | 单资源占用与释放 |
| SIM-102 | 同 offset 两申请争用与晋升（logicalSequence 决胜） |
| SIM-103 | 重复请求幂等（条目数不变、排序键不变） |
| SIM-104 | 同一申请目标冲突（CONFLICT 正常决策，绑定不变） |
| SIM-105 | 禁用、等待、启用、再次禁用、释放 |
| SIM-106 | 两次 WORLD_RESTART 之间释放并晋升 |
| SIM-INVALID-101 | 缺失 Destination → `RESOURCE_DESTINATION_NOT_FOUND` |
| SIM-INVALID-102 | 目标链不匹配 → `RESOURCE_TARGET_MISMATCH` |
| SIM-INVALID-103 | 重复冲突 Resource ID → `RESOURCE_DUPLICATE_CONFLICT` |
| SIM-INVALID-104 | 非 holder release → `RESERVATION_NOT_HELD` |
| SIM-INVALID-105 | 终态重开 → `RESERVATION_TERMINAL` |

非法场景要求：exit=1、固定 code、首错后全部 `EVENT_SKIPPED`、保留最后已提交快照、继续执行只读 Oracle、七文件 Evidence、`realExternalWriteCount=0`。

## 16. Evidence and Verification

- 每次 run 恰 7 文件；manifest 恰 6 行升序；`run-summary.txt` 21 字段（`eventPassCount` / `eventFailCount` / `eventSkippedCount`）。
- 唯一验证命令：`bash scripts/verify-simulation.sh`（24 项）。
- 终点行：`SIMULATION_RESOURCE_VERIFICATION_PASS originalCases=30 baseValidScenarios=3 baseInvalidScenarios=3 resourceValidScenarios=6 resourceInvalidScenarios=5 resourceReplay=6 realExternalWrites=0`
- 六项 mutation 见 `docs/resource-mutation-results.md`。

FINAL_SOURCE_IDENTITY=BOUND_BY_EXTERNAL_DELIVERY_RECEIPT
DELIVERY_RECEIPT=delivery-receipt.txt
