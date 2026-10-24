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

## 7. PR1 红绿表（先红阶段 + T2/T2b 收窄记录，2026-10-23 实测）

### 7.0 判据调整（已裁决）

任务清单 S1 的 T1 原要求"**在当前未修复代码上跑出 14 个失败（先红）**"。实测发现
R1-REL-01 / 02 / 11 / 12 / 13 / 14 六项的字面要求在基线 `bdb30ea` 上**本来就成立**，
无法在不削弱或扭曲断言的前提下凑成红。经裁决（2026-10-23），T1 判据改为：

> **8 红 + 6 条基线已满足（转回归锚点）** —— T1 阶段口径

- 7.2 的 8 条 = T2—T6 必须转绿的整改项；
- 7.3 的 6 条 = 不再属于"待修"，但**实现改动后必须仍然为绿**，用于捕获回归。

**2026-10-23 晚 T2b 裁决（A 方案，用户书面批准）**：R1-REL-10 的五层 readonly 属 T2 范围，
落地即转绿并移入 §7.3。判据正式改为：

> **7 红 + 7 绿**：红集合 = R1-REL-03—09（T4/T5 的整改项）；锚点 = R1-REL-01/02/10/11/12/13/14。

§7.1 的 8/6 是 T1 阶段历史实测，保留不改；T2b 之后的实时数字见 §7.5.4。

**2026-10-24 T4 落地（用户指令）**：R1-REL-03—08 六条转绿并移入 §7.3，红集合收窄为
**1 条（R1-REL-09，属 T5）**，锚点 13 条：

> **1 红 + 13 绿**。实时数字与 09 的逐字节比对见 §7.5.6。

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
> （T2b 落地后本类为 **7/7**，见 §7.5.4；T4 落地后为 **13/1**，见 §7.5.6；
> T5 落地后为 **14/0**，见 §7.5.7。）

**2026-10-24 T5 落地（用户指令）**：Workflow 步骤完成条件绑定应用状态（R1-REL-09 转绿），
并给 R1-REL-06 补 query 子断言。红集合清零：

> **0 红 + 14 绿**：`RELIABILITY_R1_CASES_PASSED=14`，进程退出码 `0`。

### 7.2 0 条 FAIL（红）——T5 后清空

> 无红条。R1-REL-03—08 于 **T4** 转绿、R1-REL-09 于 **T5** 转绿、R1-REL-10 于 **T2b** 转绿，
> 全部移入 §7.3 锚点表；各阶段的历史失败行原文保留在 §7.5.2 / §7.5.4 / §7.5.6。

### 7.3 14 条锚点（实现改动后必须仍绿）

| case | 断言要点 | 为什么满足（合同依据 + 代码位置） | 回归锚定对象 |
|---|---|---|---|
| R1-REL-01 | timeout-created 后 processOutbox：create 累计=1、query=1、申请 DISPATCHED、outbox SENT，内存=已提交，二次处理不再调外部 | S1/T4「PENDING 与 UNCERTAIN 都先 query，禁止直接 create」在基线已实现：`Registry.processOutbox` 的 UNCERTAIN 分支只调 `queryOrder`（Registry.java:309-329） | 对账路径不得退化为"UNCERTAIN 重发 create" |
| R1-REL-02 | point 保存失败后内存与已提交均无 P01，且失败后实例可继续使用 | S1/T2+T4「深拷贝候选 → 只改候选 → commitState → 成功后才发布内存」：`registerPoint` 先建候选、`storage.commitState` 成功后才 `publish`（Registry.java） | 失败仍发布 = 回归 |
| R1-REL-03 | transition（execute/complete/cancel）保存失败后状态不变 | **T4**：`transition` 只改候选（`storeApplication`）→ 单次 `commitState` → `publish`；提交失败内存原样（Registry.java） | 任一 transition 回到"先改内存后提交" = 回归 |
| R1-REL-04 | dispatch 首次意图保存失败：outbox=0、create=0、申请仍 CREATED | **T4**：`dispatchApplication` ①先提交 PENDING 意图（失败即抛、端口不调用）②再调端口（Registry.java） | 意图未落盘就调外部端口 = 回归 |
| R1-REL-05 | dispatch 第二次保存失败：不报成功，内存回到第一次提交快照（CREATED+PENDING），恢复先 query 不再 create | **T4**：第二次提交基于第一次快照的新候选，失败不 `publish`；`processOutbox` 对 PENDING 也先 query、`NOT_FOUND` 不重发（Registry.java） | 第二次提交失败仍返回成功 / 恢复时重复 create = 回归 |
| R1-REL-06 | processOutbox 提交失败：create 不增加、状态回到已提交；子断言 `query_accepted_updates_application_and_outbox`：query ACCEPTED 后 application=DISPATCHED 与 outbox=SENT 同一次提交、失败两侧一起回退 | **T4**：`processOutbox` 每条 entry 独立候选 + 独立提交、失败即停；**T5 补子断言**：`queryApplication` 把申请与 outbox 写同一候选，`armCommit(2)` 证明只提交一次、`armCommit(1)` 证明失败两侧回退（Registry.java） | 批量提交 / 盲目重发 / query 拆成两次提交 = 回归 |
| R1-REL-07 | receiveCallback 提交失败：application 与 inbox 均不变 | **T4**：`receiveCallback` 把 inbox 记录与申请流转写进同一候选，一次提交（Registry.java） | 分两次提交 = 回归 |
| R1-REL-08 | startWorkflow/advanceWorkflow 提交失败：三者不变 | **T4**：`startWorkflow` 单次提交；`advanceWorkflow` 把业务动作（含外部 create）与步骤推进写在同一候选，一次提交（REJECTED 只落失败意图、不推进）（Registry.java） | 步骤推进与业务动作分开提交 = 回归 |
| R1-REL-09 | 超时（UNKNOWN/UNCERTAIN）后 workflow 保持 RUNNING 0/3、step0 PENDING；application 的业务结果与 workflow 同一次提交 | **T5**：`advanceWorkflow` 先在同一候选上执行业务动作，只有申请状态**达到或越过**目标阶段才写 `COMPLETED` 并 `index+1`；UNKNOWN / CANCELLED 不算达成（`hasReached` / `lifecycleRank`，Registry.java） | 超时/拒绝时步骤仍被标完成、或 index 乱跳 = 回归 |
| R1-REL-10 | SqliteStorage(db,true) 的 `commitState` 与兼容 `save*` 在写 SQL 前抛 `STORAGE_READ_ONLY`（含候选本身非法的情形）；只读打开/拒写/关闭后库文件与 sidecar 的 bytes+size+mtime 不变；空库只读打开不迁移 | **T2b 落地**：第 1 层 `NOFOLLOW` 普通文件判定、第 2 层 `SQLiteConfig.setReadOnly(true)`、第 3 层不迁移、第 4 层 `checkWritable()` 先于 SQL、第 5 层由 case 断言（SqliteStorage.java） | 只读隔离与零写盘语义不得退化 |
| R1-REL-11 | 只读 + 库不存在 / 目标是目录 / 目标是符号链接：一律 `READ_ONLY_STORAGE_NOT_FOUND`，不建目录、不建文件；`lab.Main sqlite readonly` 缺库时非零退出、首行 `ERR READ_ONLY_STORAGE_NOT_FOUND`、不建 `data/` | S1/T3「五层 readonly」：`SqliteStorage(Path,true)` 用 `Files.isRegularFile(NOFOLLOW_LINKS)` 判定（SqliteStorage.java）；启动路径沿用 `Main` 的 `RegistryException → ERR + exit 1`（Main.java:29-36） | 只读判定重构后零建盘与错误码语义不得丢 |
| R1-REL-12 | 注入失败迁移：失败迁移的表不存在、成功迁移保留、版本不前进、autoCommit 恢复；首条即失败时版本保持 0 | S1/T6「Migration 事务 + 异常 rollback + finally 恢复 autoCommit」在基线已成立：`MigrationRunner.applyMigrationAtomically`（MigrationRunner.java:80-103） | 迁移事务与 autoCommit 恢复不得退化 |
| R1-REL-13 | ProcessBuilder 跑 `lab.Main`：未知/重复/冲突参数 → 退出码 2，`ERR STARTUP_ARGUMENT <arg>` 或 `ERR STARTUP_CONFLICT <storage/fake>` 首行正确，且不进入命令循环（无 STATS/BYE） | S1/T6「启动参数 fail closed」在基线已成立：`StartupOptions.parse`（StartupOptions.java:35-87）+ `Main` 先解析、失败即 `System.exit(2)` 后才建库与读 stdin（Main.java:17-26） | 参数校验不得被挪到建库之后或吞掉退出码 |
| R1-REL-14 | 三步 workflow 后 application 与 workflow 同时 COMPLETED、索引 3/3、三步全 COMPLETED，内存=已提交 | S1/T5「步骤完成绑定真实业务转换」正向路径在基线已成立：`advanceWorkflow` 先调 dispatch/execute/complete 再标本步完成（Registry.java:456-491） | 候选快照化后正向绑定不得丢 |

**声明**：以上 **14 条**作为**回归锚点**，T6 及后续任何实现改动（迁移与启动参数、
PR2/PR3 的运行时与证据）之后**必须仍然为绿**。任一条变红即视为回归，
不得通过改测试或放宽断言消红。

**已知盲区**（锚点不覆盖、需另立 case 的部分；2026-10-24 T5 后更新）：

- 01：不覆盖"PENDING 也要先 query"——**该形态已由 05 的恢复段覆盖**；
- 02：不覆盖 `commitState` 五集合单次提交、FileStorage staging+`ATOMIC_MOVE`、SQLite 单事务（已由一次性探针 `T2Probe` 验证，见 §7.5.3；尚未固化为入库 case）；
- 03—08：锚点为"提交失败不留幽灵"，不覆盖"候选构建过程中抛错"（如 advance 内 DISPATCH 的 `RCS_REJECTED` 分支——已实现但无 case 断言）；
- 10：第 2 层 `mode=ro` 的机制由一次性探针 `T2bProbe` 验证（见 §7.5.5），case 只断言"拒写 + 文件零变化"这一可观测结果；
- 11：T2b 已补"目录 / 符号链接（NOFOLLOW）"与"`lab.Main sqlite readonly` 缺库启动"两个子断言；
- 12：不覆盖非 `SQLException`（如 `RuntimeException`）时是否回滚——`catch` 只认 `SQLException`；
- 09：覆盖 DISPATCH 步的超时形态；不覆盖 EXECUTE/COMPLETE 步的"状态不符"分支（例如 app 被手动 cancel 后再 advance）；
- 13：覆盖启动参数 fail closed；仍不覆盖 `sqlite readonly` 之外的启动路径（如 `file` 模式建目录失败）；
- 14：不覆盖负向绑定（application 未到位却把步骤标 COMPLETED、手动跳步）——超时形态由 09 覆盖。

### 7.4 T2 之后的锚点复核清单（必须逐项打勾）

- [x] R1-REL-01：`RegistryState` 化后，UNCERTAIN 仍走 query；二次 `processOutbox` 仍不调外部端口（T2 实测：仍 PASS）；
- [x] R1-REL-02：`commitState` 失败时内存仍不发布（T2 实测：仍 PASS）；
- [x] R1-REL-11：只读判定移到新层后，缺库仍 `READ_ONLY_STORAGE_NOT_FOUND` 且零建盘（**T2b 实测：仍 PASS**，并新增目录/符号链接/启动路径子断言）；
- [x] R1-REL-12：Migration 事务语义迁移后，版本与 autoCommit 仍正确（T2 未改 `MigrationRunner`，仍 PASS）；
- [x] R1-REL-13：`StartupOptions` 若拆分重构，Main 仍"先解析、后建库"，退出码 2（T2 未改启动路径，仍 PASS）；
- [x] R1-REL-14：workflow 走候选快照后，三步正向仍同时 COMPLETED（T2 实测：仍 PASS）。

若某条锚点因重构变成"恒真"（断言被绕过而非被满足），必须在 PR1 内补新断言，不得删除锚点。

> **T4 复核（2026-10-24）**：13 条锚点全绿；R1-REL-09 仍红，且失败行与 T2b 时**逐字节一致**
> （`cmp` 退出 0，sha256 `a527a44c…`），确属 T5 范围。见 §7.5.6。

> **T5 复核（2026-10-24）**：14 条锚点全绿，`RELIABILITY_R1_CASES_PASSED=14`、退出码 `0`；
> 其余检查逐字节未变。见 §7.5.7。

### 7.5 T2/T2b 落地记录（存储层换代 + 只读加固，2026-10-23 实测）

#### 7.5.1 改动

| 文件 | 改动 |
|---|---|
| `RegistryState.java`（新增） | 五集合整状态快照；构造时 `List.copyOf` + `WorkflowInstance.steps` 深拷贝；提供 `empty()` |
| `Storage.java` | 合同改为 `loadState()` / `commitState(RegistryState)`；旧 `load*` / `save*` 保留为**兼容委托**，内部一律转成 loadState/commitState |
| `MemoryStorage.java` | copy-on-write：先深拷贝候选状态，成功才替换已提交引用；`loadState()` 返回深拷贝 |
| `FileStorage.java` | `*.staging` 暂存文件（写完 flush + fsync）→ `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)`；失败删除暂存、旧快照保留 |
| `SqliteStorage.java` | 五张表全量重写在**单事务**内完成；实现 `AutoCloseable`；删除按集合的独立写路径 |
| `Registry.java` | 机械适配：构造改走 `loadState()`；`persist*()` 统一为 `persistAll()`（提交当前内存快照）。执行顺序与"先改内存、后提交"的时机不变；原先相邻的两次**同内容**持久化（如 dispatch 尾部的 applications+outbox）合并为一次提交 |

**没有做**（属 T4/T5，按约束禁止提前）：dispatch 两次提交、processOutbox 的 PENDING/UNCERTAIN 恢复语义、Workflow 步骤完成条件。

> 测试替身同步换代：`ReliabilityCloseoutChecks.FaultStorage` 的注入口从 `save*` 移到
> `commitState`（`arm` → `armCommit(n)` / `armWhen(predicate)`），**断言、期望值与失败行一条未改**。

#### 7.5.2 验收（实测，与 T1 基线逐条对齐）

```text
MAIN_JAVAC_EXIT=0
TEST_JAVAC_EXIT=0
  major version: 65
CHECKS_PASSED=279
CONCURRENCY_CHECKS_PASSED=8
RELIABILITY_R1_CASES_FAILED=8
RELIABILITY_R1_CASES_PASSED=6
CASES_TOTAL=30 CASES_PASSED=30 CASES_FAILED=0
```

- **8 红**：03/04/05/06/07/08/09/10 全红，失败行与 §7.2 **逐字一致**（未被 T2 顺带修掉）；
- **6 锚点**：01/02/11/12/13/14 全绿；
- 进程退出码仍为 `1`（红阶段）。

> 本节是 **T2 提交点**的历史实测。T2b 之后红集合收窄为 7（R1-REL-10 转锚点），
> 锚点 7 条，见 §7.5.4。

#### 7.5.3 原子化探针（一次性，不入库）

`T2Probe`（14 项）覆盖三类存储的提交语义：

- MemoryStorage：提交后改源列表不影响已提交；`loadState()` 返回独立快照；再次提交替换旧状态；
- FileStorage：令第 5 个 `*.staging` 写入失败 → 提交抛异常、**旧快照仍只有 P01**、无 staging 残留；解除阻塞后可再次提交；
- SqliteStorage：主键冲突触发事务失败 → 五张表**整体回滚**（无部分写入）；`close()` 后只读重开仍可读。

实测：`T2_PROBE_CHECKS=14 T2_PROBE_ALL_PASS`。

#### 7.5.4 五层 readonly（T2b，2026-10-23 晚完成 · 用户裁决 A）

**裁决**：第 4 层（commit 前抛 `STORAGE_READ_ONLY`）正是 R1-REL-10 的断言本体，落地即转绿。
用户裁决采用 A 方案：T2b 单独提交，判据正式由「8 红 + 6 绿」改为「**7 红 + 7 绿**」，
R1-REL-10 移入 §7.3 锚点表。

**实现**（改动仅在 `SqliteStorage.java`，外加测试与文档）：

| 层 | 要求 | 实现 |
|---|---|---|
| 1 | 文件必须存在且是普通文件 | `Files.isRegularFile(dbFile, LinkOption.NOFOLLOW_LINKS)`：目录、符号链接（含悬空链接）一律 `READ_ONLY_STORAGE_NOT_FOUND` |
| 2 | 连接只读 | `SQLiteConfig.setReadOnly(true)` + `DriverManager.getConnection(url, config.toProperties())`（`SQLITE_OPEN_READONLY`） |
| 3 | 不跑 Migration | 只读分支不调用 `MigrationRunner.run`；`currentVersion()` 在只读连接上只查 `sqlite_master`，不建表 |
| 4 | 写 SQL 之前拒写 | `commitState` 与 5 个兼容 `save*` 入口都先 `checkWritable()`，抛 `RegistryException("STORAGE_READ_ONLY")` |
| 5 | close 后零变化 | 只读打开/拒写/关闭后，库文件与 sidecar 的 bytes+size+mtime 不变（由 R1-REL-10 断言） |

**实测**（同一棵树，干净重编）：

```text
MAIN_JAVAC_EXIT=0
TEST_JAVAC_EXIT=0
  major version: 65
CHECKS_PASSED=279
CONCURRENCY_CHECKS_PASSED=8
RELIABILITY_R1_CASES_FAILED=7
RELIABILITY_R1_CASES_PASSED=7
EXIT_CODE=1
CASES_TOTAL=30 CASES_PASSED=30 CASES_FAILED=0
```

- **R1-REL-10 转绿**：`savePoints` 与 `commitState`（含候选主键重复的非法状态）都抛 `STORAGE_READ_ONLY`，
  证明拒绝发生在写 SQL 之前；只读打开/拒写/关闭后库文件与 sidecar 的 bytes+size+mtime 不变；空库只读打开不建表；
- **R1-REL-11 增强后仍绿**：缺库、目录、符号链接三种目标都报 `READ_ONLY_STORAGE_NOT_FOUND`；
  `lab.Main sqlite readonly` 缺库时非零退出、首行 `ERR READ_ONLY_STORAGE_NOT_FOUND`、不建 `data/`；
- **03—09 未被顺带修掉**：T2b 前后对 03—09 的 `FAIL`+`INTENT` 行做逐字节比对，
  `cmp -s` 退出码 `0`，两侧 sha256 均为
  `438635d5cb55fefdf118f47f55df812ff0110bf1034045651cb4dc36055138be`；
- 其余锚点 01/02/12/13/14 全绿。

#### 7.5.5 T2b 探针（一次性，不入库）

`T2bProbe`（5 项）验证第 2 层机制与"拒写后不留锁"：

- 同配置的只读连接执行 `DELETE FROM points` → `SQLITE_READONLY`（`Attempt to write a readonly database`），读仍正常；
- 只读实例 `commitState` → `RegistryException: STORAGE_READ_ONLY`；
- 关闭后没有 `-journal` sidecar，重新用读写实例打开仍可正常写入。

实测：`T2B_PROBE_CHECKS=5 T2B_PROBE_ALL_PASS`。

#### 7.5.6 T4 落地记录（八个写入口统一提交合同，2026-10-24 实测）

**改动**（仅 `Registry.java`）：统一顺序
`checkWritable → 校验 → 候选快照（CandidateState）→ 只改候选 → 一次 commitState → 成功后 publish`。

| 入口 | 提交次数 | 行为 |
|---|---|---|
| `registerPoint` / `registerApplication` | 1 | 候选追加后提交；失败不发布 |
| `transition`（execute/complete/cancel） | 1 | 候选改申请状态后提交 |
| `dispatchApplication` | **2** | ①PENDING 意图提交（失败则端口不调用）②端口结果提交；第二次失败时内存停在①的快照 |
| `queryApplication` | 1 | 申请与 Outbox 同一次提交（ACCEPTED → 申请 DISPATCHED + outbox SENT） |
| `processOutbox` | 每条 1 | PENDING 与 UNCERTAIN 都先 query；NOT_FOUND 不重发；每条独立提交、失败即停 |
| `receiveCallback` | 1 | inbox 去重记录与申请流转同一次提交；终态不倒退 |
| `startWorkflow` | 1 | 候选追加 workflow 后提交 |
| `advanceWorkflow` | 1 | 业务动作（含外部 create）与步骤推进写在同一候选；REJECTED 只落失败意图、不推进 |

**没有做**（T5）：Workflow 步骤完成条件绑定（超时/状态不符时保持 PENDING、index 不动）。

**实测**（同一棵树，干净重编）：

```text
MAIN_JAVAC_EXIT=0
TEST_JAVAC_EXIT=0
  major version: 65
RELIABILITY_R1_CASES_FAILED=1
RELIABILITY_R1_CASES_PASSED=13
EXIT_CODE=1
CHECKS_PASSED=279
CONCURRENCY_CHECKS_PASSED=8
CASES_TOTAL=30 CASES_PASSED=30 CASES_FAILED=0
SCENARIO_PARSER_CHECKS_PASSED=15
SIMULATION_RUNTIME_CHECKS_PASSED=14
REPLAY_EVIDENCE_CHECKS_PASSED=9
```

- **转绿 6 条**：R1-REL-03/04/05/06/07/08 已移入 §7.3（含满足理由）；
- **R1-REL-09 仍红**：失败行与 T2b 时**逐字节一致**（`cmp` 退出 0，
  两侧 sha256 `a527a44c5e7cf48a0f12c8e1790eff1496d77073db4a1cdaef8c17f48a1f3d75`）——
  步骤完成条件绑定属 T5，未被 T4 顺带修掉；
- 锚点 01/02/10/11/12/13/14 全绿；30 个旧 case 30/30；仿真侧 15/14/9 无退化。

**锚点有效性**：03—08 的转绿来自"候选 → 单次提交 → 成功后发布"本身；故障仍由
`FaultStorage.armCommit/armWhen` 在提交点注入，锚点不是"恒真"。

#### 7.5.7 T5 落地记录（Workflow 步骤完成条件绑定，2026-10-24 实测）

**改动**：`Registry.advanceWorkflow`（新增两个私有辅助 `hasReached` / `lifecycleRank`）。

- 每步目标：DISPATCH→DISPATCHED、EXECUTE→EXECUTING、COMPLETE→COMPLETED；
- 先在同一候选快照上执行业务动作（dispatch / transition），再判断申请状态是否**达到或越过**目标阶段：
  达成才写 `COMPLETED` 并 `index+1`（末步置 workflow COMPLETED）；
  超时（UNKNOWN）、REJECTED、CANCELLED、状态不符时：步骤保持 `PENDING`、`currentStepIndex` 不动、
  workflow 保持 `RUNNING`；
- application 的业务结果与 workflow 推进**同一次 commit**，失败整体回退；
- **为什么是"达到或越过"而不是严格等号**：30 个旧 case 的 `sqlite-basic-1` 会先
  `application dispatch` + `callback STARTED`（app=EXECUTING）再 `workflow advance`，
  DISPATCH 步按既有语义应追平为完成（`sqlite-basic-2` 期望 `RUNNING 1/3`）。
  严格等号实测会把 `RUNNING 1/3` 退成 `0/3`，破坏 30/30（已由 `diff -r` 捕获并修正）。
  UNKNOWN / CANCELLED 不在推进主线上，仍不算达成（R1-REL-09 的绿靠这一点）。

**新增子断言**（写在 R1-REL-06 内，不新增编号）：`query_accepted_updates_application_and_outbox`
——query ACCEPTED 后 application=DISPATCHED 且 outbox=SENT 落在同一次提交
（`armCommit(2)` 证明只提交一次：若拆成两次提交，第 2 次会被注入失败）；
提交失败时（`armCommit(1)`）两侧一起回退到 UNKNOWN / UNCERTAIN。

**实测**（同一棵树，干净重编）：

```text
MAIN_JAVAC_EXIT=0
TEST_JAVAC_EXIT=0
  major version: 65
RELIABILITY_R1_CASES_PASSED=14
EXIT_CODE=0
CHECKS_PASSED=279
CONCURRENCY_CHECKS_PASSED=8
SCENARIO_PARSER_CHECKS_PASSED=15
SIMULATION_RUNTIME_CHECKS_PASSED=14
REPLAY_EVIDENCE_CHECKS_PASSED=9
CASES_TOTAL=30 CASES_PASSED=30 CASES_FAILED=0
```

- **R1-REL 输出前后差异只有 09**：`R1-REL-09 FAIL/INTENT` 两行 → `R1-REL-09 PASS`，
  汇总 `FAILED=1/PASSED=13` → `PASSED=14`；其余 13 行逐行未变；
- **五个检查类摘要行逐字节一致**：`cmp` 退出 `0`，两侧 sha256 均为
  `8d541511f28fdf91b7aa7b2b820261f60347549dc7e318f1e5c9b282e0d539c5`；
- **30 个旧 case 的 60 个产物 `diff -r` 无差异**（`CASE_RUNS_DIFF_EXIT=0`）。



