# 仿真基础边界

| 文档属性 | 内容 |
|---|---|
| 版本 | V0.4 |
| 日期 | 2026-10-24 |
| 状态 | FROZEN |
| 证据等级 | LOCAL_SIMULATION |

## 前置 main commit

- 仓库：`logistics-intake-practice`（GitHub 私有练习仓库；地址不写入文档，见 ZIP 外 `delivery-receipt.txt`）
- 基线与交付起点：`main` = `88bf021`（PR2 合并提交，双亲 `30bb272` + `ef76dba`）
- PR3 分支：`r1-verification`，从 `88bf021` 创建；PR3 合并后的最终 main commit 与复验输出记录在 `docs/delivery-verification.md` §8 与 PR3 评论
- 交付 ZIP 与 SHA-256 记录在 ZIP 外的 `delivery-receipt.txt`

## 基线环境

- Ubuntu 24.04 虚拟机
- OpenJDK 21.0.12.1 / javac 21.0.12.1（`--release 21 -encoding UTF-8`，class `major version: 65`）
- UTF-8
- SQLite JDBC 驱动：`lib/sqlite-jdbc-3.46.1.3.jar`
- jar SHA-256：`4a4832720a65eaf7f4d6fd7ede52087b994dc5633c076f9e994dc0c8b4b0b4fa`

## 基线事实（2026-10-24 实测，main=88bf021）

- `src/main/java` Java 文件：57（`lab` 31 + `lab.simulation` 26）
- `src/test/java` Java 文件：4
- `cases/*.in`：30；`scenarios/*.scenario`：3；`scenarios/invalid/*.scenario`：3
- `CHECKS_PASSED=279`、`CONCURRENCY_CHECKS_PASSED=8`、`RELIABILITY_R1_CASES_PASSED=14`
- `SCENARIO_PARSER_CASES_PASSED=16`、`SIMULATION_RUNTIME_CASES_PASSED=18`、`REPLAY_EVIDENCE_R1_CASES_PASSED=12`
- 30 个旧 case 逐字节 diff：`originalCases=30`

## 允许边界

- Ubuntu 24 虚拟机、Java 21、GitHub 私有练习仓库
- 合成点位和合成申请
- `MemoryStorage` / `FileStorage` / `SqliteStorage`
- `ScriptedFakeRcsAdapter`（本地 Java 类，按脚本返回固定结果）
- 仓库内 `scenarios` / `cases` / `build/runs` / `docs`

## 禁止边界

- 登录、探测或修改 lab-server-01
- 访问实验室 Windows 工作站、NAS、公司设备或现场网络
- 读取或使用 M4 账号、License、配置、日志、数据库、IP、端口、凭据、证书或 Machine Code
- 实现 M4 HTTP、WebSocket、TCP、Callback、Candidate 或 Forced 的真实字段合同
- 连接真实 AGV、线边仓、PLC、MES、上位机或数据库
- 场景文件保存真实客户、设备或生产数据
- 修改正式 IDS Product / Private 仓库
- 将练习分支直接 merge、copy 或 cherry-pick 到正式产品主线
- 使用网络请求替代本地 Fake
- 使用真实 `Thread.sleep` 等待逻辑时间到达
- 使用系统当前时间作为场景业务结论的一部分
- 在 PR5/PR6 完成前，声称资源运行时 / 资源场景已可用（`RESOURCE_ACTION_NOT_AVAILABLE`、`RESOURCE_ORACLE_NOT_AVAILABLE` 为未接线的 fail-closed 标记）

## M4 不适用

真实 M4 合同（MVP-REQ-10）不在本轮练习范围：没有 M4 账号、License、端点、字段合同与现场网络，因此与 M4 对齐的任何结论都不成立。M4 相关项固定记为不适用（`N/A`），不得被读作已通过。

## Evidence Level 与零真实外部写

`evidenceLevel=LOCAL_SIMULATION`（每条证据的 `ORC-SYS-005` 断言，`run-summary.txt` 第 20 字段同值）。

外部执行端口固定为 `ScriptedFakeRcsAdapter`，证据记录 `ORC-SYS-006` PASS、`realExternalWriteCount=0`（每条证据第 21 字段），全部证据包求和为 `realExternalWrites=0`。

禁止声明的结果（文档中只以禁止清单形式出现）：

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
- 未执行 Lab 综合验证、Shadow、Canary、Site Acceptance、容量测试
- Part B 资源领域模型已接线（PR4）；资源运行时 / 资源场景未开始

## 当前验证事实

唯一验证命令：

    bash scripts/verify-simulation.sh

终点行（2026-10-24 实测，逐字）：

    SIMULATION_R1_VERIFICATION_PASS originalCases=30 validScenarios=3 invalidScenarios=3 replay=1 evidenceBundles=5 realExternalWrites=0
