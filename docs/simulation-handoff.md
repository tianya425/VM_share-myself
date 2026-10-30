# 仿真基础交付说明（STU-SIM-02-R1）

## 一、绑定信息

TASK_ID=STU-SIM-02-R1
TASK_OWNER=牛泽原
BRANCH=nzy/stu-sim-02-r1-contract-closeout
BASE_COMMIT=ecc0215
BASE_REF=backup/stu-sim-02-before-r1
CHECKPOINTS=C1..C6（C1 42c2115 / C2 7ad89a4 / C3 a784a74 / C4 3e7cbcd / C5 de1ad7f / C6 见最终 commit）
EVIDENCE_LEVEL=LOCAL_SIMULATION
GATE_B=WAIT_FOR_INDEPENDENT_REVIEW
DELIVERY_RECEIPT=delivery-receipt.txt

最终 commit、tree、源码 ZIP 与 bundle 的 hash 只在仓库外 `delivery-receipt.txt` 中给出，Git 跟踪文档不写最终 hash（避免自引用）。

## 二、交付内容

### 领域与运行时

- `lab.simulation.resource`：Destination / ServicePoint / VirtualResource / Reservation / ReservationDecision / ReservationState / ResourceAvailability / ResourceWorldSnapshot / VirtualResourceWorld / ResourceStateValidator
- `lab.Registry`、`lab.FileStorage`、`lab.MemoryStorage`、`lab.SqliteStorage`：统一提交与加载校验
- `lab.simulation`：ScenarioParser（固定 8 Action / 8 EXPECT）、SimulationRuntime（首错即停、WORLD_RESTART、Oracle 只读）、InvariantOracle、ResourceInvariantOracle、EvidenceBundleWriter / Validator、ReplayComparator

### 场景（6 合法 + 5 非法）

- 合法：`sim-101-single-hold-release`、`sim-102-contention-promotion`、`sim-103-idempotent-request`、`sim-104-conflicting-target`、`sim-105-disable-enable`、`sim-106-restart-recovery`
- 非法：`missing-destination`（`RESOURCE_DESTINATION_NOT_FOUND`）、`target-mismatch`（`RESOURCE_TARGET_MISMATCH`）、`duplicate-conflicting-id`（`RESOURCE_DUPLICATE_CONFLICT`）、`release-non-holder`（`RESERVATION_NOT_HELD`）、`terminal-reopen`（`RESERVATION_TERMINAL`）

### 测试与脚本

- `ResourceWorldChecks` 24、`ResourceContractCorrectionChecks` 42、`ResourceStorageChecks` 6、`ResourceParserChecks` 7、`ResourceScenarioChecks` 10、`ReplayEvidenceChecks` 12，以及原有 Registry / Concurrency / Reliability / Parser / Runtime 检查
- `scripts/verify-simulation.sh`：24 项唯一验证入口，支持可选 `SIM_EVIDENCE_OUTPUT_DIR`

## 三、验收事实（2026-10-30 实测）

- 30 旧 case 逐字节通过：`ORIGINAL_CASES_OK total=30 passed=30`
- `RESOURCE_WORLD_CASES_PASSED=24`、`RESOURCE_R1_CONTRACT_CASES_PASSED=42`、`RESOURCE_SCENARIO_CASES_PASSED=10`
- 6 合法场景各双跑一致（6 对 `REPLAY_MATCH`）；5 非法场景 exit=1、固定 code、首错后全部 `EVENT_SKIPPED`
- 6 项 mutation 均被检出，随后恢复干净源码
- 22 个证据包：7 文件、manifest 6 行、21 字段、`realExternalWrites=0`
- 终点行：`SIMULATION_RESOURCE_VERIFICATION_PASS originalCases=30 baseValidScenarios=3 baseInvalidScenarios=3 resourceValidScenarios=6 resourceInvalidScenarios=5 resourceReplay=6 realExternalWrites=0`

## 四、已知限制

- 只证明本地仿真行为，不证明真实 M4 / AGV / PLC / MES 合同或容量。
- `docs/delivery-verification.md` 是历史分阶段记录，保留原文语境；R1 结论以本文档、`summary.md`、`resource-world.md` 为准。
- 独立复核未完成，Gate 状态保持 `WAIT_FOR_INDEPENDENT_REVIEW`。

## 五、回执绑定

FINAL_SOURCE_IDENTITY=BOUND_BY_EXTERNAL_DELIVERY_RECEIPT
DELIVERY_RECEIPT=delivery-receipt.txt
