# Requirement 追踪表

| 文档属性 | 内容 |
|---|---|
| 版本 | V0.1 |
| 日期 | 2026-09-21 |
| 类型 | 练习项目追踪表 |
| 对应正式文档 | IDS v0.1 MVP、CAP、MVP-REQ |
| 状态 | DRAFT |

## 文档说明

本文件把练习项目 `logistics-intake-practice` 的实现与正式 IDS 文档的 Requirement 连起来。

**它做什么：**

- 一行一条记录，说明"正式要求"对应"练习实现"
- 标注每条 Requirement 在练习里的覆盖程度
- 标记哪些 Requirement 练习里未实现

**它不做什么：**

- 不替代正式需求注册表
- 不声称练习实现等于正式产品完成
- 不把练习里的简化实现当作正式契约

依据：

- IDS v0.1 MVP 第 5 节 MVP-REQ-01..22
- IDS 产品定义 第 7 节 CAP-01..19
- 练习任务书第 8.6 节 R01—R16

## 状态说明

| 状态 | 含义 |
|---|---|
| FULL | 练习里已完整实现并有验证 |
| PARTIAL | 练习里实现了一部分，行为简化 |
| NONE | 练习里未实现 |
| N/A | 练习范围外，不适用 |

## MVP-REQ 追踪

| MVP-REQ | 正式要求简述 | 练习对应 | 检查项 | 状态 |
|---|---|---|---|---|
| MVP-REQ-01 | 统一入口与请求证据 | application add 幂等 + 并发保护 | R01—R05, R29—R32 | PARTIAL |
| MVP-REQ-02 | 稳定身份与外部映射 | externalOrderId | R33, R35 | PARTIAL |
| MVP-REQ-03 | 任务状态和不变量 | 申请状态机 | R17—R27 | PARTIAL |
| MVP-REQ-04 | 目的地解析 | 无 | — | NONE |
| MVP-REQ-05 | 资源预约 | 无 | — | NONE |
| MVP-REQ-06 | 基础优先级 | 无 | — | NONE |
| MVP-REQ-07 | 基础准入 | 无 | — | NONE |
| MVP-REQ-08 | 车辆能力和候选集合 | 无 | — | NONE |
| MVP-REQ-09 | RCS Port 与 M4Adapter | RcsExecutionPort + FakeRcsAdapter | R33—R40 | PARTIAL |
| MVP-REQ-10 | 真实 M4 合同 | 无（不连接真实 M4） | — | NONE |
| MVP-REQ-11 | 回调、查询和对账 | query 对账 | R35, R36 | PARTIAL |
| MVP-REQ-12 | 取消过程 | cancel 转换 | R26, R39 | PARTIAL |
| MVP-REQ-13 | 业务完成 | complete 转换 | R23 | PARTIAL |
| MVP-REQ-14 | 最小 Workflow | 无 | — | NONE |
| MVP-REQ-15 | SiteProfile 与配置版本 | 无 | — | NONE |
| MVP-REQ-16 | 持久化与 Migration | MemoryStorage / FileStorage / Outbox | R41—R45 | PARTIAL |
| MVP-REQ-17 | 运营界面 | 无 | — | NONE |
| MVP-REQ-18 | 告警、审计和诊断证据 | 无 | — | NONE |
| MVP-REQ-19 | 安全基线 | 无 | — | NONE |
| MVP-REQ-20 | 可观测性 | 无 | — | NONE |
| MVP-REQ-21 | Fake 与故障注入 | FakeRcsAdapter 四种模式 | R33—R40 | PARTIAL |
| MVP-REQ-22 | 部署、重启与回滚 | 手动重启 + outbox process | R45 | PARTIAL |

## CAP 追踪

| CAP | 能力简述 | 练习对应 | 状态 |
|---|---|---|---|
| CAP-01 | Request Intake | application add | PARTIAL |
| CAP-02 | Task Lifecycle | 申请状态机 | PARTIAL |
| CAP-03 | Workflow Runtime | 无 | NONE |
| CAP-04 | Destination / Resource / Reservation | 无 | NONE |
| CAP-05 | Priority / Admission | 无 | NONE |
| CAP-06 | Vehicle Capability | 无 | NONE |
| CAP-07 | Zone / Pool | 无 | NONE |
| CAP-08 | World / Map / Shared Resource | 无 | NONE |
| CAP-09 | Scheduling | 无 | NONE |
| CAP-10 | RCS Execution Integration | RcsExecutionPort | PARTIAL |
| CAP-11 | MES / LineSide 集成 | 无 | NONE |
| CAP-12 | Operations / Monitoring / Manual | 无 | NONE |
| CAP-13 | SiteProfile / Configuration | 无 | NONE |
| CAP-14 | Data / SoT / Event / Audit | 持久化 + Outbox | PARTIAL |
| CAP-15 | Security / Administration | 无 | NONE |
| CAP-16 | Reliability / HA / DR | Outbox 恢复 | PARTIAL |
| CAP-17 | Deployment / Upgrade / Rollback | 无 | NONE |
| CAP-18 | Simulation / Replay / Shadow | Fake 模式 | PARTIAL |
| CAP-19 | Capacity Engineering | 无 | NONE |

## 命令样例追踪

| 样例 | 覆盖内容 | 状态 |
|---|---|---|
| points | 点位登记与查询 | FULL |
| happy-path | 完整正常流程 | FULL |
| rejection-and-replay | 重复、冲突、拒绝 | FULL |
| boundaries | 命令结构与输入边界 | FULL |
| state-machine | 申请状态机 | FULL |
| persistence-1 / 2 | 文件持久化 | FULL |
| fake-accept | Fake 接受 | FULL |
| fake-reject | Fake 拒绝 | FULL |
| fake-timeout-created | 超时但已创建 | FULL |
| fake-timeout-not-created | 超时未创建 | FULL |
| outbox-basic | Outbox 基本记录 | FULL |
| outbox-process-1 / 2 | Outbox 恢复路径 | FULL |
| concurrency | 幂等并发 | FULL |

## RegistryChecks 追踪

| 检查 | 覆盖 | 状态 |
|---|---|---|
| R01—R16 | 点位、申请、查询、编号、名称、顺序、快照 | FULL |
| R17—R27 | 状态机转换与终态 | FULL |
| R29—R32 | 并发幂等 | FULL |
| R33—R40 | Fake M4 四种模式与对账 | FULL |
| R41—R45 | Outbox 记录与恢复 | FULL |

## 未实现清单

以下正式要求，练习里未实现，需要后续工作：

| 类别 | 未实现项 |
|---|---|
| 身份 | transportRequestId、transportTaskId、executionOrderId、executionAttemptId |
| 资源 | Reservation、Resource 图、Resource 互斥 |
| 调度 | Priority、Admission、Candidate Decision |
| 目的 | ServicePoint、Map / Scene 版本 |
| 执行 | ExecutionOrder、ExecutionAttempt、RcsInteraction |
| 工作流 | WorkflowInstance、WorkflowStep |
| 配置 | SiteProfile、Workflow Definition Version |
| 运营 | Timeline、Alarm、ManualWorkItem、AuditEvent |
| 安全 | RBAC、Secret、Callback 验证 |
| 可观测 | 结构化日志、Metrics、Trace |
| 数据 | 数据库 Migration、Inbox、Callback Inbox |
| 部署 | 可重复部署包、Backup / Restore、Rollback |

## 变更记录

| 版本 | 日期 | 说明 |
|---|---|---|
| V0.1 | 2026-09-21 | 初稿 |
