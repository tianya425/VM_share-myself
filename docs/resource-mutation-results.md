# 资源运行时变异测试记录（RES-MUT-01..06）

| 文档属性 | 内容 |
|---|---|
| 版本 | V1.0 |
| 日期 | 2026-10-24 |
| 基线 commit | `13d4e58`（PR4 合并后的 main） |
| 分支 | `sim-02-resource-runtime`（正式分支一行未改坏） |
| 结论 | 6 / 6 项变异都让目标检查以非零退出失败；每项都留下真实的 Oracle 失败行；恢复后全量脚本重新 PASS |

## 方法

1. 每项变异都在 `/tmp` 下的临时副本中进行：`rsync -a --exclude .git --exclude out --exclude build <仓库>/ <临时副本>/`，正式工作区不参与编译或运行。
2. 只改一个逻辑点（`temporaryFiles` 见下），改法见 `temporaryDiffSummary`。
3. 副本内重新编译 `out/main` 与 `out/test`（`--release 21 -encoding UTF-8`，class `major version 65`）。
4. 在副本根目录运行目标检查类，记录**真实进程退出码**与**首条 FAIL 行**。
5. 对资源场景变异额外用 `SimulationMain run` 生成证据，摘出 `oracle-results.tsv` 中对应的 `ORC-RES-SYS-*` FAIL 行，证明失败来自资源 Oracle 的真实计算，而不是测试断言自说自话。
6. `rm -rf` 副本并复核不存在（`temporaryCopyRemoved=true`）。
7. 正式分支不改动；全部变异结束后在正式分支上重跑 `bash scripts/verify-simulation.sh`。

> 注：`temporaryDiffLines` 是 `diff -u` 中增删行的合计，行数不等于改动语义的大小。

## RES-MUT-01 允许同一资源同时两个 HELD

```
mutationId=RES-MUT-01
temporaryFiles=src/main/java/lab/simulation/resource/VirtualResourceWorld.java
temporaryDiffSummary=diff_lines=1（request 的 canHold 去掉 !hasHeld(resourceId)：资源被占用仍可再 HELD）
targetClass=lab.simulation.resource.ResourceScenarioChecks
expectedFailingCase=RSC-01
actualExitCode=1
actualFailureLine=RSC-01 FAIL: scenarios/resource/sim-102-queue-order.scenario runStatus=FAILED firstFailure=ORC-112
probeScenario=scenarios/resource/sim-102-queue-order.scenario
oracleFailureRow=ORC-RES-SYS-001	SINGLE_CAPACITY	FAIL	maxHeldPerResource<=1	maxHeldPerResource=3	resource=R01 同时 HELD=3
temporaryCopyRemoved=true
```

-> 单容量不是靠 `HELD_COUNT` 装饰：ORC-RES-SYS-001 在同一观察点算出 3 个 HELD，RSC-01 红。

## RES-MUT-02 队列只按 applicationId 排序

```
mutationId=RES-MUT-02
temporaryFiles=src/main/java/lab/simulation/resource/ResourceWorldSnapshot.java
temporaryDiffSummary=diff_lines=5（sortedReservations 的 offset→logicalSequence→applicationId 排序改成只按 applicationId）
targetClass=lab.simulation.resource.ResourceScenarioChecks
expectedFailingCase=RSC-01
actualExitCode=1
actualFailureLine=RSC-01 FAIL: scenarios/resource/sim-102-queue-order.scenario runStatus=FAILED firstFailure=ORC-114
probeScenario=scenarios/resource/sim-102-queue-order.scenario
oracleFailureRow=ORC-RES-SYS-002	QUEUE_ORDER	FAIL	queueKeyAscending,promoteHeadOnly	waitingPairs=2,inversions=2,promotions=1,skippedHead=0	resource=R01 队列逆序 APP-010 -> APP-020
temporaryCopyRemoved=true
```

-> 请求顺序是 APP-030 → APP-020 → APP-010，按 appId 排会得到 APP-010 → APP-020；QUEUE_ORDER 与 ORC-RES-SYS-002 同时红。

## RES-MUT-03 重复请求重排（IDEMPOTENT 却改排序键）

```
mutationId=RES-MUT-03
temporaryFiles=src/main/java/lab/Registry.java
temporaryDiffSummary=diff_lines=9（重复 request 重建 Reservation，用新 offset/logicalSequence 提交，但仍返回 IDEMPOTENT）
targetClass=lab.simulation.resource.ResourceScenarioChecks
expectedFailingCase=RSC-01
actualExitCode=1
actualFailureLine=RSC-01 FAIL: scenarios/resource/sim-104-idempotent-request.scenario runStatus=FAILED firstFailure=ORC-RES-SYS-004
probeScenario=scenarios/resource/sim-104-idempotent-request.scenario
oracleFailureRow=ORC-RES-SYS-004	IDEMPOTENT_STABLE	FAIL	idempotentUnchanged	idempotentRequests=1,mismatches=1	app=APP-001 at EVT-008 before=HELD@2000/70 after=HELD@3000/80
temporaryCopyRemoved=true
```

-> 幂等决策本身仍返回 `IDEMPOTENT`；只有把请求前后快照逐字段比对，才看得出排序键被改了。

## RES-MUT-04 disable 驱逐 holder

```
mutationId=RES-MUT-04
temporaryFiles=src/main/java/lab/simulation/resource/VirtualResourceWorld.java
temporaryDiffSummary=diff_lines=13（setAvailability DISABLED 时把该资源 HELD 全部改成 CANCELLED）
targetClass=lab.simulation.resource.ResourceScenarioChecks
expectedFailingCase=RSC-01
actualExitCode=1
actualFailureLine=RSC-01 FAIL: scenarios/resource/sim-105-disable-enable.scenario runStatus=FAILED firstFailure=RESERVATION_TERMINAL
probeScenario=scenarios/resource/sim-105-disable-enable.scenario
oracleFailureRow=ORC-RES-SYS-005	DISABLE_KEEPS_HOLDER	FAIL	holdersUnchangedOnDisable	disableEvents=1,holderEvictions=1	resource=R01 at EVT-009 holders [APP-001] -> []
temporaryCopyRemoved=true
```

-> 驱逐后的 holder 在后续 release 时变成 `RESERVATION_TERMINAL`；ORC-RES-SYS-005 逐事件比较 disable 前后的持有者集合，直接指出 `[APP-001] -> []`。

## RES-MUT-05 release 先发布后提交

```
mutationId=RES-MUT-05
temporaryFiles=src/main/java/lab/Registry.java
temporaryDiffSummary=diff_lines=1（releaseReservation 先把新快照写进内存 resourceWorld，再 commitState）
targetClass=lab.simulation.resource.ResourceWorldChecks
expectedFailingCase=RW-24
actualExitCode=1
actualFailureLine=RW-24 FAIL: release 提交失败后 holder 状态=RELEASED 期望=HELD
temporaryCopyRemoved=true
```

-> 提交失败后内存已发布 RELEASED，出现幽灵状态；RW-24 的提交失败注入恰好覆盖 release 路径。

## RES-MUT-06 restart 不加载 reservations

```
mutationId=RES-MUT-06
temporaryFiles=src/main/java/lab/Registry.java
temporaryDiffSummary=diff_lines=4（Registry 构造时从快照重建资源世界但丢弃 reservations）
targetClass=lab.simulation.resource.ResourceScenarioChecks
expectedFailingCase=RSC-01
actualExitCode=1
actualFailureLine=RSC-01 FAIL: scenarios/resource/sim-106-restart-recovery.scenario runStatus=FAILED firstFailure=RESERVATION_NOT_FOUND
probeScenario=scenarios/resource/sim-106-restart-recovery.scenario
oracleFailureRow=ORC-RES-SYS-006	RESTART_REPLAY	FAIL	restartPairs=2,mismatches=0	restartPairs=2,mismatches=1	eventId=EVT-010 before=0f57bd2ee8c5124bfa996afb495ad5b5a4067697ff5308dc92c25ac53f5ebe66 after=49a3472b2538b54e66329d331dd1de7738faf468a1fa402047c884c8ffe0dbc5
temporaryCopyRemoved=true
```

-> 第一次重启前后摘要不同，第二次重启前后都为空所以相同，因此是 `restartPairs=2,mismatches=1`，不是只写 true；后续 release 找不到预约，场景首个失败为 `RESERVATION_NOT_FOUND`。

## 干净源码全量复验

正式分支（`sim-02-resource-runtime`）未做任何变异；六项结束后重跑 35 步脚本：

```
CHECKS_PASSED=279
CONCURRENCY_CHECKS_PASSED=8
RELIABILITY_R1_CASES_PASSED=14
ORIGINAL_CASES_OK total=30 passed=30
SCENARIO_PARSER_CASES_PASSED=16
SIMULATION_RUNTIME_CASES_PASSED=18
RESOURCE_WORLD_CASES_PASSED=24
RESOURCE_STORAGE_CASES_PASSED=6
RESOURCE_PARSER_CASES_PASSED=7
RESOURCE_SCENARIO_CASES_PASSED=10
REPLAY_EVIDENCE_R1_CASES_PASSED=12
RESOURCE_REPLAY=6
RESOURCE_INVALID_SCENARIOS=5
SIMULATION_RESOURCE_VERIFICATION_PASS originalCases=30 baseValidScenarios=3 baseInvalidScenarios=3 resourceValidScenarios=6 resourceInvalidScenarios=5 resourceReplay=6 realExternalWrites=0
```
