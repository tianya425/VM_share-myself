# 仿真场景格式

| 文档属性 | 内容 |
|---|---|
| 版本 | V0.1 |
| 日期 | 2026-10-04 |
| 类型 | 场景文件格式说明 |
| 对应正式文档 | IDS 仿真、回放与验证架构说明书 |
| 状态 | DRAFT |

## 文档说明

本文件定义 `scenarios/*.scenario` 的语法规则。

**它做什么：**

- 说明每条记录类型的字段和顺序
- 说明允许的 action、outcome 和 oracle 类型
- 说明稳定错误码

**它不做什么：**

- 不定义运行时行为（见 `simulation-boundary.md`）
- 不定义 Evidence 结构（第三阶段）

## 文件编码

- UTF-8
- LF 换行
- 无 BOM
- 无 NUL

## 记录类型

### SCENARIO

    SCENARIO <scenarioId> <version>

- scenarioId：字母数字开头，允许大写字母、数字、下划线、连字符，最长 64
- version：≥ 1

### START_AT

    START_AT <ISO-8601 Instant>

### SEED

    SEED <long>

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

## EXPECT 类型

| ruleType | 参数数量 | 参数 |
|---|---:|---|
| APPLICATION_STATE | 2 | applicationId, state |
| OUTBOX_STATUS | 2 | applicationId, status |
| CREATE_CALLS | 2 | applicationId, count |
| QUERY_CALLS | 2 | applicationId, count |
| VALID_EXTERNAL_ORDERS | 2 | applicationId, count |
| NO_DUPLICATE_EXTERNAL_ORDER | 1 | applicationId |

## 稳定错误码

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
| TIME_TRAVEL_BACKWARD | 运行时时钟倒退 |
| UNSCRIPTED_EXTERNAL_CALL | 未脚本化的外部调用 |

## 兼容性和版本升级规则

- scenarioId 变更 → 新场景
- version 变更 → 语义变化时增加
- 已执行场景不得覆盖原文件
- 兼容性检查以 digest 为准
