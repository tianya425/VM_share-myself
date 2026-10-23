# 仿真基础交付说明

| 文档属性 | 内容 |
|---|---|
| 版本 | V0.1 |
| 日期 | 2026-10-04 |
| 类型 | 仿真基础交付说明 |
| 对应正式文档 | IDS 仿真、回放与验证架构说明书 |
| 状态 | DRAFT |

## 文档说明

本文件说明练习仿真基础模块的交付内容、边界和后续接纳建议。

## 交付清单

### 新增包

- `lab.simulation`

### 新增类（18 个）

场景模型：

- ScenarioPackage
- ScenarioEvent
- ExternalScript
- OracleRule

编译与时间：

- ScenarioParser
- ScenarioValidationException
- VirtualClock

运行与 Fake：

- SimulationTarget
- RegistrySimulationTarget
- ScriptedFakeRcsAdapter
- ExternalInteraction
- SimulationRuntime
- SimulationRunResult

Oracle：

- InvariantOracle
- OracleResult

Replay 与 Evidence：

- EvidenceBundleWriter
- ReplayComparator

入口：

- SimulationMain

### 新增场景

- scenarios/sim-001-happy.scenario
- scenarios/sim-002-timeout-created.scenario
- scenarios/sim-003-timeout-not-created.scenario
- scenarios/invalid/invalid-backward-time.scenario
- scenarios/invalid/invalid-duplicate-event.scenario
- scenarios/invalid/invalid-unscripted-call.scenario

### 新增测试

- src/test/java/lab/ReliabilityCloseoutChecks.java
- src/test/java/lab/simulation/ScenarioParserChecks.java
- src/test/java/lab/simulation/SimulationRuntimeChecks.java
- src/test/java/lab/simulation/ReplayEvidenceChecks.java

### 新增脚本

- scripts/verify-simulation.sh

### 新增文档

- docs/simulation-boundary.md
- docs/simulation-format.md
- docs/simulation-handoff.md

## 行为合同

### ScenarioParser

- 严格 UTF-8、无 BOM、无 NUL
- 原始字节 SHA-256
- header / 事件顺序 / 脚本 ordinal / 参数数量校验
- 失败时抛 ScenarioValidationException

### VirtualClock

- 从 startAt 开始
- 时间倒退 → TIME_TRAVEL_BACKWARD
- 不使用系统当前时间

### ScriptedFakeRcsAdapter

- 按 operation + ordinal 查找脚本
- 找不到 → UNSCRIPTED_EXTERNAL_CALL
- 记录 ExternalInteraction 时间线

### SimulationRuntime

- 每次运行建立全新 Storage / Registry / Clock / Fake
- 按事件顺序推进虚拟时间
- 接入 InvariantOracle

### InvariantOracle

- 6 条系统不变量不可由场景关闭
- 场景 EXPECT 独立报告 PASS/FAIL

### EvidenceBundleWriter

- 7 个文件
- manifest 不含自身
- 不含绝对路径或敏感信息

### ReplayComparator

- 校验 manifest
- scenarioDigest 相同
- 逐文件比较
- MATCH / DIFFERENT / INVALID_EVIDENCE

## 已知技术债

- Fake 状态是内存态，跨进程不保留（第三阶段只做单进程 Replay）
- Oracle 未读取 HealthReport，只读取运行时公开快照

## 可供正式平台标准化的资产

- Scenario 文件格式
- VirtualClock 行为
- ScriptedFakeRcsAdapter 故障语义
- InvariantOracle 规则候选
- Evidence 文件结构
- Replay 比较规则

## 不建议直接复用的练习结构

- `Registry` 聚合式结构
- 命令行入口 `SimulationMain`
- 内存态 Storage 和 Fake
- 场景里的中文点位名称（仅用于练习）

## 代码整洁化记录

- 所有 public 方法均有 Javadoc
- SimulationRuntime.run() 拆成 5 个辅助方法
- InvariantOracle.check() 拆成 6 + 6 个独立检查方法
- 引入 OracleContext 替代裸参数
- 无临时对象

## 尚未整理

- test 目录下的检查类尚未逐个加 Javadoc
- SimulationMain 的三个子命令方法可以进一步拆

## 声明

- FORMAL_REPOSITORY_CHANGE=NONE
- REAL_M4_ACCESS=NONE
- REAL_EXTERNAL_WRITE_COUNT=0
