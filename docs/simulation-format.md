# 仿真场景与证据格式

| 文档属性 | 内容 |
|---|---|
| 版本 | V0.4 |
| 日期 | 2026-10-30 |
| 类型 | 场景文件与 Evidence 格式说明 |
| 对应正式文档 | IDS 仿真、回放与验证架构说明书 |
| 状态 | FROZEN（STU-SIM-02-R1 C6：固定 8 Action / 8 EXPECT / 21 字段 / 7 文件，运行期字段名为 eventPassCount / eventFailCount / eventSkippedCount） |
| 绑定代码 | `lab.simulation.ScenarioParser` / `EvidenceBundleValidator` / `EvidenceBundleWriter` / `ReplayComparator` / `ResourceInvariantOracle` |

## 文档说明

本文件定义 `scenarios/*.scenario` 的语法、稳定错误码，以及一次 `run` 产生的 Evidence 目录结构（7 个文件、`run-summary.txt` 的 21 个固定字段、三个 TSV 的列格式）和回放比较规则。

**它做什么：**

- 说明每条记录类型的字段和顺序
- 说明允许的 action、outcome 和 oracle 类型
- 说明 Parser 与 Evidence 校验的稳定错误码
- 逐字固定 Evidence 的 7 个文件、21 个字段与列格式

**它不做什么：**

- 不定义运行时业务语义（见 `simulation-boundary.md`）
- 不定义真实 M4 / Lab / 生产合同

## 文件编码

- UTF-8
- LF 换行
- 无 BOM
- 无 NUL
- 单文件不超过 1 MiB

## 场景记录类型

### SCENARIO

    SCENARIO <scenarioId> <version>

- scenarioId：字母或数字开头，允许大写字母、数字、下划线、连字符，最长 64
- version：≥ 1；当前 Parser 只接受 `1`

### START_AT

    START_AT <ISO-8601 Instant>

虚拟时间的起点。运行期只使用虚拟时间，不读取系统当前时间。

### SEED

    SEED <long>

根种子，写入 `run-summary.txt` 的 `rootSeed`。

### EXTERNAL

    EXTERNAL <scriptId> <CREATE|QUERY> <ordinal> <outcome> <externalOrderId|->

**CREATE 允许的 outcome：**

    ACCEPTED
    REJECTED
    TIMEOUT_BUT_CREATED
    TIMEOUT_NOT_CREATED

**QUERY 允许的 outcome：**

    ACCEPTED
    NOT_FOUND

ACCEPTED 和 TIMEOUT_BUT_CREATED 必须提供 externalOrderId；其余使用 `-`。

### AT

    AT <offsetMillis> <logicalSequence> <eventId> <action> [args...]

### EXPECT

    EXPECT <oracleId> <ruleType> [args...]

### END

必须是非注释记录中的最后一条。

## action 和参数

| action | 参数数量 | 参数 |
|---|---:|---|
| POINT_ADD | 2 | pointId, name |
| APPLICATION_ADD | 3 | applicationId, fromPointId, toPointId |
| APPLICATION_DISPATCH | 1 | applicationId |
| APPLICATION_QUERY | 1 | applicationId |
| APPLICATION_CALLBACK | 2 | applicationId, STARTED/COMPLETED/FAILED |
| OUTBOX_PROCESS | 0 | — |
| WORKFLOW_START | 1 | applicationId |
| WORKFLOW_ADVANCE | 1 | applicationId |
| DESTINATION_ADD | 2 | destinationId, name |
| SERVICE_POINT_ADD | 3 | servicePointId, destinationId, pointId |
| RESOURCE_ADD | 2 | resourceId, servicePointId |
| RESOURCE_SET | 2 | resourceId, ENABLED/DISABLED |
| RESERVATION_REQUEST | 4 | applicationId, destinationId, servicePointId, resourceId |
| RESERVATION_RELEASE | 1 | applicationId |
| RESERVATION_CANCEL | 1 | applicationId |
| WORLD_RESTART | 0 | — |

> STU-SIM-02-R1 §12.1：资源领域固定 8 个规范 Action，名称与参数逐字固定。
> 资源新增动作的规范名是 `RESOURCE_ADD`；旧名 `VIRTUAL_RESOURCE_ADD` 已删除（历史旧规范），
> Parser 不再接受该写法。

### Parser 运行前检查（资源 DSL，§12.3）

Parser 在运行前完成，不把校验留到 Oracle 运行时：

- 参数数量：按上表固定；
- ID 语法：参数位 ID 必须匹配 `[A-Za-z0-9][A-Za-z0-9_-]{0,63}`；
- enum allowlist：`RESOURCE_SET` 只接受 `ENABLED` / `DISABLED`；
- `HELD_COUNT` / `WAITING_COUNT`：必须是非负整数且不超过 int 范围（负数 / 非整数 / 溢出拒绝）；
- `WAIT_QUEUE`：队列参数是 `NONE` 或逗号分隔的 applicationId 列表，不允许空 token、不允许重复 applicationId；
- `WORLD_RESTART` 与 `NO_ORPHAN_RESERVATION` 不接受参数；
- 未知 Action / 未知 EXPECT 稳定失败（`SCENARIO_UNKNOWN_ACTION` / `SCENARIO_UNKNOWN_ORACLE`）；
- 引用存在性不在 Parser 阶段检查（对象可能由前序事件创建），由事件执行期检查。

> Part B 的 8 个 action 由运行时按事件分发。`WORLD_RESTART` 保留同一 Storage / Clock / Fake，
> 丢弃 Registry 与 Target 后用 `loadState()` 重建，并记录重载前后的规范快照摘要；不触发外部调用。

## EXPECT 类型

| ruleType | 参数数量 | 参数 |
|---|---:|---|
| APPLICATION_STATE | 2 | applicationId, state |
| OUTBOX_STATUS | 2 | applicationId, status |
| CREATE_CALLS | 2 | applicationId, count |
| QUERY_CALLS | 2 | applicationId, count |
| VALID_EXTERNAL_ORDERS | 2 | applicationId, count |
| NO_DUPLICATE_EXTERNAL_ORDER | 1 | applicationId |
| RESERVATION_STATE | 2 | applicationId, WAITING/HELD/RELEASED/CANCELLED/MISSING |
| RESERVATION_DECISION | 2 | eventId, HELD/WAITING/IDEMPOTENT/CONFLICT |
| RESOURCE_AVAILABILITY | 2 | resourceId, ENABLED/DISABLED/MISSING |
| RESOURCE_HOLDER | 2 | resourceId, applicationId/NONE |
| WAIT_QUEUE | 2 | resourceId, commaSeparatedIds/NONE（按固定排序键输出） |
| HELD_COUNT | 2 | resourceId, 0/1 |
| NO_DOUBLE_HOLD | 2 | applicationId, PASS/FAIL |
| NO_ORPHAN_RESERVATION | 0 | — |

> STU-SIM-02-R1 §12.2：以上资源领域固定 8 个规范 EXPECT（`RESERVATION_STATE` /
> `RESERVATION_DECISION` / `RESOURCE_AVAILABILITY` / `RESOURCE_HOLDER` / `WAIT_QUEUE` /
> `HELD_COUNT` / `NO_DOUBLE_HOLD` / `NO_ORPHAN_RESERVATION`）在运行时始终启用。
> `WAIT_QUEUE` 按 `requestedOffsetMillis → requestedLogicalSequence → applicationId` 固定排序键输出，
> 空队列写 `NONE`。0 条或重复条目一律 FAIL（`MISSING` / `DUPLICATE_*`），不得静默 PASS。

**ORC-RES-EXT 扩展 EXPECT（保留，不属于固定 8 项，不得替代固定合同）：**

| ruleType | 参数数量 | 参数 |
|---|---:|---|
| WAITING_COUNT | 2 | resourceId, count |
| QUEUE_ORDER | ≥2 | resourceId, applicationId...（按队列顺序） |
| QUEUE_EMPTY | 1 | resourceId |
| WORLD_RESTART_CONSISTENT | 0 | — |
| SINGLE_CAPACITY | 1 | resourceId |

## Parser 稳定错误码

`SimulationMain validate/run` 遇到这些错误时输出 `ERR <code> [detail]` 并以退出码 2 结束。

| code | 含义 |
|---|---|
| SCENARIO_FILE_NOT_FOUND | 文件不存在或不是普通文件 |
| SCENARIO_FILE_TOO_LARGE | 文件超过 1 MiB |
| SCENARIO_ENCODING | 编码问题（BOM/NUL/非 UTF-8） |
| SCENARIO_HEADER_MISSING | SCENARIO / START_AT / SEED 缺失或格式错 |
| SCENARIO_UNSUPPORTED_VERSION | version 不是当前支持的 1 |
| SCENARIO_END_MISSING | END 缺失或不是最后一条 |
| SCENARIO_EVENT_ORDER | 时间倒退或同键重复 |
| SCENARIO_DUPLICATE_EVENT | eventId / scriptId / oracleId 重复 |
| SCENARIO_SCRIPT_ORDINAL | ordinal 不连续或从 0 开始 |
| SCENARIO_UNKNOWN_ACTION | action 或 outcome 不合法 |
| SCENARIO_UNKNOWN_ORACLE | ruleType 不合法 |
| SCENARIO_ARGUMENT_COUNT | 参数数量错误 |
| SCENARIO_ARGUMENT_VALUE | 参数取值非法（枚举 / 计数） |
| TIME_TRAVEL_BACKWARD | 运行时时钟倒退（运行期，退出码 1） |
| UNSCRIPTED_EXTERNAL_CALL | 未脚本化的外部调用（运行期，退出码 1） |

## Evidence 目录（7 个文件）

一次 `run` 在目标目录发布且只发布以下 7 个文件（目录必须是普通目录，文件必须是普通文件，软链接一律拒绝）：

| # | 文件 | 说明 |
|---:|---|---|
| 1 | `scenario.scenario` | 被执行场景的原始字节副本 |
| 2 | `scenario.sha256` | 单行：`<64 位小写 hex>  scenario.scenario` |
| 3 | `run-summary.txt` | 21 个固定字段，每行 `key=value`，顺序不可变 |
| 4 | `event-timeline.tsv` | 事件时间线，表头固定 |
| 5 | `external-interactions.tsv` | 外部交互时间线，表头固定 |
| 6 | `oracle-results.tsv` | 六条系统不变量 + 场景 EXPECT 的结果 |
| 7 | `manifest.sha256` | 恰好 6 行，不含自身，按文件名升序 |

发布方式：同父目录建 `.staging-<UUID>` → 写 6 个内容文件 + manifest → 发布前校验 → `Files.move(ATOMIC_MOVE)` → 发布后校验。目标目录已存在时直接拒绝（`EVIDENCE_DIR_EXISTS`），不复用、不覆盖、不合并。

### run-summary.txt：21 个固定字段

`schemaVersion=2`。字段顺序固定，校验器逐行核对 `key=` 前缀，并核对 `plannedEventCount == eventPassCount + eventFailCount + eventSkippedCount`、`executedEventCount == eventPassCount + eventFailCount`。

| # | 字段 | 说明 |
|---:|---|---|
| 1 | `scenarioId` | 场景编号 |
| 2 | `scenarioVersion` | 场景版本 |
| 3 | `scenarioDigest` | `scenario.scenario` 原始字节的 SHA-256（小写 hex） |
| 4 | `rootSeed` | 场景 SEED |
| 5 | `virtualStartAt` | 场景 START_AT |
| 6 | `schemaVersion` | 证据结构版本，固定 `2` |
| 7 | `runStatus` | `COMPLETED` / `FAILED` / `INCONCLUSIVE` |
| 8 | `plannedEventCount` | 计划事件数 |
| 9 | `executedEventCount` | 实际执行事件数（首错即停后不再增加） |
| 10 | `eventPassCount` | 通过事件数 |
| 11 | `eventFailCount` | 失败事件数 |
| 12 | `eventSkippedCount` | 跳过事件数 |
| 13 | `firstFailureEventId` | 首个失败事件编号；无失败时为 `-` |
| 14 | `firstFailureCode` | 首个失败稳定 code；无失败时为 `-` |
| 15 | `oraclePassCount` | Oracle 结果 PASS 数 |
| 16 | `oracleFailCount` | Oracle 结果 FAIL 数 |
| 17 | `createCallCount` | CREATE 外部调用次数 |
| 18 | `queryCallCount` | QUERY 外部调用次数 |
| 19 | `validExternalOrderCount` | 有效外部订单数 |
| 20 | `evidenceLevel` | 证据等级，固定 `LOCAL_SIMULATION` |
| 21 | `realExternalWriteCount` | 真实外部写次数，固定 `0` |

### event-timeline.tsv

表头逐字固定：

    eventId	plannedOffsetMillis	logicalSequence	action	status	virtualOccurredAt	detail

- 表头之外的行数必须等于 `plannedEventCount`；
- 每行的 `status` 为该事件的执行结果，`detail` 为失败 code 或补充说明（可为空）。

### external-interactions.tsv

表头逐字固定：

    sequence	virtualOccurredAt	operation	ordinal	applicationId	outcome	sideEffectCertainty	externalOrderId	scriptId

- `operation`：`CREATE` / `QUERY`；
- `outcome`：场景脚本声明的结果；
- `sideEffectCertainty`：副作用确定性；
- `externalOrderId`：无外部订单时为 `-`；
- `scriptId`：命中的脚本编号；未命中时记录到 `oracle-results.tsv` 的 `ORC-SYS-001` 失败详情。

### oracle-results.tsv

表头逐字固定：

    oracleId	ruleType	status	expected	actual	detail

六条系统不变量固定存在：`ORC-SYS-001` 外部调用全部有脚本、`ORC-SYS-002` 同一申请最多一个有效外单、`ORC-SYS-003` Unknown 先对账、`ORC-SYS-004` 终态不倒退、`ORC-SYS-005` Evidence 不越级、`ORC-SYS-006` 零真实外部写。

六条资源系统不变量也固定存在（STU-SIM-02-R1 §13.1 固定绑定，始终启用、真算，不可由场景关闭）：

| oracleId | ruleType | 真实计算口径 |
|---|---|---|
| ORC-RES-SYS-001 | SINGLE_HELD_PER_RESOURCE | 每个 resource 的 HELD 数量 ≤ 1；检查运行中记录的每个已提交快照 |
| ORC-RES-SYS-002 | ACTIVE_PER_APPLICATION | 每个 application 的非终态 Reservation 数量 ≤ 1；检查每个已提交快照 |
| ORC-RES-SYS-003 | REFERENCE_CHAIN | 每条 Reservation 的 Destination/ServicePoint/Resource 引用链有效；检查每个已提交快照 |
| ORC-RES-SYS-004 | WAIT_QUEUE_SORTED_UNIQUE | 每个 WAITING 队列按 `requestedOffsetMillis → requestedLogicalSequence → applicationId` 排序且无重复；检查每个已提交快照 |
| ORC-RES-SYS-005 | TERMINAL_MONOTONIC | RELEASED / CANCELLED 之后不得再变为 WAITING / HELD；按状态历史检查 |
| ORC-RES-SYS-006 | RESTART_REPLAY | 逐对比较 `WORLD_RESTART` 前后规范快照的 SHA-256；无重启时 `restartPairs=0,mismatches=0` 且 PASS |

> 001—004 不得只看最终快照：必须以运行过程中记录的已提交快照（事件 after 快照 + 最终快照）逐点计算。
> `actual` 必须包含真实计数或对象 ID（例如 `applicationId=APP-001;activeCount=2`），不能只写 `true`。
> Oracle 只读，不调用 Registry 写入口，也不读取场景 EXPECT 决定 PASS/FAIL。

**ORC-RES-EXT 扩展检查（保留，不属于固定六条）：**

| oracleId | ruleType | 真实计算口径 |
|---|---|---|
| ORC-RES-EXT-001 | IDEMPOTENT_STABLE | IDEMPOTENT 请求前后，预约编号、状态与排序键逐字段不变 |
| ORC-RES-EXT-002 | DISABLE_KEEPS_HOLDER | `RESOURCE_SET ... DISABLED` 前后该资源的 HELD 持有者集合不变 |
| ORC-RES-EXT-003 | PROMOTE_HEAD_ONLY | 晋升只能取队列最小键，不能越过队首 |

`ORC-RES-SYS-006` 的 `actual` 必须是 `restartPairs=<n>,mismatches=<m>`（例如 `restartPairs=2,mismatches=0`），`expected` 为 `restartPairs=<n>,mismatches=0`；禁止只写 `true` 或硬编码 PASS。

六条 ORC-SYS 与六条 ORC-RES-SYS、三条 ORC-RES-EXT 之后，才追加场景 `EXPECT`；结果按 `oracleId` 升序排列。

### manifest.sha256

恰好 6 行，每行 `<64 位小写 hex>␠␠<纯文件名>`，文件名升序且精确等于：

    event-timeline.tsv
    external-interactions.tsv
    oracle-results.tsv
    run-summary.txt
    scenario.scenario
    scenario.sha256

禁止绝对路径、`..`、子目录、空文件名；逐文件重算 SHA-256 必须与 manifest 相等。

### 三方摘要一致

`SHA-256(scenario.scenario)` 必须同时等于 `scenario.sha256` 中的值、`run-summary.txt` 的 `scenarioDigest=` 值。

## Evidence 校验稳定错误码

`EvidenceBundleValidator` 与 `EvidenceBundleWriter` 失败时输出 `RUN_INCONCLUSIVE <scenarioId> <code>`（退出码 3），或由 `compare` 输出 `REPLAY_INVALID_EVIDENCE A:<code>` / `B:<code>`（退出码 2）：

| code | 含义 |
|---|---|
| EVIDENCE_DIR_EXISTS | 目标目录已存在 |
| EVIDENCE_DIR_NOT_FOUND | 目录不存在 |
| EVIDENCE_DIR_NOT_DIRECTORY | 目录不是普通目录（含软链接） |
| EVIDENCE_FILE_SET | 直接子项不等于 7 个文件 |
| EVIDENCE_FILE_NOT_REGULAR | 文件不是普通文件 / 软链接 / 读失败 |
| EVIDENCE_MANIFEST_LINES | manifest 不是恰好 6 行 |
| EVIDENCE_MANIFEST_FORMAT | manifest 行格式错或含非法文件名 |
| EVIDENCE_MANIFEST_ORDER | manifest 文件名不是升序约定集合 |
| EVIDENCE_MANIFEST_MISMATCH | 重算摘要与 manifest 不一致 |
| EVIDENCE_SUMMARY_LINES | run-summary 不是恰好 21 行 |
| EVIDENCE_SUMMARY_FIELDS | 字段名 / 顺序 / 取值格式错 |
| EVIDENCE_SUMMARY_ARITHMETIC | 计数算术不成立 |
| EVIDENCE_TIMELINE_HEADER | event-timeline 表头不固定 |
| EVIDENCE_TIMELINE_ROWS | 行数与 plannedEventCount 不符 |
| EVIDENCE_SCENARIO_DIGEST_FORMAT | scenario.sha256 格式错 |
| EVIDENCE_DIGEST_MISMATCH | 三方摘要不一致 |

## 回放比较规则（`compare`）

1. 先校验 A → 非法输出 `REPLAY_INVALID_EVIDENCE A:<code>`，退出码 2；
2. 再校验 B → `REPLAY_INVALID_EVIDENCE B:<code>`，退出码 2；
3. 比较 `scenarioDigest`，不同不得 MATCH；
4. 逐字节比较 `scenario.scenario`、`run-summary.txt`、`event-timeline.tsv`、`external-interactions.tsv`、`oracle-results.tsv`，首个不同输出 `REPLAY_DIFFERENT <file>`，退出码 1；
5. 全部相同输出 `REPLAY_MATCH <scenarioId> version=<n>`，退出码 0。

## 兼容性和版本升级规则

- scenarioId 变更 → 新场景
- version 变更 → 语义变化时增加
- 已执行场景不得覆盖原文件
- 兼容性检查以 digest 为准
- `schemaVersion` 变更 → 旧证据包不再通过新校验器（本项目不跟踪旧证据包；交付包一律用新 writer 重新生成）

FINAL_SOURCE_IDENTITY=BOUND_BY_EXTERNAL_DELIVERY_RECEIPT
DELIVERY_RECEIPT=delivery-receipt.txt
