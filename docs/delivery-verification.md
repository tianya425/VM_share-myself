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

---

## 7. T1 红绿表（PR1 先红阶段，2026-10-23 实测）

### 7.0 判据调整（已裁决）

任务清单 S1 的 T1 原要求"**在当前未修复代码上跑出 14 个失败（先红）**"。实测发现
R1-REL-01 / 02 / 11 / 12 / 13 / 14 六项的字面要求在基线 `bdb30ea` 上**本来就成立**，
无法在不削弱或扭曲断言的前提下凑成红。经裁决（2026-10-23），T1 判据改为：

> **8 红 + 6 条基线已满足（转回归锚点）**

- 7.2 的 8 条 = T2—T6 必须转绿的整改项；
- 7.3 的 6 条 = 不再属于"待修"，但**实现改动后必须仍然为绿**，用于捕获回归。

### 7.1 复跑方式与实测结果

```bash
cd /home/tianya/logistics-intake-practice-STU-SIM-01
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
rm -rf out/main out/test && mkdir -p out/main out/test build
find src/main/java -type f -name '*.java' | LC_ALL=C sort > build/main-sources.txt
javac -cp "lib/sqlite-jdbc-3.46.1.3.jar" --release 21 -encoding UTF-8 -d out/main @build/main-sources.txt
find src/test/java -type f -name '*.java' | LC_ALL=C sort > build/test-sources.txt
javac -cp "out/main:lib/sqlite-jdbc-3.46.1.3.jar" --release 21 -encoding UTF-8 -d out/test @build/test-sources.txt
java -cp "out/main:out/test:lib/sqlite-jdbc-3.46.1.3.jar" lab.ReliabilityCloseoutChecks
```

实测：

| 项 | 结果 |
|---|---|
| main / test 编译 | `exit=0` / `exit=0`，class `major version: 65`（OpenJDK 21.0.12.1） |
| `RELIABILITY_R1_CASES_PASSED` | `6` |
| `RELIABILITY_R1_CASES_FAILED` | `8` |
| 进程退出码 | `1` |
| 本次原始输出 | `build/reliability-r1-red.txt`（不入库，可用上面的命令复现） |
| 30 个旧 case | `CASES_TOTAL=30 CASES_PASSED=30 CASES_FAILED=0`（逐字节 `cmp`，产物在 `build/case-runs/`，未覆盖 `cases/*.actual.txt`，未改 `cases/*.expected.txt`） |

> **数字巧合警告**：本节 `RELIABILITY_R1_CASES_PASSED=6` 与第 3 节基线记录里的
> `RELIABILITY_CASES_PASSED=6` 含义不同——旧类是"6 个 case 全过（6/6）"，
> 新类是"14 个 case 中 6 个过（6/14），另 8 个红"。复核时不要按数字误判。

### 7.2 8 条 FAIL（红）——T2—T6 的整改对账依据

| case | 真实失败行（逐字） | 断言意图 | 对应整改项 |
|---|---|---|---|
| R1-REL-03 | `executeApplication 保存失败后内存状态=EXECUTING 期望回到已提交快照=DISPATCHED` | transition（execute/complete/cancel）保存失败后状态不变 | REP-05（B-01/B-04） |
| R1-REL-04 | `内存 outbox 条数=1 期望=0（幽灵 outbox 记录）` | dispatch 首次意图保存失败：outbox=0、create=0、申请仍 CREATED | REP-06（B-02） |
| R1-REL-05 | `dispatch 失败后内存申请状态=DISPATCHED 期望回到第一次提交快照=CREATED` | dispatch 第二次保存失败：不报成功、内存回到第一次提交快照（CREATED+PENDING）、恢复时先 query 且不再 create | T4 两次提交合同 |
| R1-REL-06 | `提交失败后内存申请状态=DISPATCHED 期望回到已提交=UNKNOWN` | query/processOutbox 提交失败：create 不增加、状态回到已提交 | T4 processOutbox 单条候选提交 |
| R1-REL-07 | `提交失败后内存申请状态=EXECUTING 期望回到已提交=DISPATCHED` | receiveCallback 提交失败：application 与 inbox 均不变 | T4 receiveCallback 单次提交 |
| R1-REL-08 | `startWorkflow 失败后内存仍有 workflow（幽灵状态）` | startWorkflow/advanceWorkflow 提交失败：application、outbox、workflow 三者不变 | T4 start/advance 单次提交 |
| R1-REL-09 | `超时后 stepIndex=1 期望=0 即 RUNNING 0/3（advanceWorkflow 未抛异常）` | 超时（UNKNOWN/UNCERTAIN）后 workflow 保持 RUNNING 0/3、step0 PENDING | REP-08（B-08/B-09/B-10） |
| R1-REL-10 | `只读 SqliteStorage 允许 savePoints 成功（未抛 STORAGE_READ_ONLY）` | SqliteStorage(db,true) 直接写必须抛 STORAGE_READ_ONLY，库文件与 sidecar 字节不变 | REP-07（B-15/B-16） |

> 失败行与断言意图成对出现在输出里（`R1-REL-XX FAIL: …` + `R1-REL-XX INTENT: …`），
> 便于整改时逐条对账；8 条不是"坏掉的测试"，是 T2—T6 的验收面。

### 7.3 6 条 PASS（基线已满足，转回归锚点）

| case | 断言要点 | 基线为何已满足（合同依据 + 代码位置） | 回归锚定对象 |
|---|---|---|---|
| R1-REL-01 | timeout-created 后 processOutbox：create 累计=1、query=1、申请 DISPATCHED、outbox SENT，内存=已提交，二次处理不再调外部 | S1/T4「PENDING 与 UNCERTAIN 都先 query，禁止直接 create」已实现：`Registry.processOutbox` 的 UNCERTAIN 分支只调 `queryOrder`（Registry.java:309-329） | 对账路径不得退化为"UNCERTAIN 重发 create" |
| R1-REL-02 | point 保存失败后内存与已提交均无 P01，且失败后实例可继续使用 | S1/T2+T4「深拷贝候选 → 只改候选 → commitState → 成功后才发布内存」对单集合已成立：`registerPoint` 先建候选 Map、`storage.savePoints` 成功后才 `points.put`（Registry.java:67-71） | 引入 `commitState` 后不得出现"失败仍发布" |
| R1-REL-11 | 只读 + 库不存在：`READ_ONLY_STORAGE_NOT_FOUND`，不建目录、不建文件，临时目录零残留 | S1/T3「五层 readonly：文件必须存在」已成立：`SqliteStorage(Path,true)` 先用 `Files.exists` 判定再抛 `READ_ONLY_STORAGE_NOT_FOUND`，未走到 JDBC（SqliteStorage.java:86-90） | 只读判定重构后零建盘语义不得丢 |
| R1-REL-12 | 注入失败迁移：失败迁移的表不存在、成功迁移保留、版本不前进、autoCommit 恢复；首条即失败时版本保持 0 | S1/T6「Migration 事务 + 异常 rollback + finally 恢复 autoCommit」已成立：`MigrationRunner.applyMigrationAtomically`（MigrationRunner.java:80-103） | 迁移事务与 autoCommit 恢复不得退化 |
| R1-REL-13 | ProcessBuilder 跑 `lab.Main`：未知/重复/冲突参数 → 退出码 2，`ERR STARTUP_ARGUMENT <arg>` 或 `ERR STARTUP_CONFLICT <storage/fake>` 首行正确，且不进入命令循环（无 STATS/BYE） | S1/T6「启动参数 fail closed」已成立：`StartupOptions.parse`（StartupOptions.java:35-87）+ `Main` 先解析、失败即 `System.exit(2)` 后才建库与读 stdin（Main.java:17-26） | 参数校验不得被挪到建库之后或吞掉退出码 |
| R1-REL-14 | 三步 workflow 后 application 与 workflow 同时 COMPLETED、索引 3/3、三步全 COMPLETED，内存=已提交 | S1/T5「步骤完成绑定真实业务转换」正向路径已成立：`advanceWorkflow` 先调 dispatch/execute/complete 再标本步完成（Registry.java:456-491） | 候选快照化后正向绑定不得丢 |

**声明**：以上 6 条作为**回归锚点**，T2—T6 任何实现改动（含 T2 引入 `commitState`、
T3 三类 Storage 原子化、T4 八个写入口改造、T5 workflow 绑定、T6 迁移与启动参数）之后
**必须仍然为绿**。任一条变红即视为回归，不得通过改测试或放宽断言消红。

**已知盲区**（这 6 条不覆盖，需在 T2/T3/T6 另立 case，届时补入本表）：

- 01：不覆盖"PENDING 也要先 query"（该形态由 05 的恢复段覆盖）；
- 02：不覆盖 `commitState` 五集合单次提交、FileStorage staging+`ATOMIC_MOVE`、SQLite 单事务（现在的 `Storage` API 表达不出来）；
- 11：不覆盖"目标必须是普通文件"（传目录时退化为 `RuntimeException: cannot open sqlite db`，不是 `READ_ONLY_STORAGE_NOT_FOUND`）、"只读不跑 Migration"、`mode=ro`、close 后 bytes/size/mtime/sidecar 不变；
- 12：不覆盖非 `SQLException`（如 `RuntimeException`）时是否回滚——`catch` 只认 `SQLException`；
- 13：不覆盖 T6 另一半——`sqlite readonly` 目标不存在 → `ERR READ_ONLY_STORAGE_NOT_FOUND` 非零且不建目录；
- 14：不覆盖负向绑定（application 未到位却把步骤标 COMPLETED、手动跳步）——超时形态由 09 覆盖。

### 7.4 T2 之后的锚点复核清单（必须逐项打勾）

- [ ] R1-REL-01：`RegistryState` 化后，UNCERTAIN 仍走 query；二次 `processOutbox` 仍不调外部端口；
- [ ] R1-REL-02：`commitState` 失败时内存仍不发布；
- [ ] R1-REL-11：只读判定移到新层后，缺库仍 `READ_ONLY_STORAGE_NOT_FOUND` 且零建盘；
- [ ] R1-REL-12：Migration 事务语义迁移后，版本与 autoCommit 仍正确；
- [ ] R1-REL-13：`StartupOptions` 若拆分重构，Main 仍"先解析、后建库"，退出码 2；
- [ ] R1-REL-14：workflow 走候选快照后，三步正向仍同时 COMPLETED。

若某条锚点因重构变成"恒真"（断言被绕过而非被满足），必须在 PR1 内补新断言，不得删除锚点。
