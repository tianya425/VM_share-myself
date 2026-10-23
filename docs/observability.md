# Observability 草案

| 文档属性 | 内容 |
|---|---|
| 版本 | V0.1 |
| 日期 | 2026-09-21 |
| 类型 | 练习项目可观测性草案 |
| 对应正式文档 | IDS v0.1 MVP 第 5 节 MVP-REQ-20、CAP-12 |
| 状态 | DRAFT |

## 文档说明

本文件是练习项目 `logistics-intake-practice` 的可观测性草案。

**它做什么：**

- 定义 v0.1 最小可观测性要求
- 说明 traceId、结构化日志、Metrics 的基本概念
- 为后续正式实现提供参考

**它不做什么：**

- 不实现任何日志框架或监控代码
- 不引入外部依赖
- 不定义生产级可观测性方案

依据：

- MVP-REQ-20 可观测性
- CAP-12 Operations、Monitoring 与 Manual Control
- NFR-01、NFR-07、NFR-08、NFR-12

## 一、核心关联 ID

正式文档要求所有关键记录包含以下 ID：

trace_id
request_id
platform_task_id
execution_order_id
external_rcs_order_id
vehicle_id
station_id / service_point_id
adapter_instance
作用：

从任一 ID 可以反查：
  - 这条记录属于哪个请求
  - 属于哪个任务
  - 关联哪个外部订单
  - 关联哪辆车
  - 关联哪个站点
  - 由哪个 Adapter 实例处理

练习里的对应：

| 正式字段 | 练习对应 |
|---|---|
| request_id | application_id |
| platform_task_id | application_id |
| execution_order_id | outbox entry_id |
| external_rcs_order_id | external_order_id |
| trace_id | 无 |
| vehicle_id | 无 |
| station_id | from_point_id / to_point_id |
| adapter_instance | 无 |

练习里缺的：

- trace_id
- vehicle_id
- adapter_instance
- 统一的 request_id 和 platform_task_id 分离

## 二、结构化日志

正式文档要求：

- 日志必须结构化
- 关键字段必须可查询
- 不能只靠自由文本

建议字段：

timestamp
level
logger
trace_id
request_id
application_id
state
message
context

练习里：

- 只有 System.out.println
- 无时间戳
- 无级别
- 无关联 ID
- 无结构化格式

练习与正式项目的差距：

| 项目 | 练习 | 正式 |
|---|---|---|
| 格式 | 纯文本 | JSON 或结构化 |
| 级别 | 无 | DEBUG / INFO / WARN / ERROR |
| 关联 ID | 无 | traceId + 多个领域 ID |
| 输出 | 终端 | 日志文件 + 日志服务 |
| 脱敏 | 无 | 必须 |
| 保留 | 无 | 有策略 |

## 三、Metrics

正式文档要求至少采集：

- 请求量
- 错误率
- 延迟（p50 / p95 / p99）
- 队列年龄
- Unknown 数
- Outbox / Inbox 积压
- 资源冲突数
- M4 health
- Callback 延迟 / 重复

练习里的对应：

| 正式指标 | 练习里能观察到的 |
|---|---|
| 请求量 | 手工数 |
| 错误率 | diff 结果 |
| Unknown 数 | inbox / outbox 数量 |
| Outbox 积压 | outbox list 里 PENDING 数量 |
| Inbox 积压 | inbox list 里 PENDING 数量 |
| 其他 | 无 |

## 四、Timeline

正式文档要求：

- 每个关键任务必须能从 Timeline 重建因果
- Timeline 记录所有状态变化、外部交互、人工操作
- 支持从任一 ID 反查

练习里的对应：

| 正式概念 | 练习对应 |
|---|---|
| Timeline | 无 |
| StateEvent | 无 |
| AuditEvent | 无 |
| 历史追踪 | 只能看当前状态 |

这是练习最大的缺口之一。

## 五、Trace

正式文档要求：

- 分布式追踪
- 跨服务传递 traceId
- 支持从入口追踪到外部调用

练习里：

- 单进程
- 无分布式
- 无追踪

## 六、陈旧数据与新鲜度

正式文档要求：

- 遥测必须带 sourceObservedAt 和 receivedAt
- 时间不可信或超过阈值时 fail closed
- 观测链故障自身需要告警

练习里：

- 无遥测
- 无时间戳
- 无新鲜度判断

## 七、日志脱敏

正式文档要求：

- 日志不得输出 Secret
- 错误信息不得包含敏感数据
- Evidence 必须脱敏

练习里：

- 无 Secret
- 无脱敏需求

## 八、Evidence

正式文档要求：

- 每次验证运行必须生成 Evidence Bundle
- Bundle 包含 Run Identity、Input、Oracle、Result、Reviewer 等
- Bundle 必须可复现

练习里：

- 有 cases/*.expected.txt 和 cases/*.actual.txt
- 有 cases/verification.md
- 有 docs/evidence-template.md 模板
- 无正式的 Run Identity
- 无 SHA-256
- 无 Reviewer 签名

## 九、正式项目需要补充的可观测性

| 项目 | 对应正式文档 |
|---|---|
| traceId | MVP-REQ-20 |
| 结构化日志 | MVP-REQ-20 |
| Metrics | MVP-REQ-20 |
| Timeline | MVP-REQ-20、CAP-12 |
| StateEvent 存储 | MVP-REQ-20 |
| AuditEvent | MVP-REQ-18 |
| 日志脱敏 | MVP-REQ-19 |
| Evidence Bundle | 仿真架构第 19 节 |

## 十、练习与正式项目的对应

| 练习资产 | 正式项目对应 | 差距 |
|---|---|---|
| System.out.println | 结构化日志 | 差很远 |
| 无 | traceId | 完全缺失 |
| 无 | Metrics | 完全缺失 |
| 无 | Timeline | 完全缺失 |
| cases/*.expected.txt | 最小 Evidence | 有基础 |
| docs/evidence-template.md | Evidence 模板 | 已有初稿 |

## 十一、给正式项目的建议

如果正式项目要开始做可观测性，建议顺序：

1. 引入 traceId 并贯穿所有日志
2. 引入结构化日志框架（SLF4J + Logback）
3. 加入状态变化事件表（StateEvent）
4. 加入 Timeline 查询能力
5. 加入 Metrics 端点
6. 加入 Evidence Bundle 生成器

不要一次性做完。从 traceId 开始，逐步加。

## 变更记录

| 版本 | 日期 | 说明 |
|---|---|---|
| V0.1 | 2026-09-21 | 初稿 |
