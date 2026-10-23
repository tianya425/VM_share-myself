# 仿真基础边界

## 前置 main commit

- 仓库：logistics-intake-practice
- 分支：main
- commit：待 PR 合并后从 GitHub 记录
## 基线环境

- Ubuntu 24 虚拟机
- OpenJDK 21.0.12.1
- javac 21.0.12.1
- UTF-8
- SQLite JDBC 驱动：lib/sqlite-jdbc-3.46.1.3.jar
- jar SHA-256：4a4832720a65eaf7f4d6fd7ede52087b994dc5633c076f9e994dc0c8b4b0b4fa

## 基线事实

- lab 包 Java 文件数：28
- cases 文件数：94
- docs 文档数：8
- RegistryChecks：CHECKS_PASSED=275
- ConcurrencyChecks：CONCURRENCY_CHECKS_PASSED=8

## 基线复验

- 从 main 下载全新 ZIP
- 全新目录 verify-32
- 使用源文件列表编译：`find src/main/java -type f -name '*.java' | LC_ALL=C sort > build/main-sources.txt`
- 编译命令：`javac -cp "lib/sqlite-jdbc-3.46.1.3.jar" --release 21 -encoding UTF-8 -d out/main @build/main-sources.txt`
- 全部既有回归：PASS
- ReliabilityCloseoutChecks：RELIABILITY_CASES_PASSED=6

## 允许边界

- Ubuntu 24 虚拟机
- Java 21
- GitHub 私有练习仓库
- 合成点位和合成申请
- MemoryStorage
- ScriptedFakeRcsAdapter
- 仓库内 scenarios / cases / build/runs / docs
- 既有 SQLite 驱动（仅用于 REL-03）

## 禁止边界

- 登录、探测或修改 lab-server-01
- 访问实验室 Windows 工作站、NAS、公司设备或现场网络
- 读取或使用 M4 账号、License、配置、日志、数据库、IP、端口、Token、证书或 Machine Code
- 实现 M4 HTTP、WebSocket、TCP、Callback、Candidate 或 Forced 的真实字段合同
- 连接真实 AGV、线边仓、PLC、MES、上位机或数据库
- 场景文件保存真实客户、设备或生产数据
- 修改正式 IDS Product / Private 仓库
- 将练习分支直接 merge、copy 或 cherry-pick 到正式产品主线
- 使用网络请求替代本地 Fake
- 使用真实 sleep 等待逻辑时间到达
- 使用系统当前时间作为场景业务结论的一部分

## Fake 与真实系统的区别

Fake 是本仓库内的本地 Java 类，按脚本返回固定结果，不连接任何真实系统。

真实 M4、AGV、PLC、MES 和数据库均不在本轮范围内。本轮的 Evidence Level 固定为 LOCAL_SIMULATION。

## Evidence Level

LOCAL_SIMULATION

禁止声明的结果：

- M4_CONTRACT_PASS
- VERIFIED_LAB
- SITE_PASS
- PRODUCTION_READY
- CAPACITY_PASS

## 未执行项

- 未连接真实 M4
- 未连接真实 AGV
- 未连接真实 PLC
- 未连接真实 MES
- 未连接真实数据库
- 未执行任何真实外部写

## 第二阶段：确定性场景与虚拟时间运行时

### 新增

- `lab.simulation` 包，14 个 Java 文件
- `scenarios/` 下 3 个合法场景和 3 个非法场景
- `src/test/java/lab/simulation/ScenarioParserChecks.java`
- `src/test/java/lab/simulation/SimulationRuntimeChecks.java`

### 允许的 action

- POINT_ADD
- APPLICATION_ADD
- APPLICATION_DISPATCH
- APPLICATION_QUERY
- APPLICATION_CALLBACK
- OUTBOX_PROCESS
- WORKFLOW_START
- WORKFLOW_ADVANCE

### 三个合法场景

- SIM-001 正常创建、下发、开始、完成
- SIM-002 create 超时但外部已创建，对账后恢复
- SIM-003 create 超时且外部未创建，保持 UNKNOWN

### 三个非法场景

- invalid-backward-time：时间倒退
- invalid-duplicate-event：重复 eventId
- invalid-unscripted-call：未脚本化外部调用

### 检查结果

- SCENARIO_PARSER_CHECKS_PASSED=15
- SIMULATION_RUNTIME_CHECKS_PASSED=14

### 尚未实现

- InvariantOracle（第三阶段）
- EvidenceBundleWriter（第三阶段）
- ReplayComparator（第三阶段）
- compare 子命令（第三阶段）

## 第三阶段：不变量回放与证据交付

### 新增

- `OracleResult.java` — 单条 Oracle 结果
- `InvariantOracle.java` — 系统不变量与场景 EXPECT 检查
- `EvidenceBundleWriter.java` — 证据写入
- `ReplayComparator.java` — 两次运行比较
- `src/test/java/lab/simulation/ReplayEvidenceChecks.java`
- `scripts/verify-simulation.sh`

### 修改

- `SimulationMain.java` — 加 compare 子命令
- `SimulationRuntime.java` — 接入 Oracle
- `SimulationRunResult.java` — 加 oracleResults 字段

### 系统不变量

- ORC-SYS-001 外部调用全部有脚本
- ORC-SYS-002 同一申请最多一个有效外单
- ORC-SYS-003 Unknown 先对账
- ORC-SYS-004 终态不倒退
- ORC-SYS-005 Evidence 不越级
- ORC-SYS-006 零真实外部写

### 证据目录

每次 run 生成 7 个文件：

- scenario.scenario
- scenario.sha256
- run-summary.txt
- event-timeline.tsv
- external-interactions.tsv
- oracle-results.tsv
- manifest.sha256

### 一键验证

    bash scripts/verify-simulation.sh

最终输出：

    SIMULATION_VERIFICATION_PASS scenarios=3 replay=1 evidence=4 realExternalWrites=0

## 第四阶段：代码整洁化

### 修改

- 补全 public 方法的 Javadoc：
  - ScriptedFakeRcsAdapter 的 createOrder / queryOrder 及三个只读方法
  - VirtualClock 的四个方法
  - SimulationTarget 接口的八个方法
  - OracleResult 的两个工厂方法
  - SimulationRunResult 的两个计数方法
  - EvidenceBundleWriter 的构造方法
  - SimulationMain 的 main 方法

- 拆分 SimulationRuntime.run()：
  原来的 90 行方法拆成：
  - setup()
  - executeEvents()
  - checkOracle()
  - decideStatus()
  - buildResult()

- 拆分 InvariantOracle.check()：
  顶层 check 只做调度，六条系统不变量和六种 EXPECT 各成独立方法

- 新增 OracleContext 类：
  取代原来传给 Oracle 的 4 个裸参数和一个假的 SimulationRunResult

- 去掉临时对象 tempResult

### 验证

一键验证通过：

    SIMULATION_VERIFICATION_PASS scenarios=3 replay=1 evidence=4 realExternalWrites=0
