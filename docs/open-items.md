# Open Item 与验证债务清单

| 文档属性 | 内容 |
|---|---|
| 版本 | V0.1 |
| 日期 | 2026-09-21 |
| 类型 | 练习项目开放项清单 |
| 对应正式文档 | IDS v0.1 MVP 第 13 节、IDS 产品定义 第 11 节 |
| 状态 | DRAFT |

## 文档说明

本文件把正式 IDS 文档里的 TO_VERIFY 项和开放项整理成一张表，便于后续逐项处理。

**它做什么：**

- 列出所有未确认、未验证、未执行的项目
- 标注每项的影响范围和处置方向
- 区分"阻断 v0.1"和"不阻断 v0.1"

**它不做什么：**

- 不关闭任何 Open Item
- 不代替正式开放项登记表
- 不把 TO_VERIFY 当作 FAIL

依据：

- IDS v0.1 MVP 第 13 节 风险与开放项
- IDS v0.1 MVP 第 1.2 节 尚未成立的合同与资格
- IDS 产品定义 第 11 节 与其他文档的关系
- IDS 当前系统事实记录 第 3 节 三轴证据口径

## 状态说明

| 状态 | 含义 |
|---|---|
| TO_VERIFY | 尚未验证 |
| TO_CONFIRM | 尚未确认 |
| NOT_EXECUTED | 尚未执行 |
| INTERIM_REPORTED | 有中间报告但原始证据未闭合 |
| BLOCKED | 被前置条件阻断 |

## 一、环境与合同类

| ID | 项目 | 状态 | 影响 | 处置方向 |
|---|---|---|---|---|
| OI-ENV-001 | M4 应用身份与 Scope | TO_VERIFY | 阻断真实 M4 合同 | 向厂商或现场取得应用凭据 |
| OI-ENV-002 | M4 业务端点与合同版本 | TO_VERIFY | 阻断真实 M4 合同 | exact Contract Test |
| OI-ENV-003 | M4 状态 / 时间 / 错误映射 | TO_VERIFY | 阻断状态机与 M4 对齐 | 逐字段验证 |
| OI-ENV-004 | M4 Callback 安全方案 | TO_VERIFY | 阻断 callback 接收 | 定义签名 / 认证 |
| OI-ENV-005 | M4 externalId / orderId 映射 | TO_VERIFY | 阻断身份反查 | exact Contract Test |
| OI-ENV-006 | M4 Candidate / Forced 字段语义 | TO_VERIFY | 阻断候选约束 | exact Contract Test |
| OI-ENV-007 | M4 Map / Scene identity | TO_VERIFY | 阻断目的解析 | 目标版本核对 |
| OI-ENV-008 | M4 车辆 / 虚拟车辆就绪 | TO_VERIFY | 阻断真实 M4 测试 | 准备测试资源 |
| OI-ENV-009 | Falcon 历史依赖 | TO_VERIFY | 可能影响标准订单 Slice | 列出用途，评估替代 |
| OI-ENV-010 | 安全停止条件 | TO_VERIFY | 阻断真实写 | 定义 Stop Condition |

## 二、现场与生产合同类

| ID | 项目 | 状态 | 影响 | 处置方向 |
|---|---|---|---|---|
| OI-SITE-001 | Factory3 精确 MES 合同 | TO_VERIFY | 不阻断 v0.1，阻断 v1.0 | 现场合同确认 |
| OI-SITE-002 | Factory3 精确 LineSide 合同 | TO_VERIFY | 不阻断 v0.1 | 现场调研 |
| OI-SITE-003 | Factory3 精确设备 / PLC 寄存器 | TO_VERIFY | 不阻断 v0.1 | 现场技术取证 |
| OI-SITE-004 | Factory3 Map / Scene 点表 | TO_VERIFY | 不阻断 v0.1 | 现场点表 |
| OI-SITE-005 | Factory3 网络与账号方案 | TO_VERIFY | 不阻断 v0.1 | 现场 IT 确认 |
| OI-SITE-006 | Factory3 约 50 台车辆过渡节奏 | TO_CONFIRM | 不阻断 v0.1 | 责任主体确认 |
| OI-SITE-007 | Factory1 回归范围 | TO_CONFIRM | 不阻断 v0.1 | 责任主体确认 |

## 三、开发环境与工具链

| ID | 项目 | 状态 | 影响 | 处置方向 |
|---|---|---|---|---|
| OI-TOOL-001 | exact JDK 版本 | TO_CONFIRM | 影响可重复构建 | 冻结工具链 |
| OI-TOOL-002 | exact 数据库产品与版本 | TO_CONFIRM | 影响 Migration | 环境基线确定 |
| OI-TOOL-003 | exact 部署拓扑 | TO_CONFIRM | 影响 CAP-17 | 环境设计 |
| OI-TOOL-004 | exact Secret 管理方式 | TO_CONFIRM | 影响 CAP-15 | 安全方案 |

## 四、容量与可靠性

| ID | 项目 | 状态 | 影响 | 处置方向 |
|---|---|---|---|---|
| OI-CAP-001 | C50 workload 定义 | TO_CONFIRM | 影响 v1.0 生产资格 | 负载模型 |
| OI-CAP-002 | C100 workload 定义 | TO_CONFIRM | 影响 Lab 设计资格 | 负载模型 |
| OI-CAP-003 | HA RTO / RPO 数值 | TO_CONFIRM | 影响 v1.0 | 责任主体决定 |
| OI-CAP-004 | 遥测新鲜度阈值 | TO_CONFIRM | 影响 Candidate 硬筛 | 现场验证 |
| OI-CAP-005 | 公平性与容量阈值 | TO_CONFIRM | 影响 Priority / Admission | 后续策略 |

## 五、验证与证据

| ID | 项目 | 状态 | 影响 | 处置方向 |
|---|---|---|---|---|
| OI-VER-001 | 真实 M4 合同测试执行 | NOT_EXECUTED | 阻断 A1 | exact Contract Test |
| OI-VER-002 | Lab 综合验证执行 | NOT_EXECUTED | 阻断 v0.1 退出 | Integrated Lab |
| OI-VER-003 | Shadow 执行 | NOT_EXECUTED | 阻断 Canary | SideEffectGuard 就绪后执行 |
| OI-VER-004 | Canary 执行 | NOT_EXECUTED | 阻断 Site | 责任主体授权 |
| OI-VER-005 | Site Acceptance | NOT_EXECUTED | 阻断发布 | 现场执行 |
| OI-VER-006 | Capacity 测试 | NOT_EXECUTED | 阻断 C50 资格 | Lab 环境准备 |
| OI-VER-007 | Replay 骨架 | NOT_EXECUTED | 不阻断 v0.1 | 后续实现 |
| OI-VER-008 | Evidence Bundle 模板 | DRAFT | 不阻断 v0.1 | 本仓库 docs/ 已初稿 |

## 六、练习项目特有的开放项

| ID | 项目 | 状态 | 说明 |
|---|---|---|---|
| OI-PRAC-001 | Inbox 部分 | NOT_EXECUTED | 练习里没有 callback，未实现 |
| OI-PRAC-002 | 真实数据库 Migration | NOT_EXECUTED | 练习只用文本文件 |
| OI-PRAC-003 | 时间字段 | NOT_EXECUTED | 正式项目需要 created_at / updated_at |
| OI-PRAC-004 | 多 Site 字段 | NOT_EXECUTED | 正式项目需要 customerId / siteId |
| OI-PRAC-005 | 外键与级联规则 | NOT_EXECUTED | 正式项目需要定义 |
| OI-PRAC-006 | 索引与保留策略 | NOT_EXECUTED | 正式项目需要定义 |

## 七、阻断分类

### 阻断 v0.1 退出的开放项

    OI-ENV-001 ~ OI-ENV-010
    OI-VER-001、OI-VER-002

### 不阻断 v0.1 但阻断 v1.0 的开放项

    OI-SITE-001 ~ OI-SITE-007
    OI-TOOL-001 ~ OI-TOOL-004
    OI-CAP-001 ~ OI-CAP-005
    OI-VER-003 ~ OI-VER-006

### 练习特有，不影响正式项目

    OI-PRAC-001 ~ OI-PRAC-006

## 变更记录

| 版本 | 日期 | 说明 |
|---|---|---|
| V0.1 | 2026-09-21 | 初稿，从正式文档抽取开放项 |
