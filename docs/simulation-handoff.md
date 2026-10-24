# 仿真基础交付说明

| 文档属性 | 内容 |
|---|---|
| 版本 | V0.2 |
| 日期 | 2026-10-24 |
| 类型 | 仿真基础交付说明 |
| 对应正式文档 | IDS 仿真、回放与验证架构说明书 |
| 状态 | FINAL_CANDIDATE（PR3 交付候选，等待 PR 合并与独立 GATE-A 复核） |

## 绑定信息

| 项 | 值 |
|---|---|
| 绑定 commit | `88bf021`（PR3 基线 main，双亲 `30bb272` + `ef76dba`） |
| PR3 分支 | `r1-verification`（从 `88bf021` 创建） |
| 交付 ZIP 文件名 | `logistics-intake-practice-STU-SIM-01-R1-main.zip` |
| ZIP 绑定值 | 字节数、条目数、SHA-256、source commit 见 ZIP 外的 `delivery-receipt.txt` |
| 证据等级 | `LOCAL_SIMULATION` |

## 交付清单

### 新增包

- `lab.simulation`（26 个 Java 文件）

### 类（26 个）

场景模型：`ScenarioPackage`、`ScenarioEvent`、`ExternalScript`、`OracleRule`

编译与时间：`ScenarioParser`、`ScenarioValidationException`、`VirtualClock`

运行与 Fake：`SimulationTarget`、`RegistrySimulationTarget`、`ScriptedFakeRcsAdapter`、`ExternalInteraction`、`ExternalCallAttempt`、`SimulationRuntime`、`SimulationRunResult`、`RunFactLog`、`RunFailure`、`StateTransition`

Oracle：`InvariantOracle`、`OracleContext`、`OracleResult`

Evidence 与 Replay：`EvidenceBundleWriter`、`EvidenceBundleValidator`、`EvidenceValidationException`、`ReplayComparator`、`ReplayResult`

入口：`SimulationMain`

### 场景（6 个）

- `scenarios/sim-001-happy.scenario`
- `scenarios/sim-002-timeout-created.scenario`
- `scenarios/sim-003-timeout-not-created.scenario`
- `scenarios/invalid/invalid-backward-time.scenario`
- `scenarios/invalid/invalid-duplicate-event.scenario`
- `scenarios/invalid/invalid-unscripted-call.scenario`

### 测试（4 个）

- `src/test/java/lab/ReliabilityCloseoutChecks.java`（14 个具名 case）
- `src/test/java/lab/simulation/ScenarioParserChecks.java`（16 个具名 case）
- `src/test/java/lab/simulation/SimulationRuntimeChecks.java`（18 个具名 case）
- `src/test/java/lab/simulation/ReplayEvidenceChecks.java`（12 个具名 case）

### 脚本

- `scripts/verify-simulation.sh`（28 步一键验证）

### 文档

- `docs/simulation-boundary.md`
- `docs/simulation-format.md`
- `docs/simulation-handoff.md`
- `docs/mutation-results.md`
- `docs/delivery-verification.md`（追加 PR3 复验记录）

## 行为合同

### ScenarioParser

- 严格 UTF-8、无 BOM、无 NUL、不超过 1 MiB
- 原始字节 SHA-256
- header / 事件顺序 / 脚本 ordinal / 参数数量 / END 位置校验
- 失败抛 `ScenarioValidationException`，CLI 输出 `ERR <code>`、退出码 2

### VirtualClock

- 从 `START_AT` 开始，按事件偏移推进
- 时间倒退 → `TIME_TRAVEL_BACKWARD`
- 不使用系统当前时间

### ScriptedFakeRcsAdapter

- 按 operation + ordinal 查找脚本
- 找不到 → `UNSCRIPTED_EXTERNAL_CALL`
- 记录 `ExternalInteraction` 时间线；不产生真实外部写

### SimulationRuntime

- 每次运行建立全新 Storage / Registry / Clock / Fake
- 首错即停：首个失败事件之后不再执行后续事件
- 接入 `InvariantOracle`；`runStatus` 取 `COMPLETED` / `FAILED` / `INCONCLUSIVE`

### InvariantOracle

- 6 条系统不变量不可由场景关闭
- 场景 `EXPECT` 独立报告 PASS/FAIL

### EvidenceBundleWriter / Validator

- 7 个文件；manifest 恰好 6 行且不含自身
- `run-summary.txt` 21 个固定字段（`schemaVersion=2`）
- 原子发布：staging → 校验 → `ATOMIC_MOVE` → 再校验；失败不留 staging
- 目标目录已存在直接拒绝；已存在的字节不被修改

### ReplayComparator

- 先校验两侧证据，非法 → `REPLAY_INVALID_EVIDENCE A:/B:<code>`（退出码 2）
- `scenarioDigest` 相同后才逐文件比较
- `REPLAY_MATCH` / `REPLAY_DIFFERENT <file>` / `REPLAY_INVALID_EVIDENCE`

## 验收事实（2026-10-24 实测，`bash scripts/verify-simulation.sh`）

```
CHECKS_PASSED=279
CONCURRENCY_CHECKS_PASSED=8
RELIABILITY_R1_CASES_PASSED=14
SCENARIO_PARSER_CASES_PASSED=16
SIMULATION_RUNTIME_CASES_PASSED=18
REPLAY_EVIDENCE_R1_CASES_PASSED=12
originalCases=30（逐字节 diff）
REPLAY_MATCH SIM-002 version=1
SIMULATION_R1_VERIFICATION_PASS originalCases=30 validScenarios=3 invalidScenarios=3 replay=1 evidenceBundles=5 realExternalWrites=0
```

变异测试：MUT-01..05 五项均在 `mktemp -d` 临时副本中完成，每项都让对应测试以非零退出失败，记录见 `docs/mutation-results.md`。

## 已知技术债与当前限制

- Fake 状态是内存态，跨进程不保留（回放只做单进程证据比较）
- Oracle 只读取运行时公开快照，不读取 `HealthReport`
- `run-summary.txt` 的 21 字段集合由本仓库固定（任务书 3.8 原文未落地，见验收簿 D-9）
- 证据格式升级到 `schemaVersion=2` 后，旧证据包不再通过新校验器；仓库内不跟踪旧证据包
- 未连接真实 M4、AGV、PLC、MES
- 不代表真实 Lab、容量或生产资格
- Part B（`lab.simulation.resource.*`）未开始，`GATE-A=NOT_READY`

## 声明

    FORMAL_REPOSITORY_CHANGE=NONE
    REAL_M4_ACCESS=NONE
    REAL_EXTERNAL_WRITE_COUNT=0
