# R1 问题 → 文件 → 测试 → Evidence 追踪

| 属性 | 内容 |
|---|---|
| 任务 | STU-SIM-02-R1 |
| 版本 | V2.0 |
| 日期 | 2026-10-30 |
| 基线 | `ecc0215` |
| Gate | `WAIT_FOR_INDEPENDENT_REVIEW` |

## 一、偏差 → 修复 → 测试

| 偏差 | 修复文件 | 测试 | Evidence |
|---|---|---|---|
| REP-R1-01 `ReservationDecision` 缺 CONFLICT/detail | `lab/simulation/resource/ReservationDecision.java` | `R1-CON-01..06`、`RW-15`、`RSC-04` | `resource-scenario.out.txt`（SIM-104 CONFLICT） |
| REP-R1-02 换目标抛异常 | `Registry.requestReservation` | `R1-CON-*`、`RSC-04` | SIM-104 Evidence（runStatus=COMPLETED） |
| REP-R1-03 DISPATCHED 后重复请求被拒 | `Registry.requestReservation`（幂等先于 state 检查） | `R1-DOM-*`、`RW-14` | SIM-103 Evidence（EVT-008 IDEMPOTENT） |
| REP-R1-04 非法标量可进入领域对象 | `Reservation`/`Destination`/`ServicePoint`/`VirtualResource` 构造校验 | `R1-DOM-01..08` | `resource-contract.out.txt` |
| REP-R1-05 孤儿 HELD 可被加载 | `ResourceStateValidator`、`FileStorage`/`MemoryStorage`/`SqliteStorage` | `R1-LOAD-01..06` | `resource-contract.out.txt` |
| REP-R1-06 两条活动预约 Oracle 全 PASS | `ResourceInvariantOracle` | `R1-ORC-01..06`、`RSC-09` | `resource-scenario.out.txt`（RSC-09） |
| REP-R1-07 Oracle 语义被替换 | `ResourceInvariantOracle`（固定 ID/语义） | `R1-ORC-01..06` | `resource-contract.out.txt` |
| REP-R1-08 旧 Action 名/参数 | `ScenarioParser`、场景文件 | `R1-DSL-01..06`、`ResourceParserChecks` | `resource-parser.out.txt` |
| REP-R1-09 EXPECT 集合不一致 | `ScenarioParser`/`ResourceInvariantOracle` | `R1-DSL-03`、`RSC-01` | `resource-scenario.out.txt`（RSC-01） |
| REP-R1-10 6+5 场景编号/语义错位 | `scenarios/resource/*.scenario`、`scenarios/resource/invalid/*.scenario`、`ResourceScenarioChecks` | `RSC-01..10` | 6×2 合法 + 5 非法 Evidence |
| REP-R1-11 FileStorage 六文件逐个替换 | `FileStorage`、`StateFileOps` | `R1-STORE-01..10`、`RW-24` | `resource-contract.out.txt`、`resource-world.out.txt` |
| REP-R1-12 任务名/文档/commit/Gate 不一致 | README、docs、`delivery-receipt.txt` | 脚本第 22 项（文档一致性） | `verification-after.log`、`gate-matrix.md` |

## 二、检查点与测试入口

| 检查点 | commit | 主要测试 | 计数 |
|---|---|---|---|
| C1 先红 | `42c2115` | `ResourceContractCorrectionChecks` | 红：31 FAIL / 11 PASS |
| C2 领域+预约 | `7ad89a4` | `R1-CON`、`R1-DOM` | 14/14 |
| C3 加载+存储 | `a784a74` | `R1-LOAD`、`R1-STORE` | 16/16 |
| C4 DSL+Oracle | `3e7cbcd` | `R1-DSL`、`R1-ORC` | 12/12（合同合计 42） |
| C5 场景+Evidence | `de1ad7f` | `RSC-01..10` | 10/10 |
| C6 文档/脚本/交付 | 最终 commit | 24 项脚本 + 6 项 mutation | 全过 |

## 三、固定 Oracle 与反例

| Oracle | 语义 | 反例测试 |
|---|---|---|
| ORC-RES-SYS-001 | 同一资源最多一个 HELD | RSC-09 / RW-12 |
| ORC-RES-SYS-002 | 队列固定排序、只晋升队首 | RSC-09 / RW-13 |
| ORC-RES-SYS-003 | 引用链完整 | RSC-09 / RW-24 |
| ORC-RES-SYS-004 | 幂等请求不改排序键 | RSC-09 / RW-14 |
| ORC-RES-SYS-005 | DISABLED 不驱逐 holder | RSC-09 / RW-20 |
| ORC-RES-SYS-006 | 重启前后规范摘要一致 | RSC-09 / RW-23 |

## 四、验证入口

```text
bash scripts/verify-simulation.sh
```

终点行：`SIMULATION_RESOURCE_VERIFICATION_PASS originalCases=30 ... realExternalWrites=0`

## 五、回执绑定

FINAL_SOURCE_IDENTITY=BOUND_BY_EXTERNAL_DELIVERY_RECEIPT
DELIVERY_RECEIPT=delivery-receipt.txt
