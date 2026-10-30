# STU-SIM-02-R1 整改总结

| 属性 | 内容 |
|---|---|
| 任务 | STU-SIM-02-R1 资源合同整改与验证收口 |
| 分支 | `nzy/stu-sim-02-r1-contract-closeout` |
| base | `ecc0215`（`backup/stu-sim-02-before-r1`） |
| 检查点 | C1 先红 → C2 领域/预约 → C3 加载/原子存储 → C4 DSL/Oracle → C5 场景/Evidence → C6 文档/脚本/交付 |
| 证据等级 | `LOCAL_SIMULATION` |
| 终态 | `SELF_CHECK=PASS`；`GATE_B=WAIT_FOR_INDEPENDENT_REVIEW` |

## 一、实际实现

- **领域模型**：`ReservationDecision` 覆盖 `HELD` / `WAITING` / `IDEMPOTENT` / `CONFLICT` 四态并带 `detail`；`ServicePoint` 固定三字段；`Reservation` 构造函数拒绝非法标量。
- **请求算法**：固定顺序 —— 参数 → application 存在 → 既有预约（终态 / 同目标幂等 / 不同目标 CONFLICT）→ CREATED → Destination / ServicePoint / Resource 存在性 → 引用链 → 可用性与 holder 决定 HELD/WAITING → 候选快照单次 `commitState` → 成功后发布内存。
- **错误码**：`RESOURCE_DESTINATION_NOT_FOUND`、`RESOURCE_SERVICE_POINT_NOT_FOUND`、`RESOURCE_NOT_FOUND`、`RESOURCE_TARGET_MISMATCH`、`RESOURCE_DUPLICATE_CONFLICT`、`RESERVATION_TERMINAL`、`RESERVATION_NOT_HELD` 等逐字固定。
- **校验与存储**：`ResourceStateValidator` 16 步统一校验；`MemoryStorage` / `FileStorage` / `SqliteStorage` 加载均 fail closed；`FileStorage` 以整份 `RegistryState` 单快照原子提交，失败后内存与已提交状态都不变。
- **DSL 与 Oracle**：固定 8 Action / 8 EXPECT / 6 条 `ORC-RES-SYS`（ID 与语义固定，扩展规则改用 `ORC-RES-EXT-*`）。
- **场景**：6 个合法（SIM-101..106）、5 个非法（SIM-INVALID-101..105）逐编号重建；非法场景 exit=1、固定 code、首错后全部 `EVENT_SKIPPED`、保留最后已提交快照。
- **Evidence**：每次 run 恰 7 文件，manifest 恰 6 行升序，`run-summary.txt` 21 字段（计数字段为 `eventPassCount` / `eventFailCount` / `eventSkippedCount`），`realExternalWriteCount=0`。

## 二、实际计数（2026-10-30 实测）

| 项 | 值 |
|---|---|
| 旧 case | `originalCases=30`，30/30 逐字节通过 |
| 资源世界固定 case | `RESOURCE_WORLD_CASES=24` |
| R1 整改合同 case | `RESOURCE_R1_CONTRACT_CASES=42` |
| 资源场景 case | `RESOURCE_SCENARIO_CASES=10` |
| 资源合法 / 非法场景 | 6 / 5 |
| 资源双跑回放 | 6 对 `REPLAY_MATCH` |
| 资源 mutation | 6 / 6 被检出 |
| 真实外部写 | `realExternalWrites=0` |

终点行（唯一验证命令 `bash scripts/verify-simulation.sh`）：

```text
SIMULATION_RESOURCE_VERIFICATION_PASS originalCases=30 baseValidScenarios=3 baseInvalidScenarios=3 resourceValidScenarios=6 resourceInvalidScenarios=5 resourceReplay=6 realExternalWrites=0
```

## 三、外部回执绑定

Git 跟踪文档不写最终 commit/tree（避免自引用）。最终源码身份由仓库外 `delivery-receipt.txt` 绑定：

FINAL_SOURCE_IDENTITY=BOUND_BY_EXTERNAL_DELIVERY_RECEIPT
DELIVERY_RECEIPT=delivery-receipt.txt

回执同时记录最终 commit、tree、源码 ZIP SHA-256/字节数/条目数、bundle SHA-256、Evidence manifest 与验证日志 SHA-256。

## 四、未执行与边界

- 未访问 M4、实验室服务器、真实 AGV / PLC / MES / 现场网络；`M4_ACCESS=0`。
- 未执行真实设备联调、容量结论、生产资格评估；这些不属于本任务。
- 独立复核尚未完成；本交付只声明学生自检完成并等待复核。

## 五、文档清单

`README.md`、`docs/summary.md`、`docs/simulation-format.md`、`docs/simulation-boundary.md`、`docs/simulation-handoff.md`、`docs/traceability.md`、`docs/open-items.md`、`docs/resource-world.md`、`docs/resource-mutation-results.md`。
