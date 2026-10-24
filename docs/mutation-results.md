# 变异测试记录（MUT-01..05）

| 文档属性 | 内容 |
|---|---|
| 版本 | V1.0 |
| 日期 | 2026-10-24 |
| 基线 commit | `88bf021`（PR2 合并后的 main） |
| 分支 | `r1-verification`（正式分支一行未改坏） |
| 结论 | 5 / 5 项变异都让对应检查以非零退出失败；恢复后全量脚本重新 PASS |

## 方法

1. 每项变异都在 `mktemp -d` 临时副本中进行：`git -C <repo> archive 88bf021 | tar -x -C <tmp>`，正式工作区不参与。
2. 只改副本里的一个文件（`temporaryFile`），改法见 `temporaryDiffSummary` 与下方说明。
3. 副本内重新编译：`javac --release 21 -encoding UTF-8` 编 `out/main` 与 `out/test`。
4. 在副本根目录运行对应检查类，记录**真实进程退出码**与**首条失败行**。
5. `rm -rf` 副本并用 `[ -d ]` 复核不存在（`temporaryCopyRemoved=true`）。
6. 正式分支不改动；全部变异结束后在正式分支上重跑 `bash scripts/verify-simulation.sh`。

## MUT-01 首错后继续执行

```
mutationId=MUT-01
baselineCommit=88bf021
temporaryFile=src/main/java/lab/simulation/SimulationRuntime.java
temporaryDiffSummary=diff_lines=6（删除 executeEvents 的 firstFailure 跳过分支，首错后继续执行并推进时钟）
command=cd <mktemp -d 副本> && java -cp "out/main:out/test:lib/sqlite-jdbc-3.46.1.3.jar" lab.simulation.SimulationRuntimeChecks
expectedFailingCase=RUN-15
actualExitCode=1
actualFailureLine=RUN-15 FAIL: 状态序列=[EVENT_PASS, EVENT_PASS, EVENT_PASS, EVENT_FAIL, EVENT_PASS, EVENT_PASS, EVENT_PASS]
temporaryCopyRemoved=true
cleanSourceFullVerification=SIMULATION_R1_VERIFICATION_PASS originalCases=30 validScenarios=3 invalidScenarios=3 replay=1 evidenceBundles=5 realExternalWrites=0
```

## MUT-02 FAILED 退出码改 0

```
mutationId=MUT-02
baselineCommit=88bf021
temporaryFile=src/main/java/lab/simulation/SimulationMain.java
temporaryDiffSummary=diff_lines=2（FAILED 分支 System.exit(1) → System.exit(0)）
command=cd <mktemp -d 副本> && java -cp "out/main:out/test:lib/sqlite-jdbc-3.46.1.3.jar" lab.simulation.ReplayEvidenceChecks
expectedFailingCase=RPL-11
actualExitCode=1
actualFailureLine=RPL-11 FAIL: CLI 退出码=0 输出=RUN_FAILED SIM-INVALID-03 UNSCRIPTED_EXTERNAL_CALL
temporaryCopyRemoved=true
cleanSourceFullVerification=SIMULATION_R1_VERIFICATION_PASS originalCases=30 validScenarios=3 invalidScenarios=3 replay=1 evidenceBundles=5 realExternalWrites=0
```

## MUT-03 ORC-SYS-001 固定 PASS

```
mutationId=MUT-03
baselineCommit=88bf021
temporaryFile=src/main/java/lab/simulation/InvariantOracle.java
temporaryDiffSummary=diff_lines=2（checkScriptedExternalCalls 的判定改为 if (false)，ORC-SYS-001 恒为 PASS）
command=cd <mktemp -d 副本> && java -cp "out/main:out/test:lib/sqlite-jdbc-3.46.1.3.jar" lab.simulation.SimulationRuntimeChecks
expectedFailingCase=RUN-13
actualExitCode=1
actualFailureLine=RUN-13 FAIL: ORC-SYS-001=PASS
temporaryCopyRemoved=true
cleanSourceFullVerification=SIMULATION_R1_VERIFICATION_PASS originalCases=30 validScenarios=3 invalidScenarios=3 replay=1 evidenceBundles=5 realExternalWrites=0
```

## MUT-04 manifest 允许少一行

```
mutationId=MUT-04
baselineCommit=88bf021
temporaryFile=src/main/java/lab/simulation/EvidenceBundleValidator.java
temporaryDiffSummary=diff_lines=2（manifest 行数判定 != 6 改为 > 6，5 行不再报 EVIDENCE_MANIFEST_LINES）
command=cd <mktemp -d 副本> && java -cp "out/main:out/test:lib/sqlite-jdbc-3.46.1.3.jar" lab.simulation.ReplayEvidenceChecks
expectedFailingCase=RPL-09
actualExitCode=1
actualFailureLine=RPL-09 FAIL: 改场景并删 manifest 行：code=EVIDENCE_MANIFEST_ORDER，期望 EVIDENCE_MANIFEST_LINES
temporaryCopyRemoved=true
cleanSourceFullVerification=SIMULATION_R1_VERIFICATION_PASS originalCases=30 validScenarios=3 invalidScenarios=3 replay=1 evidenceBundles=5 realExternalWrites=0
```

> 说明：删掉精确行数守卫后，少一行的 manifest 会落到下一层 `EVIDENCE_MANIFEST_ORDER` 被拒；RPL-09 断言的正是稳定 code 必须是 `EVIDENCE_MANIFEST_LINES`，因此该变异确实让 RPL-09 变红，证明这条精确行数检查不是装饰。

## MUT-05 transition 先发布后提交

```
mutationId=MUT-05
baselineCommit=88bf021
temporaryFile=src/main/java/lab/Registry.java
temporaryDiffSummary=diff_lines=2（transition 内 publish(candidate) 与 storage.commitState(...) 交换顺序）
command=cd <mktemp -d 副本> && java -cp "out/main:out/test:lib/sqlite-jdbc-3.46.1.3.jar" lab.ReliabilityCloseoutChecks
expectedFailingCase=R1-REL-03
actualExitCode=1
actualFailureLine=R1-REL-03 FAIL: executeApplication 保存失败后内存状态=EXECUTING 期望回到已提交快照=DISPATCHED
temporaryCopyRemoved=true
cleanSourceFullVerification=SIMULATION_R1_VERIFICATION_PASS originalCases=30 validScenarios=3 invalidScenarios=3 replay=1 evidenceBundles=5 realExternalWrites=0
```

## 干净源码全量复验

正式分支（`r1-verification`）未做任何变异；五项结束后重跑 28 步脚本：

```
CHECKS_PASSED=279
CONCURRENCY_CHECKS_PASSED=8
RELIABILITY_R1_CASES_PASSED=14
SCENARIO_PARSER_CASES_PASSED=16
SIMULATION_RUNTIME_CASES_PASSED=18
REPLAY_EVIDENCE_R1_CASES_PASSED=12
ORIGINAL_CASES_OK total=30 passed=30
REPLAY_MATCH SIM-002 version=1
EVIDENCE_BUNDLES=5
SIMULATION_R1_VERIFICATION_PASS originalCases=30 validScenarios=3 invalidScenarios=3 replay=1 evidenceBundles=5 realExternalWrites=0
```
