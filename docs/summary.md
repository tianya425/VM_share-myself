# 练习项目总结

| 文档属性 | 内容 |
|---|---|
| 版本 | V0.7 |
| 日期 | 2026-09-24 |
| 类型 | 练习项目总结 |
| 状态 | FINAL |

## 一、练习起点

本仓库是 IDS 入门练习项目 logistics-intake-practice。

起点是任务书要求的三阶段练习：

- 第一阶段：点位登记与查询
- 第二阶段：搬运申请登记、查询与重复识别
- 第三阶段：自动检查与边界样例

三阶段已全部完成。

## 二、额外完成的阶段

在三阶段基础上，按正式 IDS 文档继续推进了 14 个阶段：

- 第四阶段：文件持久化存储
- 第五阶段：申请状态机
- 第六阶段：幂等并发保护
- 第七阶段：Fake 外部执行系统
- 第八阶段：Outbox 可靠意图
- 第九阶段：v0.1 文档草案
- 第十阶段：Inbox 事件接收
- 第十一阶段：Security 与 Observability 文档
- 第十二阶段：Workflow 骨架
- 第十三阶段：SQLite 持久化
- 第十四阶段：查询与统计
- 第十五阶段：健康检查
- 第十六阶段：只读模式
- 第十七阶段：Migration 版本管理

## 三、PR 一览

| PR | 标题 | 状态 |
|---|---|---|
| #2 | 实现点位登记与命令行查询 | Merged |
| #3 | 实现搬运申请登记、查询与重复识别 | Merged |
| #4 | 补齐登记器自动检查与边界测试 | Merged |
| #5 | 增加文件持久化存储 | Merged |
| #6 | 增加申请状态机 | Merged |
| #7 | 增加幂等并发保护 | Merged |
| #8 | 增加 Fake 外部执行系统 | Merged |
| #10 | 增加 Outbox 可靠意图 | Merged |
| #11 | 建立 docs 分支 PR 记录 | Merged |
| #12 | 增加 Inbox 事件接收 | Merged |
| #13 | 增加 Security 和 Observability 文档草案 | Merged |
| #14 | 增加 Workflow 骨架 | Merged |
| #15 | 增加练习项目总结 | Merged |
| #16 | 增加 SQLite 持久化 | Merged |
| #17 | 增加查询与统计 | Merged |
| #19 | 增加健康检查 | Merged |
| #20 | 增加只读模式 | Merged |
| #21 | 增加 Migration 版本管理 | 待合并 |

## 四、代码资产

- lab 包 28 个 Java 文件
- lib 目录 1 个 jar（sqlite-jdbc-3.46.1.3.jar）
- cases 目录 34 组样例
- docs 目录 8 个文档
- RegistryChecks 275 项判断
- ConcurrencyChecks 8 项判断

### 核心 Java 类

数据模型：

- Point
- TransferApplication
- ApplicationState
- Stats
- HealthReport

外部执行：

- RcsExecutionPort
- ExternalOrderResult
- FakeRcsAdapter

可靠意图：

- OutboxEntry
- OutboxStatus

事件接收：

- InboxEntry
- InboxStatus

工作流：

- WorkflowInstance
- WorkflowState
- WorkflowStep
- WorkflowStepState

存储与迁移：

- Storage
- MemoryStorage
- FileStorage
- SqliteStorage
- Migration
- MigrationRunner

入口与校验：

- Registry
- Main
- RegistryChecks
- ConcurrencyChecks
- RegistryException
- RegistrationResult

### 命令列表

- point add / show / list
- application add / show / list / dispatch / query / callback / execute / complete / cancel
- outbox list / process
- inbox list
- workflow start / advance / show / list
- stats
- health
- migration status / list
- help / exit

### 存储模式

- 内存模式：java -cp "out:lib/sqlite-jdbc-3.46.1.3.jar" lab.Main
- 文件模式：java -cp "out:lib/sqlite-jdbc-3.46.1.3.jar" lab.Main file
- SQLite：java -cp "out:lib/sqlite-jdbc-3.46.1.3.jar" lab.Main sqlite
- 只读：java -cp "out:lib/sqlite-jdbc-3.46.1.3.jar" lab.Main readonly
- SQLite 只读：java -cp "out:lib/sqlite-jdbc-3.46.1.3.jar" lab.Main sqlite readonly

### 存储文件

文件模式：

- data/points.txt
- data/applications.txt
- data/outbox.txt
- data/inbox.txt
- data/workflows.txt

SQLite 模式：

- data/logistics.db

## 五、覆盖的正式文档条目

| MVP-REQ | 要求 | 覆盖程度 |
|---|---|---|
| MVP-REQ-01 | 统一入口与请求证据 | 部分（幂等 + 并发） |
| MVP-REQ-02 | 稳定身份与外部映射 | 部分（externalOrderId） |
| MVP-REQ-03 | 任务状态和不变量 | 部分（六态状态机） |
| MVP-REQ-09 | RCS Port 与 M4Adapter | 部分（Port + Fake） |
| MVP-REQ-11 | 回调、查询和对账 | 部分（query + callback） |
| MVP-REQ-12 | 取消过程 | 部分（cancel 转换） |
| MVP-REQ-13 | 业务完成 | 部分（complete 转换） |
| MVP-REQ-14 | 最小 Workflow | 部分（固定 3 步） |
| MVP-REQ-16 | 持久化与 Migration | 部分（文件 + SQLite + Migration + Outbox + Inbox + Workflow） |
| MVP-REQ-19 | 安全基线 | 文档草案 + 健康检查 + 只读模式 |
| MVP-REQ-20 | 可观测性 | 部分（stats + health） |
| MVP-REQ-21 | Fake 与故障注入 | 部分（四种模式） |

| CAP | 能力 | 覆盖程度 |
|---|---|---|
| CAP-02 | Task Lifecycle | 部分 |
| CAP-10 | RCS Execution Integration | 部分 |
| CAP-12 | Operations、Monitoring 与 Manual Control | 部分（stats + health） |
| CAP-14 | Data、SoT、Event 与 Audit | 部分 |
| CAP-15 | Security 与 Administration | 文档草案 |
| CAP-16 | Reliability / HA / DR | 部分（Outbox 恢复 + health + readonly） |
| CAP-18 | Simulation / Replay / Shadow | 部分（Fake） |

## 六、尚未实现

### 需要外部依赖或环境

- 真实 M4 合同测试（MVP-REQ-10）
- 真实数据库 Migration 到生产（MVP-REQ-16）
- 认证 / 授权（MVP-REQ-19）
- 结构化日志 / Metrics（MVP-REQ-20）
- 部署包（MVP-REQ-22）
- 容量测试（CAP-19）

### 尚未接触的核心概念

- Destination / ServicePoint（MVP-REQ-04）
- Resource Reservation（MVP-REQ-05）
- Priority / Admission（MVP-REQ-06 / 07）
- Vehicle Capability / Candidate（MVP-REQ-08）
- SiteProfile / 配置版本（MVP-REQ-15）
- 运营界面（MVP-REQ-17）
- 告警 / 审计 / 诊断证据（MVP-REQ-18）

## 七、量化

- 代码覆盖率 约 45%
- 文档覆盖率 约 25%
- 测试覆盖率 约 52%
- 概念理解覆盖 约 82%

## 八、文档清单

- docs/schema-v0.1.md 数据 Schema 草案
- docs/traceability.md Requirement 追踪表
- docs/evidence-template.md Evidence Bundle 模板
- docs/open-items.md Open Item 与验证债务清单
- docs/facts-evidence-levels.md 当前事实与证据等级整理
- docs/security-baseline.md Security 基线草案
- docs/observability.md Observability 草案
- docs/summary.md 本文件

## 九、下一步

### 可选方向

- A. 继续做代码（需要引入更多外部依赖）
- B. 继续写文档
- C. 结束当前阶段，等老师安排
- D. 进入正式项目

### 进入正式项目需要的前置资源

1. 正式项目仓库地址和访问权限
2. 代码库结构说明
3. 开发环境搭建文档
4. 第一个具体任务
5. 任务对应的正式文档条目
6. 代码提交规范
7. 碰到问题找谁

## 十、结论

本练习项目覆盖了正式 IDS v0.1 的核心概念：

- 状态机
- 幂等
- 并发保护
- 防腐层（Port / Adapter）
- Outbox / Inbox
- Unknown 对账
- Workflow
- 持久化（文件 + SQLite）
- 只读统计
- 健康检查
- 只读模式
- Migration 版本管理

这些概念在正式项目中通用，不因代码写法变化而失效。

练习代码的定位是：

- 用最小实现验证概念
- 不连接任何真实系统
- 不产生外部副作用
- 通过 GitHub PR 提交，合并后复验

练习代码不进入正式工程，如需进入正式项目，应另行评审。

## 十八、仿真基础（STU-SIM-01）

### 新增

- `lab.simulation` 包，18 个 Java 文件
- `scenarios/` 下 6 个场景
- 4 个测试类
- `scripts/verify-simulation.sh`
- 3 份仿真文档

### 三个 PR

- PR1：修正仿真接入前的可靠性语义（6 项门槛）
- PR2：实现确定性场景与虚拟时间运行时
- PR3：实现不变量回放与证据交付

### 一键验证

    SIMULATION_VERIFICATION_PASS scenarios=3 replay=1 evidence=4 realExternalWrites=0

## 变更记录

| 版本 | 日期 | 说明 |
|---|---|---|
| V0.1 | 2026-09-23 | 初稿 |
| V0.2 | 2026-09-23 | 增加第十四阶段：查询与统计 |
| V0.3 | 2026-09-23 | 增加第十五阶段：健康检查 |
| V0.4 | 2026-09-24 | 增加第十六阶段：只读模式 |
| V0.5 | 2026-09-24 | 增加第十七阶段：Migration 版本管理 |
| V0.6 | 2026-10-04 | 增加第十八阶段：仿真基础（STU-SIM-01） |
| V0.7 | 2026-10-04 | 增加仿真基础代码整洁化 |
