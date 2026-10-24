# Requirement 追踪表

| 文档属性 | 内容 |
|---|---|
| 版本 | V0.2 |
| 日期 | 2026-10-24 |
| 类型 | 练习项目追踪表 |
| 对应正式文档 | IDS v0.1 MVP、CAP、MVP-REQ |
| 状态 | FROZEN |

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
| MVP-REQ-01 | 统一入口与请求证据 | application add 幂等 + 并发保护 + 仿真证据包 | R01—R05, R29—R32 | PARTIAL |
| MVP-REQ-02 | 稳定身份与外部映射 | externalOrderId | R33, R35 | PARTIAL |
| MVP-REQ-03 | 任务状态和不变量 | 申请状态机 + 6 条系统不变量 | R17—R27, RUN-13—18 | PARTIAL |
| MVP-REQ-04 | 目的地解析 | 无 | — | NONE |
| MVP-REQ-05 | 资源预约 | 无 | — | NONE |
| MVP-REQ-06 | 基础优先级 | 无 | — | NONE |
| MVP-REQ-07 | 基础准入 | 无 | — | NONE |
| MVP-REQ-08 | 车辆能力和候选集合 | 无 | — | NONE |
| MVP-REQ-09 | RCS Port 与 M4Adapter | RcsExecutionPort + FakeRcsAdapter + ScriptedFakeRcsAdapter | R33—R40 | PARTIAL |
| MVP-REQ-10 | 真实 M4 合同 | 无（不连接真实 M4，本轮不适用） | — | N/A |
| MVP-REQ-11 | 回调、查询和对账 | query 对账 + callback + Outbox 恢复先 query | R35, R36 | PARTIAL |
| MVP-REQ-12 | 取消过程 | cancel 转换 | R26, R39 | PARTIAL |
| MVP-REQ-13 | 业务完成 | complete 转换 | R23 | PARTIAL |
| MVP-REQ-14 | 最小 Workflow | 固定 3 步（DISPATCH / EXECUTE / COMPLETE），步骤完成绑定申请状态 | R1-REL-09/14 | PARTIAL |
| MVP-REQ-15 | SiteProfile 与配置版本 | 无 | — | NONE |
| MVP-REQ-16 | 持久化与 Migration | MemoryStorage / FileStorage / SqliteStorage + RegistryState 原子提交 + MigrationRunner 单事务 | R41—R45, R1-REL-03—15 | PARTIAL |
| MVP-REQ-17 | 运营界面 | 无 | — | NONE |
| MVP-REQ-18 | 告警、审计和诊断证据 | 无（只有仿真证据包，不是审计日志） | — | NONE |
| MVP-REQ-19 | 安全基线 | 只读模式（五层拒写）+ `docs/security-baseline.md` | R1-REL-10/11 | PARTIAL |
| MVP-REQ-20 | 可观测性 | `stats` + `health` | R41—R45 | PARTIAL |
| MVP-REQ-21 | Fake 与故障注入 | FakeRcsAdapter 四种模式 + 场景脚本化 Fake | R33—R40, SCN-01—16 | PARTIAL |
| MVP-REQ-22 | 部署、重启与回滚 | 手动重启 + `outbox process` + Migration 版本管理 | R45 | PARTIAL |

## CAP 追踪

| CAP | 能力简述 | 练习对应 | 状态 |
|---|---|---|---|
| CAP-01 | Request Intake | application add | PARTIAL |
| CAP-02 | Task Lifecycle | 申请状态机 | PARTIAL |
| CAP-03 | Workflow Runtime | WorkflowInstance / WorkflowStep（固定 3 步） | PARTIAL |
| CAP-04 | Destination / Resource / Reservation | 无 | NONE |
| CAP-05 | Priority / Admission | 无 | NONE |
| CAP-06 | Vehicle Capability | 无 | NONE |
| CAP-07 | Zone / Pool | 无 | NONE |
| CAP-08 | World / Map / Shared Resource | 无 | NONE |
| CAP-09 | Scheduling | 无 | NONE |
| CAP-10 | RCS Execution Integration | RcsExecutionPort | PARTIAL |
| CAP-11 | MES / LineSide 集成 | 无 | NONE |
| CAP-12 | Operations / Monitoring / Manual | `stats` + `health` | PARTIAL |
| CAP-13 | SiteProfile / Configuration | 无 | NONE |
| CAP-14 | Data / SoT / Event / Audit | 三存储 + Outbox + Inbox | PARTIAL |
| CAP-15 | Security / Administration | 只读模式 + 安全基线文档 | PARTIAL |
| CAP-16 | Reliability / HA / DR | Outbox 恢复 + 原子提交 + health + readonly | PARTIAL |
| CAP-17 | Deployment / Upgrade / Rollback | 无（只有 Migration 版本管理） | NONE |
| CAP-18 | Simulation / Replay / Shadow | 本地确定性仿真 + 证据包 + 回放；Shadow 未做 | PARTIAL |
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
| fake-accept / fake-reject | Fake 接受与拒绝 | FULL |
| fake-timeout-created / fake-timeout-not-created | 超时两种形态 | FULL |
| outbox-basic / outbox-process-1 / 2 | Outbox 记录与恢复 | FULL |
| inbox-basic / inbox-duplicate / inbox-reject | Inbox 接收、幂等、拒绝 | FULL |
| workflow-basic / workflow-reject | Workflow 正向与拒绝 | FULL |
| sqlite-basic-1 / 2 / sqlite-reject | SQLite 持久化与拒绝 | FULL |
| migration-basic / migration-persistence | Migration 版本与持久化 | FULL |
| stats-basic / stats-mixed | 统计 | FULL |
| health-basic / health-warn | 健康检查 | FULL |
| readonly-basic / readonly-write-rejected | 只读模式 | FULL |
| concurrency | 幂等并发 | FULL |
| sim-001 / sim-002 / sim-003 | 确定性仿真三个合法场景 | FULL（本地仿真） |

## 检查入口追踪

| 检查 | 覆盖 | 状态 |
|---|---|---|
| R01—R16 | 点位、申请、查询、编号、名称、顺序、快照 | FULL |
| R17—R27 | 状态机转换与终态 | FULL |
| R29—R32 | 并发幂等 | FULL |
| R33—R40 | Fake M4 四种模式与对账 | FULL |
| R41—R45 | Outbox 记录与恢复 | FULL |
| R1-REL-01—14 | 可靠性：候选快照 / 单次提交 / 成功后才发布 / 只读 / 迁移 / 启动参数 | FULL |
| SCN-01—16 | 场景解析、1 MiB 边界、稳定错误码 | FULL |
| RUN-01—18 | 运行时首错即停、虚拟时间、Oracle 六条真算、CLI 退出码 | FULL |
| RPL-01—12 | 证据 7 文件、manifest 精确、回放比较、故障注入 | FULL |

## 未实现清单

以下正式要求，练习里未实现，需要后续工作：

| 类别 | 未实现项 |
|---|---|
| 身份 | transportRequestId、transportTaskId、executionOrderId、executionAttemptId |
| 资源 | Reservation、Resource 图、Resource 互斥 |
| 调度 | Priority、Admission、Candidate Decision |
| 目的 | ServicePoint、Map / Scene 版本 |
| 执行 | ExecutionOrder、ExecutionAttempt、RcsInteraction |
| 配置 | SiteProfile、Workflow Definition Version |
| 运营 | Timeline、Alarm、ManualWorkItem、AuditEvent |
| 安全 | RBAC、凭据管理、Callback 验证 |
| 可观测 | 结构化日志、Metrics、Trace |
| 数据 | 生产数据库 Migration、Callback Inbox 签名与重放防护 |
| 部署 | 可重复部署包、Backup / Restore、Rollback |
| 仿真 | Shadow、跨进程回放、真实 Lab / M4 证据 |

## 变更记录

| 版本 | 日期 | 说明 |
|---|---|---|
| V0.1 | 2026-09-21 | 初稿 |
| V0.2 | 2026-10-24 | 按已实现项逐条更新：Workflow、Inbox、SQLite/Migration、只读、stats/health、确定性仿真与回放；把真实 M4 合同标为 N/A；从未实现清单移除已实现项 |
