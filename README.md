# logistics-intake-practice

独立 Java 练习项目：在内存、文件或 SQLite 中登记点位、搬运申请、Outbox、Inbox 与 Workflow，支持状态机、并发保护、Fake 外部执行系统、统计与健康检查，并用固定 `.scenario` 文件做确定性仿真、不变量检查、证据与回放。

**边界**：本项目只做本地仿真（`LOCAL_SIMULATION`），不连接真实系统，零真实外部写（`realExternalWrites=0`）。不证明真实 M4、Lab、容量或生产资格。

## 环境要求

- Ubuntu 24.04
- Java 21（实测 `java 21.0.12.1` / `javac 21.0.12.1`）
- UTF-8
- 仅 Java 标准库 + `lib/sqlite-jdbc-3.46.1.3.jar`

## 目录与模块

### 数据模型

- `src/main/java/lab/Point.java` — 点位记录
- `src/main/java/lab/TransferApplication.java` — 搬运申请记录（含状态）
- `src/main/java/lab/ApplicationState.java` — 申请状态与允许的转换表
- `src/main/java/lab/Stats.java` — 统计结果
- `src/main/java/lab/HealthReport.java` — 健康检查结果

### 可靠意图与事件

- `src/main/java/lab/OutboxStatus.java` / `OutboxEntry.java` — Outbox 状态与记录
- `src/main/java/lab/InboxStatus.java` / `InboxEntry.java` — Inbox 状态与记录

### 工作流

- `src/main/java/lab/WorkflowState.java` — 工作流状态
- `src/main/java/lab/WorkflowStepState.java` — 工作流步骤状态
- `src/main/java/lab/WorkflowStep.java` — 工作流步骤
- `src/main/java/lab/WorkflowInstance.java` — 工作流实例

### 外部执行

- `src/main/java/lab/RcsExecutionPort.java` — 外部执行系统接口
- `src/main/java/lab/ExternalOrderResult.java` — 外部返回结果
- `src/main/java/lab/FakeRcsAdapter.java` — 虚拟 M4

### 存储

- `src/main/java/lab/Storage.java` — 存储接口（`loadState` / `commitState`）
- `src/main/java/lab/RegistryState.java` — 五集合整状态快照
- `src/main/java/lab/MemoryStorage.java` — 内存实现（copy-on-write）
- `src/main/java/lab/FileStorage.java` — 文件实现（staging + ATOMIC_MOVE）
- `src/main/java/lab/SqliteStorage.java` — SQLite 实现（单事务、五层只读）
- `src/main/java/lab/Migration.java` / `MigrationRunner.java` — 迁移与执行器
- `lib/sqlite-jdbc-3.46.1.3.jar` — SQLite JDBC 驱动

### 入口与校验

- `src/main/java/lab/Registry.java` — 业务规则、查询与状态转换
- `src/main/java/lab/Main.java` — 终端命令入口
- `src/main/java/lab/StartupOptions.java` / `StartupException.java` — 启动参数解析（fail closed）
- `src/main/java/lab/RegistryChecks.java` — 业务检查入口
- `src/main/java/lab/ConcurrencyChecks.java` — 并发检查入口
- `src/main/java/lab/RegistryException.java` — 业务错误码与详情
- `src/main/java/lab/RegistrationResult.java` — 新增或重复的返回结果

### 确定性仿真（`lab.simulation`）

- 场景：`ScenarioPackage`、`ScenarioEvent`、`ExternalScript`、`OracleRule`
- 解析与时间：`ScenarioParser`、`ScenarioValidationException`、`VirtualClock`
- 运行：`SimulationTarget`、`RegistrySimulationTarget`、`ScriptedFakeRcsAdapter`、`ExternalInteraction`、`SimulationRuntime`、`SimulationRunResult`
- Oracle：`InvariantOracle`、`OracleContext`、`OracleResult`
- 证据与回放：`EvidenceBundleWriter`、`EvidenceBundleValidator`、`EvidenceValidationException`、`ReplayComparator`、`ReplayResult`
- 入口：`SimulationMain`
- 测试：`src/test/java/lab/ReliabilityCloseoutChecks.java`、`src/test/java/lab/simulation/ScenarioParserChecks.java`、`src/test/java/lab/simulation/SimulationRuntimeChecks.java`、`src/test/java/lab/simulation/ReplayEvidenceChecks.java`

## 编译

必须用 Java 21，产物固定在 `out/main` 与 `out/test`（`out/vscode` 只给 IDE 用，不参与验证）：

    mkdir -p out/main out/test build
    find src/main/java -type f -name '*.java' | LC_ALL=C sort > build/main-sources.txt
    find src/test/java -type f -name '*.java' | LC_ALL=C sort > build/test-sources.txt
    javac -cp "lib/sqlite-jdbc-3.46.1.3.jar" --release 21 -encoding UTF-8 -d out/main @build/main-sources.txt
    javac -cp "out/main:lib/sqlite-jdbc-3.46.1.3.jar" --release 21 -encoding UTF-8 -d out/test @build/test-sources.txt

产物必须 `major version: 65`：

    javap -verbose out/main/lab/ApplicationState.class | grep 'major version'

## 启动

默认使用内存存储，退出后数据清空：

    java -cp "out/main:lib/sqlite-jdbc-3.46.1.3.jar" lab.Main

使用文件存储，数据保存在 `data/` 目录：

    java -cp "out/main:lib/sqlite-jdbc-3.46.1.3.jar" lab.Main file

使用 SQLite 存储，数据保存在 `data/logistics.db`：

    java -cp "out/main:lib/sqlite-jdbc-3.46.1.3.jar" lab.Main sqlite

只读模式，拒绝所有写操作：

    java -cp "out/main:lib/sqlite-jdbc-3.46.1.3.jar" lab.Main readonly
    java -cp "out/main:lib/sqlite-jdbc-3.46.1.3.jar" lab.Main sqlite readonly

切换外部执行系统的模拟模式：

    java -cp "out/main:lib/sqlite-jdbc-3.46.1.3.jar" lab.Main fake-accept
    java -cp "out/main:lib/sqlite-jdbc-3.46.1.3.jar" lab.Main fake-reject
    java -cp "out/main:lib/sqlite-jdbc-3.46.1.3.jar" lab.Main fake-timeout-created
    java -cp "out/main:lib/sqlite-jdbc-3.46.1.3.jar" lab.Main fake-timeout-not-created

参数可组合（存储 × 外部模式）：

    java -cp "out/main:lib/sqlite-jdbc-3.46.1.3.jar" lab.Main sqlite fake-reject

## 命令列表

- `point add <pointId> <name>` / `point show <pointId>` / `point list`
- `application add <applicationId> <fromPointId> <toPointId>`
- `application show <applicationId>` / `application list`
- `application dispatch <applicationId>` / `application query <applicationId>`
- `application callback <applicationId> <eventType>`
- `application execute <applicationId>` / `application complete <applicationId>` / `application cancel <applicationId>`
- `outbox list` / `outbox process`
- `inbox list`
- `workflow start <applicationId>` / `workflow advance <applicationId>` / `workflow show <applicationId>` / `workflow list`
- `stats` / `health`
- `migration status` / `migration list`
- `help` / `exit`

## 业务规则

- 编号格式：`[A-Z][A-Z0-9_-]{0,31}`
- 名称 1—40 字符，不含空白
- 点位编号重复拒绝，不覆盖原值
- 申请编号重复：
  - 起终点相同 → 返回原记录，输出 `OK APPLICATION_REPLAY`
  - 起终点不同 → 输出 `ERR APPLICATION_CONFLICT`，不覆盖原记录
- 新申请要求起点、终点均已登记，且起点不等于终点

## 申请状态机

新申请初始状态 `CREATED`，允许的转换：

    CREATED    → DISPATCHED, CANCELLED, UNKNOWN
    DISPATCHED → EXECUTING, CANCELLED
    EXECUTING  → COMPLETED, CANCELLED
    UNKNOWN    → DISPATCHED, CANCELLED
    COMPLETED  → （终态，不允许任何转换）
    CANCELLED  → （终态，不允许任何转换）

非法转换输出 `ERR INVALID_STATE_TRANSITION <applicationId>`；未知申请输出 `ERR UNKNOWN_APPLICATION <applicationId>`。

## 外部执行系统

`application dispatch` 通过 `RcsExecutionPort` 调用外部执行系统。Fake 返回结果决定状态：

    ACCEPTED   → DISPATCHED，记录 externalOrderId
    REJECTED   → 保持原状态，输出 ERR RCS_REJECTED
    TIMEOUT    → UNKNOWN，externalOrderId 暂缺

`application query` 用于 UNKNOWN 对账：

    ACCEPTED   → DISPATCHED，补记 externalOrderId
    NOT_FOUND  → 保持 UNKNOWN，输出 ERR EXTERNAL_ORDER_NOT_FOUND

## Outbox

`application dispatch` 在调用外部系统之前先提交 Outbox 意图。状态：`PENDING`（待发送）、`SENT`（已发送成功）、`FAILED`（发送失败）。`outbox process` 处理 `PENDING` 记录，恢复时先 `query` 再决定，不盲目重发。

## Inbox

`application callback` 将外部事件写入 Inbox。状态：`PENDING`、`PROCESSED`、`REJECTED`。eventType：`STARTED → EXECUTING`、`COMPLETED → COMPLETED`、`FAILED → CANCELLED`。重复 callback 幂等；乱序 callback 被拒绝。

## Workflow

固定 3 步流程 `DISPATCH → EXECUTE → COMPLETE`，工作流状态 `RUNNING → COMPLETED`。每一步只有申请状态**达到或越过**该步目标时才标记完成并推进索引；超时（UNKNOWN）、拒绝、取消时步骤保持 `PENDING`，索引不动。

## 统计与健康检查

`stats` 输出各类数据数量与状态分布。`health` 输出：

    HEALTH mode READ_WRITE
    HEALTH storage OK
    HEALTH points 2
    HEALTH applications 1 unknown=0
    HEALTH outbox total=1 pending=0 failed=0
    HEALTH inbox total=1 pending=0
    HEALTH workflows total=1 running=1
    HEALTH OVERALL OK

健康状态：`OK` 全部正常、`WARN` 有 UNKNOWN / PENDING / FAILED、`FAIL` Storage 打不开。只读模式下 `HEALTH mode READ_ONLY`，写命令返回 `ERR READ_ONLY`，读命令正常。

## 存储格式

### 文件模式

    data/points.txt         P|<pointId>|<name>
    data/applications.txt   A|<applicationId>|<fromPointId>|<toPointId>|<state>|<externalOrderId>
    data/outbox.txt         O|<entryId>|<applicationId>|<fromPointId>|<toPointId>|<status>|<externalOrderId>|<attempts>
    data/inbox.txt          I|<entryId>|<applicationId>|<eventType>|<status>|<reason>
    data/workflows.txt      W|<instanceId>|<applicationId>|<state>|<currentStepIndex>|<step1>:<state1>,<step2>:<state2>,<step3>:<state3>

### SQLite 模式

    data/logistics.db

    points (point_id PRIMARY KEY, name)
    applications (application_id PRIMARY KEY, from_point_id, to_point_id, state, external_order_id)
    outbox (entry_id PRIMARY KEY, application_id, from_point_id, to_point_id, status, external_order_id, attempts)
    inbox (entry_id PRIMARY KEY, application_id, event_type, status, reason)
    workflows (instance_id PRIMARY KEY, application_id, state, current_step_index, steps)

## 验证方式（唯一命令）

    bash scripts/verify-simulation.sh

脚本固定 28 步，任一步失败立即非零退出，不吞结果。它依次完成：

1. 仓库根自检、JDK 21 自检、只清理 `out` / `build/runs` / `build/case-runs` / `build/test-temp`；
2. 用 `LC_ALL=C sort` 生成源文件清单，编译到 `out/main`、`out/test`，并核对 `major version: 65`；
3. 六个检查入口逐字核对：`CHECKS_PASSED=279`、`CONCURRENCY_CHECKS_PASSED=8`、`RELIABILITY_R1_CASES_PASSED=14`、`SCENARIO_PARSER_CASES_PASSED=16`、`SIMULATION_RUNTIME_CASES_PASSED=18`、`REPLAY_EVIDENCE_R1_CASES_PASSED=12`；
4. 30 个旧 case 各自在 `build/case-runs/<name>` 独立工作目录运行，产物写 `build/case-runs/<name>.actual.txt`，与 `cases/<name>.expected.txt` 逐字节 diff；
5. 3 个合法场景 validate 退出 0；2 个 parse 非法场景退出 2 且输出稳定 code；`run scenarios/invalid/invalid-unscripted-call.scenario` 退出 1、`RUN_FAILED`、`ORC-SYS-001=FAIL`；
6. 3 个合法场景各 run 一次，SIM-002 再 run 一次并 `compare` → `REPLAY_MATCH`；
7. 5 个证据目录逐一 `sha256sum -c manifest.sha256`，核对 7 文件形状、manifest 6 行、`run-summary.txt` 21 字段顺序；
8. 扫描 Evidence 与 README/docs 的绝对路径、用户名、凭据值、真实内网 IP、越级声明词，并检查工作区无 `.class` / `.db` / `-wal` / `-shm` / `-journal` 残留、源码无 `Thread.sleep` / `Instant.now` / `currentTimeMillis`。

全部通过的终点行（逐字）：

    SIMULATION_R1_VERIFICATION_PASS originalCases=30 validScenarios=3 invalidScenarios=3 replay=1 evidenceBundles=5 realExternalWrites=0

## 确定性仿真

### 用途与证明范围

从固定 `.scenario` 文件驱动既有业务入口，生成本地仿真证据。只证明练习模型在固定输入下的本地行为，不证明真实 M4、Lab、容量或生产资格。

### CLI 与退出码

    java -cp "out/main:lib/sqlite-jdbc-3.46.1.3.jar" lab.simulation.SimulationMain validate <scenarioFile>
    java -cp "out/main:lib/sqlite-jdbc-3.46.1.3.jar" lab.simulation.SimulationMain run <scenarioFile> <evidenceDir>
    java -cp "out/main:lib/sqlite-jdbc-3.46.1.3.jar" lab.simulation.SimulationMain compare <dirA> <dirB>

| 子命令 | 结果 | 退出码 |
|---|---|---|
| `validate` | `SCENARIO_VALID …` | 0 |
| `validate` | `ERR <稳定 code>`（parse 非法） | 2 |
| `run` | `RUN_COMPLETED …` + `EVIDENCE_WRITTEN …` | 0 |
| `run` | `RUN_FAILED …`（事件 / Oracle 失败，证据仍写出） | 1 |
| `run` | `RUN_INCONCLUSIVE …`（证据未发布或内部错误） | 3 |
| `compare` | `REPLAY_MATCH …` | 0 |
| `compare` | `REPLAY_DIFFERENT <file>` | 1 |
| `compare` | `REPLAY_INVALID_EVIDENCE <A/B:code>` | 2 |
| 任意 | `ERR SIMULATION_USAGE`（用法错误） | 2 |

验证脚本本身：全部通过退出 0，任一步失败非零退出。

### 场景

3 个合法场景：`scenarios/sim-001-happy.scenario`、`scenarios/sim-002-timeout-created.scenario`、`scenarios/sim-003-timeout-not-created.scenario`。

3 个非法场景：`scenarios/invalid/invalid-backward-time.scenario`（`SCENARIO_EVENT_ORDER`）、`invalid-duplicate-event.scenario`（`SCENARIO_DUPLICATE_EVENT`）、`invalid-unscripted-call.scenario`（运行期 `UNSCRIPTED_EXTERNAL_CALL`）。

### 证据目录

每次 `run` 生成 7 个文件：`scenario.scenario`、`scenario.sha256`、`run-summary.txt`（21 个固定字段）、`event-timeline.tsv`、`external-interactions.tsv`、`oracle-results.tsv`、`manifest.sha256`。字段与列格式见 `docs/simulation-format.md`。

### 系统不变量

- ORC-SYS-001 外部调用全部有脚本
- ORC-SYS-002 同一申请最多一个有效外单
- ORC-SYS-003 Unknown 先对账
- ORC-SYS-004 终态不倒退
- ORC-SYS-005 Evidence 不越级（`LOCAL_SIMULATION`）
- ORC-SYS-006 零真实外部写

## 禁止范围

- 不登录、探测或修改 lab-server-01；不访问实验室工作站、NAS、现场网络。
- 不连接真实 M4、AGV、线边仓、PLC、MES、上位机或数据库；不实现真实 M4 字段合同。
- 不用网络请求替代本地 Fake；不用真实 `Thread.sleep` 等待逻辑时间；不以系统当前时间决定业务结论。
- 不产生任何真实外部写：`realExternalWrites=0`。
- `GATE-A=PASS` 之前不写 `lab.simulation.resource.*`（Part B 未开始）。
- 不修改 `cases/*.expected.txt`；不用 `catch (Exception ignored)` 吞错。
- 不声称 `M4_CONTRACT_PASS`、`VERIFIED_LAB`、`SITE_PASS`、`PRODUCTION_READY`、`CAPACITY_PASS`。

## 文档

- `docs/schema-v0.1.md` — 数据 Schema 草案
- `docs/traceability.md` — Requirement 追踪表
- `docs/evidence-template.md` — Evidence Bundle 模板
- `docs/open-items.md` — Open Item 与验证债务清单
- `docs/facts-evidence-levels.md` — 事实与证据等级整理
- `docs/security-baseline.md` — Security 基线草稿
- `docs/observability.md` — Observability 草稿
- `docs/summary.md` — 练习项目总结
- `docs/simulation-format.md` — 场景格式与 Evidence 格式（21 字段、7 文件、列格式）
- `docs/simulation-boundary.md` — 仿真边界与证据等级
- `docs/simulation-handoff.md` — 仿真交付说明
- `docs/delivery-verification.md` — 分阶段复验记录
- `docs/mutation-results.md` — MUT-01..05 变异测试记录
