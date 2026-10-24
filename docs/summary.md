# 练习项目总结

| 文档属性 | 内容 |
|---|---|
| 版本 | V1.0 |
| 日期 | 2026-10-24 |
| 类型 | 练习项目总结 |
| 状态 | FINAL |

## 一、练习起点

本仓库是 IDS 入门练习项目 `logistics-intake-practice`，起点是任务书要求的三部分练习：点位登记与查询、搬运申请登记与重复识别、自动检查与边界样例。这三部分已在基线 `stu-sim-01-baseline` 中完成。

## 二、已实现能力（按功能，不按阶段）

- 点位登记、查询与列表
- 搬运申请登记、查询、列表与幂等重复识别
- 申请状态机（六态，含非法转换拒绝）
- 文件持久化与 SQLite 持久化
- 迁移版本管理（2 个版本，单事务回滚）
- 只读模式（五层拒绝写）
- Fake 外部执行系统（四种模式）
- Outbox 可靠意图（PENDING / SENT / FAILED，恢复先对账）
- Inbox 事件接收（幂等、乱序拒绝）
- 三步 Workflow（步骤完成绑定申请状态）
- 统计与健康检查
- 确定性仿真：场景解析、虚拟时间、运行时、六条系统不变量
- 证据包（7 文件、21 字段、原子发布、逐文件摘要）
- 回放比较（`REPLAY_MATCH` / `REPLAY_DIFFERENT` / `REPLAY_INVALID_EVIDENCE`）
- 28 步一键验证脚本与 5 项变异测试

## 三、PR 状态

| PR | 分支 | 标题 | 状态 | 合并 commit |
|---|---|---|---|---|
| #1 | `r1-reliability` | 可靠性整改（R1-REL-01..14） | Merged | `30bb272` |
| #2 | `r1-runtime` | 确定性场景、运行时、Oracle 与证据回放整改 | Merged | `88bf021` |
| #3 | `r1-verification` | 验收脚本、变异测试、文档冻结与交付 | 待合并 | — |

基线之前的历史 PR（编号 #2—#21，2026-09）属于 `legacy/vm-share-68f5a25` 归档历史，与当前 `main`（从 `bdb30ea` 起）不同源，不再代表当前代码。

## 四、代码资产（实测计数）

- `src/main/java`：57 个 Java 文件（`lab` 31 + `lab.simulation` 26）
- `src/test/java`：4 个检查类，60 个具名 case（14 + 16 + 18 + 12）
- `lib/`：1 个 jar（`sqlite-jdbc-3.46.1.3.jar`）
- `cases/`：30 个 `.in` 样例 + 31 个 `expected.txt`（94 个文件）
- `scenarios/`：3 个合法场景 + 3 个非法场景
- `docs/`：13 个文档
- `RegistryChecks`：279 项断言；`ConcurrencyChecks`：8 项

### 核心 Java 类

数据模型：`Point`、`TransferApplication`、`ApplicationState`、`Stats`、`HealthReport`

外部执行：`RcsExecutionPort`、`ExternalOrderResult`、`FakeRcsAdapter`

可靠意图与事件：`OutboxEntry`、`OutboxStatus`、`InboxEntry`、`InboxStatus`

工作流：`WorkflowInstance`、`WorkflowState`、`WorkflowStep`、`WorkflowStepState`

存储与迁移：`Storage`、`RegistryState`、`MemoryStorage`、`FileStorage`、`SqliteStorage`、`Migration`、`MigrationRunner`

入口与校验：`Registry`、`Main`、`StartupOptions`、`StartupException`、`RegistryChecks`、`ConcurrencyChecks`、`RegistryException`、`RegistrationResult`

仿真：`ScenarioParser`、`VirtualClock`、`SimulationRuntime`、`InvariantOracle`、`EvidenceBundleWriter`、`EvidenceBundleValidator`、`ReplayComparator`、`SimulationMain`

### 命令列表

- `point add` / `point show` / `point list`
- `application add` / `show` / `list` / `dispatch` / `query` / `callback` / `execute` / `complete` / `cancel`
- `outbox list` / `outbox process`
- `inbox list`
- `workflow start` / `workflow advance` / `workflow show` / `workflow list`
- `stats` / `health`
- `migration status` / `migration list`
- `help` / `exit`

### 存储模式

- 内存：`java -cp "out/main:lib/sqlite-jdbc-3.46.1.3.jar" lab.Main`
- 文件：`java -cp "out/main:lib/sqlite-jdbc-3.46.1.3.jar" lab.Main file`
- SQLite：`java -cp "out/main:lib/sqlite-jdbc-3.46.1.3.jar" lab.Main sqlite`
- 只读：`java -cp "out/main:lib/sqlite-jdbc-3.46.1.3.jar" lab.Main readonly`
- SQLite 只读：`java -cp "out/main:lib/sqlite-jdbc-3.46.1.3.jar" lab.Main sqlite readonly`

### 存储文件

文件模式：`data/points.txt`、`data/applications.txt`、`data/outbox.txt`、`data/inbox.txt`、`data/workflows.txt`

SQLite 模式：`data/logistics.db`

## 五、覆盖的正式文档条目

| MVP-REQ | 要求 | 覆盖程度 |
|---|---|---|
| MVP-REQ-01 | 统一入口与请求证据 | 部分（幂等 + 并发 + 仿真证据） |
| MVP-REQ-02 | 稳定身份与外部映射 | 部分（externalOrderId） |
| MVP-REQ-03 | 任务状态和不变量 | 部分（六态状态机 + 6 条系统不变量） |
| MVP-REQ-09 | RCS Port 与 M4Adapter | 部分（Port + Fake + 脚本化 Fake） |
| MVP-REQ-11 | 回调、查询和对账 | 部分（query + callback + Outbox 恢复） |
| MVP-REQ-12 | 取消过程 | 部分（cancel 转换） |
| MVP-REQ-13 | 业务完成 | 部分（complete 转换） |
| MVP-REQ-14 | 最小 Workflow | 部分（固定 3 步，绑定申请状态） |
| MVP-REQ-16 | 持久化与 Migration | 部分（文件 + SQLite + 单事务 Migration） |
| MVP-REQ-19 | 安全基线 | 部分（只读模式 + 本文档集的安全基线） |
| MVP-REQ-20 | 可观测性 | 部分（stats + health） |
| MVP-REQ-21 | Fake 与故障注入 | 部分（四种模式 + 场景脚本） |

| CAP | 能力 | 覆盖程度 |
|---|---|---|
| CAP-02 | Task Lifecycle | 部分 |
| CAP-10 | RCS Execution Integration | 部分 |
| CAP-12 | Operations、Monitoring 与 Manual Control | 部分（stats + health） |
| CAP-14 | Data、SoT、Event 与 Audit | 部分（三存储 + Outbox + Inbox） |
| CAP-15 | Security 与 Administration | 部分（只读 + 基线文档） |
| CAP-16 | Reliability / HA / DR | 部分（Outbox 恢复 + health + readonly） |
| CAP-18 | Simulation / Replay / Shadow | 部分（本地确定性仿真 + 证据 + 回放；Shadow 未做） |

## 六、尚未实现

需要外部依赖或环境：

- 真实 M4 合同测试（MVP-REQ-10）
- 生产数据库 Migration（MVP-REQ-16 的生产部分）
- 认证 / 授权（MVP-REQ-19）
- 结构化日志 / Metrics / Trace（MVP-REQ-20）
- 部署包（MVP-REQ-22）
- 容量测试（CAP-19）
- Lab 综合验证、Shadow、Canary、Site Acceptance

尚未接触的核心概念：

- Destination / ServicePoint（MVP-REQ-04）
- Resource Reservation（MVP-REQ-05）
- Priority / Admission（MVP-REQ-06 / 07）
- Vehicle Capability / Candidate（MVP-REQ-08）
- SiteProfile / 配置版本（MVP-REQ-15）
- 运营界面（MVP-REQ-17）
- 告警 / 审计 / 诊断证据（MVP-REQ-18）

## 七、量化（只列实测）

| 项 | 值 | 来源 |
|---|---|---|
| `CHECKS_PASSED` | 279 | `lab.RegistryChecks` |
| `CONCURRENCY_CHECKS_PASSED` | 8 | `lab.ConcurrencyChecks` |
| `RELIABILITY_R1_CASES_PASSED` | 14 | `lab.ReliabilityCloseoutChecks` |
| `SCENARIO_PARSER_CASES_PASSED` | 16 | `lab.simulation.ScenarioParserChecks` |
| `SIMULATION_RUNTIME_CASES_PASSED` | 18 | `lab.simulation.SimulationRuntimeChecks` |
| `REPLAY_EVIDENCE_R1_CASES_PASSED` | 12 | `lab.simulation.ReplayEvidenceChecks` |
| 旧 case 逐字节 diff | 30 / 30 | `scripts/verify-simulation.sh` |
| 真实外部写 | 0 | 每条证据 `realExternalWriteCount=0` |

不写代码行覆盖率、文档覆盖率等未测量的估计值。

## 八、文档清单

- `docs/schema-v0.1.md` — 数据 Schema 草案
- `docs/traceability.md` — Requirement 追踪表
- `docs/evidence-template.md` — Evidence Bundle 模板
- `docs/open-items.md` — Open Item 与验证债务清单
- `docs/facts-evidence-levels.md` — 事实与证据等级整理
- `docs/security-baseline.md` — Security 基线草稿
- `docs/observability.md` — Observability 草稿
- `docs/summary.md` — 本文件
- `docs/simulation-format.md` — 场景与 Evidence 格式（21 字段、7 文件、列格式）
- `docs/simulation-boundary.md` — 仿真边界
- `docs/simulation-handoff.md` — 仿真交付说明
- `docs/delivery-verification.md` — 分阶段复验记录
- `docs/mutation-results.md` — 变异测试记录

## 九、结论

本练习覆盖了正式 IDS v0.1 的核心概念：状态机、幂等、并发保护、防腐层（Port / Adapter）、Outbox / Inbox、Unknown 对账、Workflow、三存储持久化、迁移、只读隔离、健康检查、确定性仿真、证据与回放。

练习代码的定位：

- 用最小实现验证概念
- 不连接任何真实系统，`realExternalWrites=0`
- 通过 GitHub PR 提交，合并后复验
- 不进入正式工程；Part B 资源领域模型已在 PR4 接线，资源运行时与资源场景未完成（PR5/PR6）

## 变更记录

| 版本 | 日期 | 说明 |
|---|---|---|
| V0.1 | 2026-09-23 | 初稿 |
| V0.2 | 2026-09-23 | 增加查询与统计 |
| V0.3 | 2026-09-23 | 增加健康检查 |
| V0.4 | 2026-09-24 | 增加只读模式 |
| V0.5 | 2026-09-24 | 增加 Migration 版本管理 |
| V0.6 | 2026-10-04 | 增加仿真基础 |
| V0.7 | 2026-10-04 | 仿真代码整洁化 |
| V1.0 | 2026-10-24 | 删阶段号与估算覆盖率；按实测改写计数与 PR 状态（PR1/PR2 已合并，PR3 待合并） |
