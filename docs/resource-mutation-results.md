# 资源运行时变异测试记录（RES-MUT-01..06）

| 文档属性 | 内容 |
|---|---|
| 版本 | V2.0 |
| 日期 | 2026-10-30 |
| 基线 commit | `de1ad7f`（C5 完成后的 HEAD） |
| 分支 | `nzy/stu-sim-02-r1-contract-closeout` |
| 结论 | 6 / 6 项变异都被目标检查以退出码 1 检出；每项随后删除临时 clone；主仓库工作区保持 clean |

## 方法

1. 每项 mutation 都执行 `git clone --no-hardlinks` 到独立临时目录，并 `git switch --detach <BASE>`；不复用注入过错误的目录。
2. 只改一个逻辑点（见各项 `temporaryDiffSummary`），然后在 clone 内重新编译 `out/main` 与 `out/test`（`--release 21 -encoding UTF-8`）。
3. 运行目标检查类，记录**真实退出码**与**首条失败行**。
4. 结束后 `rm -rf` 临时 clone，复核不存在；主仓库不使用 `git reset --hard`，工作区保持 clean。
5. 统一命令：`java -cp "out/main:out/test:lib/sqlite-jdbc-3.46.1.3.jar" lab.simulation.resource.ResourceWorldChecks`

## RES-MUT-01 允许同一资源两个 HELD

```text
mutationId=RES-MUT-01
temporaryFiles=src/main/java/lab/Registry.java
temporaryDiffSummary=diff_lines=3（request 的 canHold 去掉 !current.hasHeld(resourceId)）
command=java -cp out/main:out/test:lib/sqlite-jdbc-3.46.1.3.jar lab.simulation.resource.ResourceWorldChecks
expectedFailingTest=RW-12
actualFailingTest=RW-12 FAIL: RegistryException: RESOURCE_STATE_INVALID rule=R13_RESOURCE_HELD_RESERVATION;entity=R01;field=reservationId;actual=RSV-APP-001,RSV-APP-002
exitCode=1
restoredCleanTree=true
```

备注：C3 起统一校验器在提交前生效，因此双 HELD 被 `RESOURCE_STATE_INVALID` 拒绝，RW-12 仍以非零退出检出；ORC-RES-SYS-001 的负向反例由 RSC-09 覆盖。

## RES-MUT-02 队列只按 applicationId 排序

```text
mutationId=RES-MUT-02
temporaryFiles=src/main/java/lab/simulation/resource/ResourceWorldSnapshot.java
temporaryDiffSummary=diff_lines=5（sortedReservations 从 offset→logicalSequence→applicationId 改成只按 applicationId）
command=java -cp out/main:out/test:lib/sqlite-jdbc-3.46.1.3.jar lab.simulation.resource.ResourceWorldChecks
expectedFailingTest=RW-13
actualFailingTest=RW-13 FAIL: RegistryException: RESOURCE_STATE_INVALID rule=R15_WAITING_QUEUE_ORDER;entity=R01;field=queueOrder;actual=RSV-APP-001>RSV-APP-002
exitCode=1
restoredCleanTree=true
```

## RES-MUT-03 重复请求更新时间并重排

```text
mutationId=RES-MUT-03
temporaryFiles=src/main/java/lab/Registry.java
temporaryDiffSummary=diff_lines=15（IDEMPOTENT 分支用新 offset/seq 重建 Reservation 并提交，仍返回 IDEMPOTENT）
command=java -cp out/main:out/test:lib/sqlite-jdbc-3.46.1.3.jar lab.simulation.resource.ResourceWorldChecks
expectedFailingTest=RW-14
actualFailingTest=RW-14 FAIL: 排序键被改了：offset=200 seq=30 期望=100/20
exitCode=1
restoredCleanTree=true
```

## RES-MUT-04 disable 时驱逐 holder

```text
mutationId=RES-MUT-04
temporaryFiles=src/main/java/lab/simulation/resource/VirtualResourceWorld.java
temporaryDiffSummary=diff_lines=9（setAvailability DISABLED 时把该资源 HELD 改成 CANCELLED）
command=java -cp out/main:out/test:lib/sqlite-jdbc-3.46.1.3.jar lab.simulation.resource.ResourceWorldChecks
expectedFailingTest=RW-20
actualFailingTest=RW-20 FAIL: disable 后 HELD 列表=[] 期望=[APP-009]（不得驱逐）
exitCode=1
restoredCleanTree=true
```

## RES-MUT-05 release 先发布内存再 commit

```text
mutationId=RES-MUT-05
temporaryFiles=src/main/java/lab/Registry.java
temporaryDiffSummary=diff_lines=1（releaseReservation 先把新快照赋给内存 resourceWorld，再 commitWorld）
command=java -cp out/main:out/test:lib/sqlite-jdbc-3.46.1.3.jar lab.simulation.resource.ResourceWorldChecks
expectedFailingTest=RW-24
actualFailingTest=RW-24 FAIL: release 提交失败后 holder 状态=RELEASED 期望=HELD
exitCode=1
restoredCleanTree=true
```

## RES-MUT-06 restart 不加载 Reservation

```text
mutationId=RES-MUT-06
temporaryFiles=src/main/java/lab/Registry.java
temporaryDiffSummary=diff_lines=6（构造器从快照重建资源世界时把 reservations 置为空列表）
command=java -cp out/main:out/test:lib/sqlite-jdbc-3.46.1.3.jar lab.simulation.resource.ResourceWorldChecks
expectedFailingTest=RW-23
actualFailingTest=RW-23 FAIL: WORLD_RESTART 前后规范快照不一致
exitCode=1
restoredCleanTree=true
```

## 主仓库复验

六项 mutation 全部结束后，主仓库未保留任何错误代码，工作区 clean；按 `docs/simulation-handoff.md` 的唯一命令重新执行完整脚本，终点行：

```text
SIMULATION_RESOURCE_VERIFICATION_PASS originalCases=30 baseValidScenarios=3 baseInvalidScenarios=3 resourceValidScenarios=6 resourceInvalidScenarios=5 resourceReplay=6 realExternalWrites=0
```

FINAL_SOURCE_IDENTITY=BOUND_BY_EXTERNAL_DELIVERY_RECEIPT
DELIVERY_RECEIPT=delivery-receipt.txt
