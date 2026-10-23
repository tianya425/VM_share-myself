# 验证记录

## 环境

- Ubuntu 24 虚拟机
- OpenJDK 21.0.12
- javac 21.0.12
- UTF-8

## 编译

    javac --release 21 -encoding UTF-8 -d out src/main/java/lab/*.java

结果：COMPILE_OK

## 命令样例

四组样例均通过，diff 无差异：

| 样例 | 输入 | 预期 | 结果 |
|---|---|---|---|
| points | cases/points.in | cases/points.expected.txt | PASS |
| happy-path | cases/happy-path.in | cases/happy-path.expected.txt | PASS |
| rejection-and-replay | cases/rejection-and-replay.in | cases/rejection-and-replay.expected.txt | PASS |
| boundaries | cases/boundaries.in | cases/boundaries.expected.txt | PASS |

## 业务检查

    java -cp out lab.RegistryChecks > cases/checks.actual.txt

结果：CHECKS_PASSED=70

覆盖 R01—R16：

- R01 新 Registry 为空
- R02 点位字段与排序
- R03 重复点位拒绝，原名不变
- R04 申请登记新增
- R05 同申请重复返回原记录
- R06 同编号不同起终点冲突
- R07 不同编号相同路线两条独立申请
- R08 未知点先报起点
- R09 同点先看存在性
- R10 查询不存在返回空
- R11 编号格式边界
- R12 编号长度 1 和 32
- R13 名称长度 0、40、41 和含空白
- R14 编号格式先于冲突判断
- R15 申请列表按 applicationId 排序
- R16 列表快照不可修改

## 制表符输入

    printf 'point\tlist\nexit\n' | java -cp out lab.Main

预期：

    POINTS 0
    BYE

## EOF 行为

cases/boundaries.in 末尾不含 exit，程序在输入结束时直接退出且不输出 BYE。

## 故意破坏检查

目的：确认 RegistryChecks 能检出真实缺陷，而不是全部通过的空壳。

步骤：

1. 复制正确工作目录到独立临时副本 `~/mutation-check`。
2. 在临时副本中只改 `registerPoint` 的重复判断，使重复 P01 能够覆盖原值。
3. 在临时副本重新编译并运行 RegistryChecks。
4. 预期 R03 相关检查失败，进程非零退出。
5. 记录实际输出。
6. 删除临时副本，返回正确工作目录重新验证通过。

### 实际执行记录

临时副本：`~/mutation-check`

临时改动：

    if (points.containsKey(pointId)) {
        // 故意破坏：不抛异常，允许覆盖
    }

执行命令：

    cd ~/mutation-check
    rm -rf out
    mkdir -p out
    javac --release 21 -encoding UTF-8 -d out src/main/java/lab/*.java
    java -cp out lab.RegistryChecks

实际结果：

    Exception in thread "main" java.lang.AssertionError: FAILED: R03 same name (no exception)
        at lab.RegistryChecks.expectError(RegistryChecks.java:280)
        at lab.RegistryChecks.r03(RegistryChecks.java:56)
        at lab.RegistryChecks.main(RegistryChecks.java:13)
    CHECK_EXIT=1

正确工作目录重新验证：

    cd ~/verify-02/logistics-intake-practice-main
    rm -rf out
    mkdir -p out
    javac --release 21 -encoding UTF-8 -d out src/main/java/lab/*.java
    java -cp out lab.RegistryChecks

实际结果：

    CHECKS_PASSED=70
    CHECK_EXIT=0

结论：故意破坏能被检出，正式工作目录未被污染，正确实现重新通过。

## 临时副本清理

    rm -rf ~/mutation-check
    ls ~ | grep mutation

结果：无输出，临时副本已删除。

## 第五阶段：状态机

### 编译

    javac --release 21 -encoding UTF-8 -d out src/main/java/lab/*.java

结果：COMPILE_OK

### RegistryChecks

    java -cp out lab.RegistryChecks

结果：CHECKS_PASSED=91

新增 R17—R27：

- R17 新申请初始状态 CREATED
- R18 CREATED → DISPATCHED 成功
- R19 CREATED → EXECUTING 被拒绝，状态不变
- R20 CREATED → COMPLETED 被拒绝
- R21 DISPATCHED → EXECUTING 成功
- R22 DISPATCHED → COMPLETED 被拒绝
- R23 EXECUTING → COMPLETED 成功
- R24 COMPLETED 之后所有转换被拒绝
- R25 CANCELLED 之后所有转换被拒绝
- R26 CREATED → CANCELLED 成功
- R27 对未知申请的操作返回 UNKNOWN_APPLICATION

### 命令样例

| 样例 | 结果 |
|---|---|
| points | PASS |
| happy-path | PASS |
| rejection-and-replay | PASS |
| boundaries | PASS |
| persistence-1 | PASS |
| persistence-2 | PASS |
| state-machine | PASS |

### 持久化 + 状态

第一次运行（file 模式）：

    application add APP-001 P01 P02
    application dispatch APP-001
    application execute APP-001

文件内容：

    A|APP-001|P01|P02|EXECUTING

第二次运行（file 模式）：

    application show APP-001       → APPLICATION APP-001 P01 P02 EXECUTING
    application complete APP-001    → OK APPLICATION_COMPLETED APP-001
    application show APP-001       → APPLICATION APP-001 P01 P02 COMPLETED
    application cancel APP-001      → ERR INVALID_STATE_TRANSITION APP-001

## 第六阶段：幂等与并发安全

### 修改

`Registry.java` 中三处关键写方法增加 `synchronized`：

- `registerPoint`
- `registerApplication`
- `transition`

### 新增

`ConcurrencyChecks.java`，使用 10 个线程并发提交同一个 applicationId。

检查项：

- R29 恰好一个线程创建成功，其余为重放
- R30 存储中恰好 1 条记录
- R31 返回的 applicationId 正确
- R32 存储的起终点与初始状态正确

### 编译

    javac --release 21 -encoding UTF-8 -d out src/main/java/lab/*.java

结果：COMPILE_OK

### 并发检查

    java -cp out lab.ConcurrencyChecks

结果：CONCURRENCY_CHECKS_PASSED=8

连续 5 次运行均通过。

### RegistryChecks

    java -cp out lab.RegistryChecks

结果：CHECKS_PASSED=91

### 测试敏感性验证

临时副本中移除 `registerApplication` 的 `synchronized`，重新编译并连续运行 5 次。

实际结果：5 次全部失败。

    java.lang.AssertionError: FAILED: R29 exactly one created
    EXIT=1

说明并发测试能检出缺少同步的真实缺陷。正确版本恢复后连续 5 次通过。

## 第七阶段：Fake 外部执行系统

### 新增

- `RcsExecutionPort.java` — IDS 与外部执行系统之间的合同
- `ExternalOrderResult.java` — 规范化返回结果
- `FakeRcsAdapter.java` — 虚拟 M4，四种模式

### 修改

- `ApplicationState.java` — 增加 UNKNOWN 状态
- `TransferApplication.java` — 增加 externalOrderId 字段
- `Registry.java` — dispatch 与 query 通过 RcsExecutionPort
- `Main.java` — fake 参数解析、query 命令、show 输出第五列
- `FileStorage.java` — 文件格式增加 externalOrderId 列

### Fake 四种模式

- ACCEPT：返回 ACCEPTED，记录外部订单号
- REJECT：返回 REJECTED
- TIMEOUT_BUT_CREATED：返回 TIMEOUT，但外部已记录订单
- TIMEOUT_NOT_CREATED：返回 TIMEOUT，外部未记录

### 编译

    javac --release 21 -encoding UTF-8 -d out src/main/java/lab/*.java

结果：COMPILE_OK

### RegistryChecks

    java -cp out lab.RegistryChecks

结果：CHECKS_PASSED=111

新增 R33—R40：

- R33 ACCEPT 模式：状态变 DISPATCHED，externalOrderId 记录
- R34 REJECT 模式：抛 RCS_REJECTED，状态不变
- R35 TIMEOUT_BUT_CREATED：dispatch 后 UNKNOWN，query 后 DISPATCHED
- R36 TIMEOUT_NOT_CREATED：dispatch 后 UNKNOWN，query 抛 EXTERNAL_ORDER_NOT_FOUND，状态保持 UNKNOWN
- R37 对已 DISPATCHED 的申请再次 dispatch 被拒绝
- R38 对 CREATED 的申请 query 被拒绝
- R39 UNKNOWN 可取消
- R40 对未知申请 dispatch / query 返回 UNKNOWN_APPLICATION

### 命令样例

| 样例 | 结果 |
|---|---|
| points | PASS |
| happy-path | PASS |
| rejection-and-replay | PASS |
| boundaries | PASS |
| state-machine | PASS |
| persistence-1 | PASS |
| persistence-2 | PASS |
| fake-accept | PASS |
| fake-reject | PASS |
| fake-timeout-created | PASS |
| fake-timeout-not-created | PASS |

### Timeout 对账演示

TIMEOUT_BUT_CREATED 模式：

    application dispatch APP-001   → OK APPLICATION_UNKNOWN APP-001
    application show APP-001        → APPLICATION APP-001 P01 P02 UNKNOWN -
    application query APP-001       → OK APPLICATION_DISPATCHED APP-001
    application show APP-001        → APPLICATION APP-001 P01 P02 DISPATCHED EXT-APP-001

TIMEOUT_NOT_CREATED 模式：

    application dispatch APP-001   → OK APPLICATION_UNKNOWN APP-001
    application query APP-001       → ERR EXTERNAL_ORDER_NOT_FOUND APP-001
    application show APP-001        → APPLICATION APP-001 P01 P02 UNKNOWN -
    application cancel APP-001      → OK APPLICATION_CANCELLED APP-001

## 第八阶段：Outbox 可靠意图

### 新增

- `OutboxStatus.java` — PENDING / SENT / FAILED
- `OutboxEntry.java` — Outbox 记录
- `cases/outbox-basic.*` — ACCEPT 模式下的 Outbox 样例
- `cases/outbox-process-1.*` / `outbox-process-2.*` — 两阶段恢复样例

### 修改

- `Storage.java` — 增加 loadOutbox / saveOutbox
- `MemoryStorage.java` — 增加 Outbox 内存实现
- `FileStorage.java` — 增加 data/outbox.txt 持久化
- `Registry.java` — dispatch 先写 Outbox，新增 listOutbox / processOutbox
- `Main.java` — 新增 outbox list / outbox process 命令
- `README.md` — 补充 Outbox 命令、存储格式、测试方式

### 编译

    javac --release 21 -encoding UTF-8 -d out src/main/java/lab/*.java

结果：COMPILE_OK

### RegistryChecks

    java -cp out lab.RegistryChecks

结果：CHECKS_PASSED=137

新增 R41—R45：

- R41 ACCEPT：Outbox SENT，externalOrderId 记录，attempts=0
- R42 TIMEOUT_BUT_CREATED：Outbox PENDING，attempts=1
- R43 REJECT：Outbox FAILED，application 仍 CREATED
- R44 TIMEOUT 后 process：Outbox 仍 PENDING，attempts 变 2
- R45 模拟进程重启：第二次 Registry 加载 PENDING，process 后恢复 DISPATCHED

### 命令样例

| 样例 | 结果 |
|---|---|
| outbox-basic | PASS |
| outbox-process-1 | PASS |
| outbox-process-2 | PASS |

### Outbox 恢复路径演示

第一次运行（fake-timeout-created）：

    application dispatch APP-001   → OK APPLICATION_UNKNOWN APP-001
    outbox list                     → OUTBOX 1，PENDING，attempts=1

文件：

    O|OBX-APP-001|APP-001|P01|P02|PENDING||1

第二次运行（fake-accept）：

    outbox list                     → OUTBOX 1，PENDING，attempts=1
    outbox process                  → OK OUTBOX_PROCESSED 1
    outbox list                     → OUTBOX 1，SENT，EXT-APP-001，attempts=1
    application show APP-001        → DISPATCHED，EXT-APP-001

文件：

    O|OBX-APP-001|APP-001|P01|P02|SENT|EXT-APP-001|1
    A|APP-001|P01|P02|DISPATCHED|EXT-APP-001

## 第十阶段：Inbox 事件接收

### 新增

- `InboxStatus.java` — PENDING / PROCESSED / REJECTED
- `InboxEntry.java` — Inbox 记录
- `cases/inbox-basic.*` — 正常 callback 推进状态
- `cases/inbox-duplicate.*` — 重复 callback 幂等
- `cases/inbox-reject.*` — 乱序 + 未知申请

### 修改

- `Storage.java` — 增加 loadInbox / saveInbox
- `MemoryStorage.java` — 增加 Inbox 内存实现
- `FileStorage.java` — 增加 data/inbox.txt 持久化
- `Registry.java` — 增加 receiveCallback / listInbox
- `Main.java` — 增加 application callback 和 inbox list 命令
- `README.md` — 补充 Inbox 命令、存储格式、测试方式

### 编译

    javac --release 21 -encoding UTF-8 -d out src/main/java/lab/*.java

结果：COMPILE_OK

### RegistryChecks

    java -cp out lab.RegistryChecks

结果：CHECKS_PASSED=152

新增 R46—R52：

- R46 STARTED callback 推进到 EXECUTING
- R47 COMPLETED callback 推进到 COMPLETED
- R48 重复 callback 幂等，Inbox 只有 1 条
- R49 乱序 callback 被 REJECTED，状态不变
- R50 未知申请 callback 被 REJECTED
- R51 非法 eventType 被拒绝
- R52 FAILED callback 推进到 CANCELLED

### 命令样例

| 样例 | 结果 |
|---|---|
| inbox-basic | PASS |
| inbox-duplicate | PASS |
| inbox-reject | PASS |

### callback 处理路径

正常：

    application dispatch APP-001       → DISPATCHED
    application callback APP-001 STARTED   → EXECUTING
    application callback APP-001 COMPLETED → COMPLETED

幂等：

    重复 callback APP-001 STARTED
    → OK CALLBACK_DUPLICATE
    → Inbox 仍只有 1 条

乱序：

    DISPATCHED 状态收到 COMPLETED callback
    → ERR CALLBACK_INVALID_STATE_TRANSITION
    → 状态不变，Inbox 记录 REJECTED

未知：

    callback APP-999 STARTED
    → ERR CALLBACK_UNKNOWN_APPLICATION
    → Inbox 记录 REJECTED，原因 UNKNOWN_APPLICATION

## 第十一阶段：Workflow 骨架

### 新增

- `WorkflowState.java` — RUNNING / COMPLETED / CANCELLED / FAILED
- `WorkflowStepState.java` — PENDING / RUNNING / COMPLETED / FAILED
- `WorkflowStep.java` — 工作流步骤
- `WorkflowInstance.java` — 工作流实例
- `cases/workflow-basic.*` — 正常推进流程
- `cases/workflow-reject.*` — 拒绝场景

### 修改

- `Storage.java` — 增加 loadWorkflows / saveWorkflows
- `MemoryStorage.java` — Workflow 内存实现
- `FileStorage.java` — 增加 data/workflows.txt
- `Registry.java` — 增加 startWorkflow / advanceWorkflow / findWorkflow / listWorkflows
- `Main.java` — 增加 workflow start / advance / show / list 命令
- `README.md` — 补充 Workflow 说明

### 编译

    javac --release 21 -encoding UTF-8 -d out src/main/java/lab/*.java

结果：COMPILE_OK

### RegistryChecks

    java -cp out lab.RegistryChecks

结果：CHECKS_PASSED=174

新增 R53—R60：

- R53 启动 Workflow，状态 RUNNING，3 步 PENDING
- R54 对未知申请启动 Workflow 被拒绝
- R55 对同一申请重复启动 Workflow 被拒绝
- R56 未启动就 advance 被拒绝
- R57 advance 一次，步骤 0 变 COMPLETED，索引变 1
- R58 advance 三次，Workflow 变 COMPLETED，索引 3
- R59 完成后再 advance 被拒绝
- R60 findWorkflow / listWorkflows 正确

### 命令样例

| 样例 | 结果 |
|---|---|
| workflow-basic | PASS |
| workflow-reject | PASS |

### Workflow 流程

固定 3 步：

    DISPATCH → EXECUTE → COMPLETE

工作流状态：

    RUNNING → COMPLETED

演示路径：

    workflow start APP-001     → RUNNING 0/3
    workflow advance APP-001   → RUNNING 1/3
    workflow advance APP-001   → RUNNING 2/3
    workflow advance APP-001   → COMPLETED 3/3

## 第十三阶段：SQLite 持久化

### 新增

- `SqliteStorage.java` — SQLite 实现，五张表
- `lib/sqlite-jdbc-3.46.1.3.jar` — SQLite JDBC 驱动
- `cases/sqlite-basic-1.*` / `sqlite-basic-2.*` — 两次运行验证跨进程持久化
- `cases/sqlite-reject.*` — 同一数据库下的拒绝场景

### 修改

- `Main.java` — 支持 `sqlite` 参数
- `.gitignore` — 增加 `*.db`
- `README.md` — 补充 SQLite 说明
- `RegistryChecks.java` — 新增 R61—R65

### 表结构

```sql
points (point_id PRIMARY KEY, name)
applications (application_id PRIMARY KEY, from_point_id, to_point_id, state, external_order_id)
outbox (entry_id PRIMARY KEY, application_id, from_point_id, to_point_id, status, external_order_id, attempts)
inbox (entry_id PRIMARY KEY, application_id, event_type, status, reason)
workflows (instance_id PRIMARY KEY, application_id, state, current_step_index, steps)
