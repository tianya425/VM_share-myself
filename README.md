# logistics-intake-practice

独立 Java 练习项目；实现内存、文件或 SQLite 中的点位、搬运申请、Outbox、Inbox、Workflow 登记，支持状态机、并发保护、Fake 外部执行系统、统计与健康检查，不连接真实系统。

## 环境要求

- Ubuntu 24
- Java 21
- UTF-8
- 仅 Java 标准库 + sqlite-jdbc

## 目录与模块

### 数据模型

- `src/main/java/lab/Point.java` — 点位记录
- `src/main/java/lab/TransferApplication.java` — 搬运申请记录（含状态）
- `src/main/java/lab/ApplicationState.java` — 申请状态与允许的转换表
- `src/main/java/lab/Stats.java` — 统计结果
- `src/main/java/lab/HealthReport.java` — 健康检查结果

### 可靠意图

- `src/main/java/lab/OutboxStatus.java` — Outbox 状态
- `src/main/java/lab/OutboxEntry.java` — Outbox 记录

### 事件接收

- `src/main/java/lab/InboxStatus.java` — Inbox 状态
- `src/main/java/lab/InboxEntry.java` — Inbox 记录

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

- `src/main/java/lab/Storage.java` — 存储接口
- `src/main/java/lab/MemoryStorage.java` — 内存实现
- `src/main/java/lab/FileStorage.java` — 文件实现
- `src/main/java/lab/SqliteStorage.java` — SQLite 实现
- `src/main/java/lab/Migration.java` — 一条数据库升级
- `src/main/java/lab/MigrationRunner.java` — 迁移执行器
- `lib/sqlite-jdbc-3.46.1.3.jar` — SQLite JDBC 驱动

### 入口与校验

- `src/main/java/lab/Registry.java` — 业务规则、查询与状态转换
- `src/main/java/lab/Main.java` — 终端命令入口
- `src/main/java/lab/RegistryChecks.java` — 业务检查入口
- `src/main/java/lab/ConcurrencyChecks.java` — 并发检查入口
- `src/main/java/lab/RegistryException.java` — 业务错误码与详情
- `src/main/java/lab/RegistrationResult.java` — 新增或重复的返回结果

## 编译

    javac -cp "lib/sqlite-jdbc-3.46.1.3.jar" --release 21 -encoding UTF-8 -d out src/main/java/lab/*.java

## 启动

默认使用内存存储，退出后数据清空：

    java -cp "out:lib/sqlite-jdbc-3.46.1.3.jar" lab.Main

使用文件存储，数据保存在 `data/` 目录：

    java -cp "out:lib/sqlite-jdbc-3.46.1.3.jar" lab.Main file

使用 SQLite 存储，数据保存在 `data/logistics.db`：

    java -cp "out:lib/sqlite-jdbc-3.46.1.3.jar" lab.Main sqlite
只读模式，拒绝所有写操作：

    java -cp "out:lib/sqlite-jdbc-3.46.1.3.jar" lab.Main readonly
    java -cp "out:lib/sqlite-jdbc-3.46.1.3.jar" lab.Main sqlite readonly
可通过参数切换外部执行系统的模拟模式：

    java -cp "out:lib/sqlite-jdbc-3.46.1.3.jar" lab.Main fake-accept
    java -cp "out:lib/sqlite-jdbc-3.46.1.3.jar" lab.Main fake-reject
    java -cp "out:lib/sqlite-jdbc-3.46.1.3.jar" lab.Main fake-timeout-created
    java -cp "out:lib/sqlite-jdbc-3.46.1.3.jar" lab.Main fake-timeout-not-created

参数可组合：

    java -cp "out:lib/sqlite-jdbc-3.46.1.3.jar" lab.Main sqlite fake-reject

## 命令列表

- `point add <pointId> <name>`
- `point show <pointId>`
- `point list`
- `application add <applicationId> <fromPointId> <toPointId>`
- `application show <applicationId>`
- `application list`
- `application dispatch <applicationId>`
- `application query <applicationId>`
- `application callback <applicationId> <eventType>`
- `application execute <applicationId>`
- `application complete <applicationId>`
- `application cancel <applicationId>`
- `outbox list`
- `outbox process`
- `inbox list`
- `workflow start <applicationId>`
- `workflow advance <applicationId>`
- `workflow show <applicationId>`
- `workflow list`
- `stats`
- `health`
- `migration status`
- `migration list`
- `help`
- `exit`

## 业务规则

- 编号格式：`[A-Z][A-Z0-9_-]{0,31}`
- 名称 1—40 字符，不含空白
- 点位编号重复拒绝，不覆盖原值
- 申请编号重复：
  - 起终点相同 → 返回原记录，输出 `OK APPLICATION_REPLAY`
  - 起终点不同 → 输出 `ERR APPLICATION_CONFLICT`，不覆盖原记录
- 新申请要求起点、终点均已登记，且起点不等于终点

## 申请状态机

新申请初始状态：

    CREATED

允许的转换：

    CREATED    → DISPATCHED, CANCELLED, UNKNOWN
    DISPATCHED → EXECUTING, CANCELLED
    EXECUTING  → COMPLETED, CANCELLED
    UNKNOWN    → DISPATCHED, CANCELLED
    COMPLETED  → （终态，不允许任何转换）
    CANCELLED  → （终态，不允许任何转换）

非法转换输出：

    ERR INVALID_STATE_TRANSITION <applicationId>

对未知申请：

    ERR UNKNOWN_APPLICATION <applicationId>

## 外部执行系统

`application dispatch` 通过 `RcsExecutionPort` 调用外部执行系统。

Fake 返回结果决定状态：

    ACCEPTED   → DISPATCHED，记录 externalOrderId
    REJECTED   → 保持原状态，输出 ERR RCS_REJECTED
    TIMEOUT    → UNKNOWN，externalOrderId 暂缺

`application query` 用于 UNKNOWN 对账：

    ACCEPTED   → DISPATCHED，补记 externalOrderId
    NOT_FOUND  → 保持 UNKNOWN，输出 ERR EXTERNAL_ORDER_NOT_FOUND

## Outbox

`application dispatch` 在调用外部系统之前先写 Outbox。

Outbox 状态：

    PENDING   待发送
    SENT      已发送成功
    FAILED    发送失败

`outbox process` 处理所有 PENDING 记录，用于进程重启后恢复。

## Inbox

`application callback` 将外部事件写入 Inbox。

Inbox 状态：

    PENDING    已接收，未处理
    PROCESSED  已处理
    REJECTED   被拒绝

eventType：

    STARTED   → EXECUTING
    COMPLETED → COMPLETED
    FAILED    → CANCELLED

重复 callback 幂等；乱序 callback 被拒绝。

## Workflow

固定 3 步流程：

    DISPATCH → EXECUTE → COMPLETE

工作流状态：

    RUNNING → COMPLETED

## 统计

    stats

输出各类数据数量与状态分布。

## 健康检查

    health

输出：
    HEALTH mode READ_WRITE
    HEALTH storage OK
    HEALTH points 2
    HEALTH applications 1 unknown=0
    HEALTH outbox total=1 pending=0 failed=0
    HEALTH inbox total=1 pending=0
    HEALTH workflows total=1 running=1
    HEALTH OVERALL OK

健康状态：

    OK    全部正常
    WARN  有 UNKNOWN / PENDING / FAILED
    FAIL  Storage 打不开
只读模式下：

    HEALTH mode READ_ONLY
    所有写命令返回 ERR READ_ONLY
    读命令正常
## 存储格式

### 文件模式

`data/points.txt` 每行：

    P|<pointId>|<name>

`data/applications.txt` 每行：

    A|<applicationId>|<fromPointId>|<toPointId>|<state>|<externalOrderId>

`data/outbox.txt` 每行：

    O|<entryId>|<applicationId>|<fromPointId>|<toPointId>|<status>|<externalOrderId>|<attempts>

`data/inbox.txt` 每行：

    I|<entryId>|<applicationId>|<eventType>|<status>|<reason>

`data/workflows.txt` 每行：

    W|<instanceId>|<applicationId>|<state>|<currentStepIndex>|<step1>:<state1>,<step2>:<state2>,<step3>:<state3>

### SQLite 模式

    data/logistics.db

五张表：

    points (point_id PRIMARY KEY, name)
    applications (application_id PRIMARY KEY, from_point_id, to_point_id, state, external_order_id)
    outbox (entry_id PRIMARY KEY, application_id, from_point_id, to_point_id, status, external_order_id, attempts)
    inbox (entry_id PRIMARY KEY, application_id, event_type, status, reason)
    workflows (instance_id PRIMARY KEY, application_id, state, current_step_index, steps)

## 测试方式

五组基础样例（内存模式）：

    java -cp "out:lib/sqlite-jdbc-3.46.1.3.jar" lab.Main < cases/points.in > cases/points.actual.txt
    diff -u cases/points.expected.txt cases/points.actual.txt

    java -cp "out:lib/sqlite-jdbc-3.46.1.3.jar" lab.Main < cases/happy-path.in > cases/happy-path.actual.txt
    diff -u cases/happy-path.expected.txt cases/happy-path.actual.txt

    java -cp "out:lib/sqlite-jdbc-3.46.1.3.jar" lab.Main < cases/rejection-and-replay.in > cases/rejection-and-replay.actual.txt
    diff -u cases/rejection-and-replay.expected.txt cases/rejection-and-replay.actual.txt

    java -cp "out:lib/sqlite-jdbc-3.46.1.3.jar" lab.Main < cases/boundaries.in > cases/boundaries.actual.txt
    diff -u cases/boundaries.expected.txt cases/boundaries.actual.txt

    java -cp "out:lib/sqlite-jdbc-3.46.1.3.jar" lab.Main < cases/state-machine.in > cases/state-machine.actual.txt
    diff -u cases/state-machine.expected.txt cases/state-machine.actual.txt

持久化两组（文件模式）：

    rm -rf data
    java -cp "out:lib/sqlite-jdbc-3.46.1.3.jar" lab.Main file < cases/persistence-1.in > cases/persistence-1.actual.txt
    diff -u cases/persistence-1.expected.txt cases/persistence-1.actual.txt

    java -cp "out:lib/sqlite-jdbc-3.46.1.3.jar" lab.Main file < cases/persistence-2.in > cases/persistence-2.actual.txt
    diff -u cases/persistence-2.expected.txt cases/persistence-2.actual.txt

Fake 四组（内存模式）：

    java -cp "out:lib/sqlite-jdbc-3.46.1.3.jar" lab.Main fake-accept < cases/fake-accept.in > cases/fake-accept.actual.txt
    diff -u cases/fake-accept.expected.txt cases/fake-accept.actual.txt

    java -cp "out:lib/sqlite-jdbc-3.46.1.3.jar" lab.Main fake-reject < cases/fake-reject.in > cases/fake-reject.actual.txt
    diff -u cases/fake-reject.expected.txt cases/fake-reject.actual.txt

    java -cp "out:lib/sqlite-jdbc-3.46.1.3.jar" lab.Main fake-timeout-created < cases/fake-timeout-created.in > cases/fake-timeout-created.actual.txt
    diff -u cases/fake-timeout-created.expected.txt cases/fake-timeout-created.actual.txt

    java -cp "out:lib/sqlite-jdbc-3.46.1.3.jar" lab.Main fake-timeout-not-created < cases/fake-timeout-not-created.in > cases/fake-timeout-not-created.actual.txt
    diff -u cases/fake-timeout-not-created.expected.txt cases/fake-timeout-not-created.actual.txt

Outbox 三组：

    java -cp "out:lib/sqlite-jdbc-3.46.1.3.jar" lab.Main fake-accept < cases/outbox-basic.in > cases/outbox-basic.actual.txt
    diff -u cases/outbox-basic.expected.txt cases/outbox-basic.actual.txt

    rm -rf data
    java -cp "out:lib/sqlite-jdbc-3.46.1.3.jar" lab.Main file fake-timeout-created < cases/outbox-process-1.in > cases/outbox-process-1.actual.txt
    diff -u cases/outbox-process-1.expected.txt cases/outbox-process-1.actual.txt

    java -cp "out:lib/sqlite-jdbc-3.46.1.3.jar" lab.Main file fake-accept < cases/outbox-process-2.in > cases/outbox-process-2.actual.txt
    diff -u cases/outbox-process-2.expected.txt cases/outbox-process-2.actual.txt

Inbox 三组：

    java -cp "out:lib/sqlite-jdbc-3.46.1.3.jar" lab.Main fake-accept < cases/inbox-basic.in > cases/inbox-basic.actual.txt
    diff -u cases/inbox-basic.expected.txt cases/inbox-basic.actual.txt

    java -cp "out:lib/sqlite-jdbc-3.46.1.3.jar" lab.Main fake-accept < cases/inbox-duplicate.in > cases/inbox-duplicate.actual.txt
    diff -u cases/inbox-duplicate.expected.txt cases/inbox-duplicate.actual.txt

    java -cp "out:lib/sqlite-jdbc-3.46.1.3.jar" lab.Main fake-accept < cases/inbox-reject.in > cases/inbox-reject.actual.txt
    diff -u cases/inbox-reject.expected.txt cases/inbox-reject.actual.txt

Workflow 两组：

    java -cp "out:lib/sqlite-jdbc-3.46.1.3.jar" lab.Main fake-accept < cases/workflow-basic.in > cases/workflow-basic.actual.txt
    diff -u cases/workflow-basic.expected.txt cases/workflow-basic.actual.txt

    java -cp "out:lib/sqlite-jdbc-3.46.1.3.jar" lab.Main fake-accept < cases/workflow-reject.in > cases/workflow-reject.actual.txt
    diff -u cases/workflow-reject.expected.txt cases/workflow-reject.actual.txt

SQLite 三组：

    rm -rf data
    java -cp "out:lib/sqlite-jdbc-3.46.1.3.jar" lab.Main sqlite fake-accept < cases/sqlite-basic-1.in > cases/sqlite-basic-1.actual.txt
    diff -u cases/sqlite-basic-1.expected.txt cases/sqlite-basic-1.actual.txt

    java -cp "out:lib/sqlite-jdbc-3.46.1.3.jar" lab.Main sqlite fake-accept < cases/sqlite-basic-2.in > cases/sqlite-basic-2.actual.txt
    diff -u cases/sqlite-basic-2.expected.txt cases/sqlite-basic-2.actual.txt

    java -cp "out:lib/sqlite-jdbc-3.46.1.3.jar" lab.Main sqlite fake-accept < cases/sqlite-reject.in > cases/sqlite-reject.actual.txt
    diff -u cases/sqlite-reject.expected.txt cases/sqlite-reject.actual.txt

Stats 两组：

    java -cp "out:lib/sqlite-jdbc-3.46.1.3.jar" lab.Main fake-accept < cases/stats-basic.in > cases/stats-basic.actual.txt
    diff -u cases/stats-basic.expected.txt cases/stats-basic.actual.txt

    java -cp "out:lib/sqlite-jdbc-3.46.1.3.jar" lab.Main fake-accept < cases/stats-mixed.in > cases/stats-mixed.actual.txt
    diff -u cases/stats-mixed.expected.txt cases/stats-mixed.actual.txt

Health 两组：

    java -cp "out:lib/sqlite-jdbc-3.46.1.3.jar" lab.Main fake-accept < cases/health-basic.in > cases/health-basic.actual.txt
    diff -u cases/health-basic.expected.txt cases/health-basic.actual.txt

    java -cp "out:lib/sqlite-jdbc-3.46.1.3.jar" lab.Main fake-timeout-created < cases/health-warn.in > cases/health-warn.actual.txt
    diff -u cases/health-warn.expected.txt cases/health-warn.actual.txt
Readonly 两组：

    java -cp "out:lib/sqlite-jdbc-3.46.1.3.jar" lab.Main readonly < cases/readonly-basic.in > cases/readonly-basic.actual.txt
    diff -u cases/readonly-basic.expected.txt cases/readonly-basic.actual.txt

    java -cp "out:lib/sqlite-jdbc-3.46.1.3.jar" lab.Main readonly < cases/readonly-write-rejected.in > cases/readonly-write-rejected.actual.txt
    diff -u cases/readonly-write-rejected.expected.txt cases/readonly-write-rejected.actual.txt
Migration 两组（需要依次运行，共享数据库）：

    rm -rf data
    java -cp "out:lib/sqlite-jdbc-3.46.1.3.jar" lab.Main sqlite fake-accept < cases/migration-basic.in > cases/migration-basic.actual.txt
    diff -u cases/migration-basic.expected.txt cases/migration-basic.actual.txt

    java -cp "out:lib/sqlite-jdbc-3.46.1.3.jar" lab.Main sqlite fake-accept < cases/migration-persistence.in > cases/migration-persistence.actual.txt
    diff -u cases/migration-persistence.expected.txt cases/migration-persistence.actual.txt
业务检查：

    java -cp "out:lib/sqlite-jdbc-3.46.1.3.jar" lab.RegistryChecks > cases/checks.actual.txt

并发检查：

    java -cp "out:lib/sqlite-jdbc-3.46.1.3.jar" lab.ConcurrencyChecks > cases/concurrency.actual.txt
    diff -u cases/concurrency.expected.txt cases/concurrency.actual.txt

详细验证记录见 `cases/verification.md`。

## 数据边界

- 内存模式：数据仅在同一进程内有效，退出后清空
- 文件模式：数据保存在 `data/` 目录，重启后仍存在
- SQLite 模式：数据保存在 `data/logistics.db`
- 单线程处理输入，写操作内部使用 synchronized
- 不连接真实系统
- 不使用真实 M4

## 当前完成范围

- 第一阶段：点位登记与查询
- 第二阶段：搬运申请登记、查询与重复识别
- 第三阶段：自动检查与边界样例
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
## 文档

`docs/` 目录包含 v0.1 草案：

- `docs/schema-v0.1.md` — 数据 Schema 草案
- `docs/traceability.md` — Requirement 追踪表
- `docs/evidence-template.md` — Evidence Bundle 模板
- `docs/open-items.md` — Open Item 与验证债务清单
- `docs/facts-evidence-levels.md` — 当前事实与证据等级整理
- `docs/security-baseline.md` — Security 基线草案
- `docs/observability.md` — Observability 草案
- `docs/summary.md` — 练习项目总结
