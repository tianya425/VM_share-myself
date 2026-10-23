# Security 基线草案

| 文档属性 | 内容 |
|---|---|
| 版本 | V0.1 |
| 日期 | 2026-09-21 |
| 类型 | 练习项目安全基线草案 |
| 对应正式文档 | IDS v0.1 MVP 第 5 节 MVP-REQ-19、CAP-15 |
| 状态 | DRAFT |

## 文档说明

本文件是练习项目 `logistics-intake-practice` 的安全基线草案。

**它做什么：**

- 定义 v0.1 最小安全要求
- 说明 RBAC、Secret、Callback 验证、审计的基本概念
- 为后续正式实现提供参考

**它不做什么：**

- 不实现任何认证 / 授权代码
- 不引入外部安全框架
- 不定义生产级安全方案

依据：

- MVP-REQ-19 安全基线
- CAP-15 Security 与 Administration
- NFR-05、NFR-06

## 一、基本角色

正式文档要求至少区分三类角色：

| 角色 | 权限 |
|---|---|
| viewer | 只读：查询点位、申请、Outbox、Inbox |
| operator | 读 + 部分写：登记点位、登记申请、dispatch、cancel |
| admin | 全部：包括强制终结、配置管理、审计查看 |

**练习里未实现**。练习只有单一用户，没有认证和授权。

## 二、动作分级

| 动作 | 风险 | 要求 |
|---|---|---|
| point add / show / list | 低 | viewer 可读，operator 可写 |
| application add / show / list | 低 | viewer 可读，operator 可写 |
| application dispatch / query / callback | 中 | operator |
| application execute / complete / cancel | 中 | operator |
| outbox list / process | 中 | operator |
| inbox list | 低 | viewer |
| 强制终结、配置激活、审计导出 | 高 | admin，需要理由和审计 |

## 三、Secret 管理

**原则：**

- Secret 不进入源码
- Secret 不进入普通日志
- Secret 不进入 Git
- Secret 通过环境变量或受控 secret 服务提供

**正式文档要求：**

- Secret、License、Machine Code 和凭据不得进入普通 Markdown、Git 或 API 响应
- 日志必须脱敏

**练习里：**

- 无 Secret
- 无外部凭据
- 无日志脱敏需求

## 四、Callback 验证

正式文档要求 Callback：

- 验证来源身份
- 验证认证
- 验证合同版本
- 验证时间窗
- 验证大小
- 写入 Inbox 前先验证

**练习里：**

- Callback 通过 `application callback` 命令模拟
- 无来源验证
- 无签名验证
- 无时间窗
- 直接写入 Inbox

**练习与正式项目的差距：**

| 项目 | 练习 | 正式 |
|---|---|---|
| 来源 | 终端输入 | HTTP / 消息中间件 |
| 认证 | 无 | 签名 / Token / mTLS |
| 重放保护 | 无 | 时间窗 + 去重键 |
| 大小限制 | 无 | 有 |
| 异常处理 | 直接抛 | 隔离 + 审计 |

## 五、审计

正式文档要求：

- 高风险动作必须记录操作者、角色、理由、前后状态、外部响应摘要
- Audit 不允许被普通用户删除或改写

**练习里：**

- 没有 AuditEvent
- 没有操作者身份
- 没有理由字段
- 没有前后状态记录

## 六、输入校验

正式文档要求：

- 所有入口必须校验输入
- 编号、名称、状态、合同版本必须校验

**练习里已实现：**

- 编号格式校验：`[A-Z][A-Z0-9_-]{0,31}`
- 名称格式校验：长度 1—40，不含空白
- 状态转换校验：非法转换拒绝
- eventType 校验：仅 STARTED / COMPLETED / FAILED

**练习里未实现：**

- 请求大小限制
- 速率限制
- 合同版本校验
- 来源身份校验

## 七、默认拒绝

正式文档要求：

- 认证、授权、Site/Scope 或 Callback 来源无法验证时默认拒绝
- 不能保留万能默认密码、明文 Token 或无认证写

**练习里：**

- 无认证，无默认拒绝
- 所有命令都可执行

## 八、功能安全边界

正式文档明确：

- IDS 不是 SIL / PL 安全控制器
- 车辆本体、安全 PLC、传感器、碰撞避免、急停和硬实时保护由车辆、M4/RCS 与现场安全系统承担
- IDS 的停止派发、取消、WriteReadiness 失效和报警属于业务与运行治理，不等于物理急停

**练习里：**

- 无任何安全控制
- 无物理安全相关操作

## 九、正式项目需要补充的安全项

| 项目 | 对应正式文档 |
|---|---|
| 用户 / 服务身份 | MVP-REQ-19 |
| RBAC 三角色 | MVP-REQ-19 |
| API 写认证 | MVP-REQ-19 |
| Callback 来源验证 | MVP-REQ-19、MVP-REQ-11 |
| Secret 管理 | MVP-REQ-19 |
| Audit | MVP-REQ-18、MVP-REQ-19 |
| 输入校验 | MVP-REQ-19 |
| 默认拒绝 | MVP-REQ-19 |
| 日志脱敏 | MVP-REQ-19 |

## 十、练习与正式项目的对应

| 练习资产 | 正式项目对应 | 差距 |
|---|---|---|
| 无 | RBAC | 完全缺失 |
| 无 | Secret 管理 | 完全缺失 |
| 无 | Callback 验证 | 完全缺失 |
| 无 | Audit | 完全缺失 |
| 编号 / 名称校验 | 输入校验 | 已有基础 |
| 状态机校验 | 状态转换校验 | 已有基础 |

## 变更记录

| 版本 | 日期 | 说明 |
|---|---|---|
| V0.1 | 2026-09-21 | 初稿 |
