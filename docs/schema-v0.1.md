# v0.1 数据 Schema 草案

| 文档属性 | 内容 |
|---|---|
| 版本 | V0.1 |
| 日期 | 2026-09-21 |
| 类型 | 练习项目数据模型草案 |
| 对应正式文档 | IDS v0.1 MVP、CAP-14 |
| 状态 | DRAFT |

## 文档说明

本文件是练习项目 `logistics-intake-practice` 的数据模型草案。

**它做什么：**

- 定义 v0.1 核心实体的字段、类型、约束、索引
- 标记每张表对应哪个 MVP-REQ 或 CAP
- 区分"练习已实现"和"正式项目需要"

**它不做什么：**

- 不执行 Migration
- 不定义 Java 类
- 不定义 API 路径
- 不定义数据库产品

依据：

- IDS v0.1 MVP 第 6.2 节 数据边界
- IDS v0.1 MVP 第 2.1 节 MUST 清单
- CAP-14 Data、SoT、Event 与 Audit
- MVP-REQ-16 持久化与 Migration

## 表清单

### 练习已实现

| 表 | 说明 |
|---|---|
| Point | 点位记录 |
| TransferApplication | 搬运申请记录 |
| OutboxEntry | 待发送外部命令 |

### 正式项目需要但练习未实现

| 表 | 对应 MVP-REQ | 说明 |
|---|---|---|
| RequestEnvelope | MVP-REQ-01 | 原始请求信封 |
| IdempotencyRecord | MVP-REQ-01 | 幂等记录 |
| ExecutionAttempt | MVP-REQ-02 | 外部执行尝试 |
| ExternalOrderMapping | MVP-REQ-02 | IDS 与外部订单映射 |
| RcsInteraction | MVP-REQ-09 | 一次外部协议交互 |
| Reservation | MVP-REQ-05 | 资源预约 |
| WorkflowInstance | MVP-REQ-14 | 工作流实例 |
| WorkflowStep | MVP-REQ-14 | 工作流步骤 |
| StateEvent | MVP-REQ-03 | 状态事件 |
| Alarm | MVP-REQ-18 | 告警 |
| ManualWorkItem | MVP-REQ-18 | 人工工作项 |
| AuditEvent | MVP-REQ-18 | 审计事件 |
| SiteProfile | MVP-REQ-15 | 站点配置 |

## 逐表定义（练习已实现）

### Point

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| point_id | VARCHAR(32) | PRIMARY KEY | 点位编号，格式 `[A-Z][A-Z0-9_-]{0,31}` |
| name | VARCHAR(40) | NOT NULL | 显示名称，不含空白字符 |

**索引**

- PRIMARY KEY (point_id)

**业务约束**

- point_id 匹配 `[A-Z][A-Z0-9_-]{0,31}`
- name 长度 1—40
- name 不含空白字符

**来源** MVP-REQ-16 持久化与 Migration

**练习状态** 已实现（MemoryStorage + FileStorage）

**正式项目状态** 与 core ServicePoint 有差异，需后续对齐

---

### TransferApplication

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| application_id | VARCHAR(32) | PRIMARY KEY | 申请编号，格式同 Point |
| from_point_id | VARCHAR(32) | NOT NULL, FK → Point.point_id | 起点编号 |
| to_point_id | VARCHAR(32) | NOT NULL, FK → Point.point_id | 终点编号 |
| state | VARCHAR(16) | NOT NULL | CREATED / DISPATCHED / EXECUTING / COMPLETED / CANCELLED / UNKNOWN |
| external_order_id | VARCHAR(64) | NULL | 外部订单号，未下发时为 null |

**索引**

- PRIMARY KEY (application_id)
- INDEX (from_point_id)
- INDEX (to_point_id)
- INDEX (state)

**业务约束**

- from_point_id ≠ to_point_id
- from_point_id 和 to_point_id 必须在 Point 里存在
- state 只能是 ApplicationState 的六个值之一
- COMPLETED 和 CANCELLED 是终态，不允许继续转换
- external_order_id 在 state 为 DISPATCHED / EXECUTING / COMPLETED 时才可能有值

**来源** MVP-REQ-03、MVP-REQ-16

**练习状态** 已实现

**正式项目状态** 与 TransportTask 有差异，需后续对齐

**状态转换表**

| 起始状态 | 允许到达 |
|---|---|
| CREATED | DISPATCHED, CANCELLED, UNKNOWN |
| DISPATCHED | EXECUTING, CANCELLED |
| EXECUTING | COMPLETED, CANCELLED |
| UNKNOWN | DISPATCHED, CANCELLED |
| COMPLETED | 无 |
| CANCELLED | 无 |

---

### OutboxEntry

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| entry_id | VARCHAR(64) | PRIMARY KEY | Outbox 记录编号 |
| application_id | VARCHAR(32) | NOT NULL, FK → TransferApplication.application_id | 关联的申请 |
| from_point_id | VARCHAR(32) | NOT NULL | 冗余起点 |
| to_point_id | VARCHAR(32) | NOT NULL | 冗余终点 |
| status | VARCHAR(16) | NOT NULL | PENDING / SENT / FAILED |
| external_order_id | VARCHAR(64) | NULL | 成功后记录的外部订单号 |
| attempts | INT | NOT NULL, DEFAULT 0 | 尝试次数 |

**索引**

- PRIMARY KEY (entry_id)
- INDEX (status)
- INDEX (application_id)

**业务约束**

- status 只能是 PENDING / SENT / FAILED
- status = SENT 时，external_order_id 必须有值
- status = PENDING 或 FAILED 时，external_order_id 为 null
- attempts ≥ 0
- application_id 必须指向已存在的 TransferApplication

**来源** MVP-REQ-16 持久化与 Migration

**练习状态** 已实现

**正式项目状态** 与正式 Outbox 有差异，需后续对齐

**状态转换表**

| 起始状态 | 允许到达 | 触发 |
|---|---|---|
| PENDING | SENT | 外部返回 ACCEPTED |
| PENDING | FAILED | 外部返回 REJECTED |
| PENDING | PENDING | 外部返回 TIMEOUT，attempts+1 |

---

## 身份与命名空间

```text
point_id           [A-Z][A-Z0-9_-]{0,31}
application_id     [A-Z][A-Z0-9_-]{0,31}
entry_id           练习里用 OBX-<applicationId>
external_order_id  练习里用 EXT-<applicationId>

## 变更记录

| 版本 | 日期 | 说明 |
|---|---|---|
| V0.1 | 2026-09-21 | 初稿，覆盖练习已实现的三张表 |
| V0.1.1 | 2026-09-21 | 建立 docs 分支 PR 记录 |
