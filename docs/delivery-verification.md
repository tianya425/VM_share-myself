# 交付验证记录（A0 基线冻结与缺陷复现）

| 属性 | 内容 |
|---|---|
| 记录对象 | `logistics-intake-practice`（STU-SIM-01 交付版） |
| 记录时间 | 2026-10-23（UTC 2026-10-23T12:03Z 实测） |
| 记录方式 | 全部命令在 Ubuntu 24.04 虚拟机内实际执行，输出原样粘贴 |
| 结论 | 正向路径可运行；失败语义、存储原子性、只读保护、Oracle 信任、证据完整性均未达标 → **GATE-A 未就绪** |

---

## 1. 基线身份（唯一、可复验）

| 项目 | 值 |
|---|---|
| 基线源码包 | `logistics-intake-practice-STU-SIM-01-20261005.zip` |
| ZIP 字节数 | 14,239,772 |
| ZIP 条目数 | 184 |
| ZIP SHA-256 | `1ea82c8de07e8993b59c095dbf650ceb0551198ad5e745badd890ab8cd31a3c7` |
| ZIP comment commit | `2e59f44e5c4f83ce57d4dc6bd1798bcf40aed1cc` |
| 本仓库基线提交 | `bdb30ea`（分支 `stu-sim-01-baseline`，标签 `baseline-r0`） |
| 操作系统 | Ubuntu 24.04.4 LTS（`Linux 6.8.0-147-generic`） |
| Java | `openjdk 21.0.12.1` / `javac 21.0.12.1` |
| 工作目录 | `/home/tianya/logistics-intake-practice-STU-SIM-01` |

> 源码包内不含 `.git`，因此"ZIP comment commit"与本地 `git init` 后的 `bdb30ea` 是同一个源码状态的两种标识；两者都不含任何修改。

## 2. 构建方式（可复现）

```bash
cd /home/tianya/logistics-intake-practice-STU-SIM-01
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"

rm -rf out/main out/test
mkdir -p out/main out/test build

find src/main/java -type f -name '*.java' | LC_ALL=C sort > build/main-sources.txt
javac -cp "lib/sqlite-jdbc-3.46.1.3.jar" --release 21 -encoding UTF-8 -d out/main @build/main-sources.txt

find src/test/java -type f -name '*.java' | LC_ALL=C sort > build/test-sources.txt
javac -cp "out/main:lib/sqlite-jdbc-3.46.1.3.jar" --release 21 -encoding UTF-8 -d out/test @build/test-sources.txt

javap -verbose out/main/lab/ApplicationState.class | grep "major version"
```

实测：main 与 test 编译均 `exit=0`，class 文件 `major version: 65`（Java 21）。

**环境注意**：若用 VS Code 的 Java 扩展编译，可能被其它 JDK 覆盖为 `major version: 69`（Java 25），运行时将报 `UnsupportedClassVersionError`。本项目一律以 `--release 21` + JDK 21 编译为准。

## 3. 基线检查类结果（全部"通过"）

| 检查入口 | 输出 | 说明 |
|---|---|---|
| `lab.RegistryChecks` | `CHECKS_PASSED=279` | 原有登记规则 |
| `lab.ReliabilityCloseoutChecks` | `RELIABILITY_CASES_PASSED=6` | 仅 6 项，未覆盖 14 项矩阵 |
| `lab.simulation.ScenarioParserChecks` | `SCENARIO_PARSER_CHECKS_PASSED=15` | 有 15 个累计断言，不等于 SCN-01—16 |
| `lab.simulation.SimulationRuntimeChecks` | `SIMULATION_RUNTIME_CHECKS_PASSED=14` | RUN-01—18 未完整覆盖 |
| `lab.simulation.ReplayEvidenceChecks` | `REPLAY_EVIDENCE_CHECKS_PASSED=9` | 完整性攻击面覆盖不足 |

> **这些"通过"不构成验收结论**：下面 8 条复现证明缺陷真实存在，而检查类没有抓到。

## 4. 八条缺陷复现（实际输出）

### REP-01 运行失败仍以 0 退出

```bash
java -cp "out/main:out/test:lib/sqlite-jdbc-3.46.1.3.jar" \
  lab.simulation.SimulationMain run \
  scenarios/invalid/invalid-unscripted-call.scenario /tmp/a0-work/rep01-evidence
```

```
EXIT_CODE=0
RUN_FAILED SIM-INVALID-03 version=1 events=4 oraclePass=6 oracleFail=0 creates=1 queries=0 externalOrders=0
EVIDENCE_WRITTEN /tmp/a0-work/rep01-evidence
```

- 期望：`EXIT_CODE=1`
- 实际：`EXIT_CODE=0`
- 结论：**缺陷成立（对应 A-01）**

### REP-02 未脚本化外部调用未被 Oracle 判定失败

同一次运行导出的 `oracle-results.tsv`：

```
oracleId	ruleType	status	expected	actual	detail
ORC-SYS-001	SCRIPTED_EXTERNAL_CALLS	PASS	true	true
ORC-SYS-002	NO_DUPLICATE_EXTERNAL_ORDER	PASS	unique	unique
ORC-SYS-003	UNKNOWN_QUERY_BEFORE_RETRY	PASS	<=1	<=1
ORC-SYS-004	TERMINAL_MONOTONIC	PASS	valid	valid
ORC-SYS-005	EVIDENCE_LEVEL	PASS	LOCAL_SIMULATION	LOCAL_SIMULATION
ORC-SYS-006	NO_REAL_EXTERNAL_WRITE	PASS	0	0
```

- 期望：`ORC-SYS-001` 为 `FAIL`
- 实际：`PASS`，且 6 条不变量全部 `PASS`
- 结论：**缺陷成立（对应 C-01、A-07）**

### REP-03 首个事件失败后仍继续执行后续事件

使用探针场景（dispatch 位于第 4 个事件，第 5 个事件在其后）：

```
SCENARIO SIM-REP-03 1
START_AT 2026-01-01T00:00:00Z
SEED 9003
AT 0 10 EVT-001 POINT_ADD P01 A
AT 0 20 EVT-002 POINT_ADD P02 B
AT 1000 30 EVT-003 APPLICATION_ADD APP-003 P01 P02
AT 2000 40 EVT-004 APPLICATION_DISPATCH APP-003
AT 3000 50 EVT-005 POINT_ADD P03 C
END
```

运行后 `event-timeline.tsv`：

```
eventId	offsetMillis	logicalSequence	action	status	detail
EVT-001	0	10	POINT_ADD	EVENT_PASS
EVT-002	0	20	POINT_ADD	EVENT_PASS
EVT-003	1000	30	APPLICATION_ADD	EVENT_PASS
EVT-004	2000	40	APPLICATION_DISPATCH	EVENT_FAIL	UNSCRIPTED_EXTERNAL_CALL CREATE ordinal=1 app=APP-003
EVT-005	3000	50	POINT_ADD	EVENT_PASS
```

- 期望：`EVT-004` 失败后，`EVT-005` 为 `EVENT_SKIPPED` 或不存在
- 实际：`EVT-005` 仍为 `EVENT_PASS`（业务副作用已产生）
- 结论：**缺陷成立（对应 A-02、A-03）**

### REP-04 删除 manifest 关键行后仍判 MATCH

```bash
# 同一场景运行两次，正常比较
java -cp "$CP" lab.simulation.SimulationMain run scenarios/sim-002-timeout-created.scenario /tmp/a0-work/b1
java -cp "$CP" lab.simulation.SimulationMain run scenarios/sim-002-timeout-created.scenario /tmp/a0-work/b2
java -cp "$CP" lab.simulation.SimulationMain compare /tmp/a0-work/b1 /tmp/a0-work/b2
# 篡改 b2 副本：改场景内容 + 删掉 manifest 中 scenario.scenario 那一行
cp -r /tmp/a0-work/b2 /tmp/a0-work/b3
echo "# TAMPERED BY A0 PROBE" >> /tmp/a0-work/b3/scenario.scenario
grep -v 'scenario\.scenario' /tmp/a0-work/b3/manifest.sha256 > /tmp/a0-work/b3/manifest.tmp
mv /tmp/a0-work/b3/manifest.tmp /tmp/a0-work/b3/manifest.sha256
java -cp "$CP" lab.simulation.SimulationMain compare /tmp/a0-work/b1 /tmp/a0-work/b3
```

```
baseline compare:
REPLAY_MATCH
EXIT_CODE=0
manifest lines after delete: 5
tampered compare:
REPLAY_MATCH
EXIT_CODE=0
```

- 期望：`REPLAY_INVALID_EVIDENCE`，退出码 `2`
- 实际：`REPLAY_MATCH`，退出码 `0`
- 结论：**缺陷成立（对应 D-01、D-02）**

### REP-05 保存失败后内存留下"幽灵状态"

故障注入方式：`Storage.saveApplications` 抛 `IllegalStateException("INJECTED_FAILURE saveApplications")`，先 `dispatchApplication` 到 `DISPATCHED`，再调用 `executeApplication`。

```
REP-05 exception=IllegalStateException:INJECTED_FAILURE saveApplications memoryState=EXECUTING committedState=DISPATCHED verdict=BUG_CONFIRMED
```

- 期望：`memoryState=DISPATCHED`（与已提交一致）
- 实际：`memoryState=EXECUTING`
- 结论：**缺陷成立（对应 B-01、B-04）**

### REP-06 首次 Outbox 意图保存失败后内存仍存在记录

故障注入方式：`Storage.saveOutbox` 抛 `IllegalStateException("INJECTED_FAILURE saveOutbox")`，调用 `dispatchApplication`。

```
REP-06 exception=IllegalStateException:INJECTED_FAILURE saveOutbox memoryOutbox=1 committedOutbox=0 createCalls=0 verdict=BUG_CONFIRMED
```

- 期望：`memoryOutbox=0`
- 实际：`memoryOutbox=1`（外层未调用外部端口，`createCalls=0`）
- 结论：**缺陷成立（对应 B-02）**

### REP-07 只读 SqliteStorage 仍可写入

```java
Path db = ...; // 先用可写 Storage 写入 P01
SqliteStorage rw = new SqliteStorage(db);
rw.savePoints(List.of(new Point("P01", "A")));
SqliteStorage ro = new SqliteStorage(db, true);
ro.savePoints(List.of(new Point("P01", "A"), new Point("P02", "B")));
```

```
REP-07 exception=none committedHasP02=true points=2 verdict=BUG_CONFIRMED
```

- 期望：抛出 `STORAGE_READ_ONLY`，`P02` 不存在
- 实际：无异常，`P02` 已写入
- 结论：**缺陷成立（对应 B-15、B-16）**

### REP-08 超时后 Workflow 与申请状态矛盾

```java
Registry r = new Registry(new MemoryStorage(),
        new FakeRcsAdapter(FakeRcsAdapter.Mode.TIMEOUT_BUT_CREATED), false);
r.registerPoint("P01", "A");
r.registerPoint("P02", "B");
r.registerApplication("APP-008", "P01", "P02");
r.startWorkflow("APP-008");
r.advanceWorkflow("APP-008");
```

```
REP-08 appState=UNKNOWN workflowState=RUNNING stepIndex=1 step0=COMPLETED verdict=BUG_CONFIRMED
```

- 期望：`stepIndex=0`、`step0=PENDING`、`workflow=RUNNING 0/3`
- 实际：`stepIndex=1`、`step0=COMPLETED`
- 结论：**缺陷成立（对应 B-08、B-09、B-10）**

## 5. 复现用探针说明

- REP-05—08 使用一次性探针 `RepProbe.java`（实现 `Storage` 故障注入包装与 `RcsExecutionPort` 计数包装），**不进入仓库**，仅用于本次取证。
- REP-03 使用一次性场景 `rep-03.scenario`，同样不进入仓库（正式场景以 `scenarios/` 下三个合法 + 三个非法为准）。
- 上述探针位于虚拟机 `/tmp/a0/`，如需复跑按第 4 节命令重建。

## 6. A0 结论

| 维度 | 结论 |
|---|---|
| 正向路径 | PASS（三个合法场景均可 `RUN_COMPLETED`，SIM-002 双跑 `REPLAY_MATCH`） |
| 失败语义 | FAIL（REP-01、REP-03） |
| 存储原子性 | FAIL（REP-05、REP-06） |
| 只读保护 | FAIL（REP-07） |
| Oracle 信任 | FAIL（REP-02） |
| 证据完整性 | FAIL（REP-04） |
| Workflow 一致性 | FAIL（REP-08） |
| GATE-A | **NOT_READY** |

下一步：按任务书 Part A 的 PR1（可靠性）→ PR2（运行时与证据）→ PR3（脚本、变异、文档）顺序整改；Part B 在 `GATE-A=PASS` 前不得开始。
