# 当前事实与证据等级整理

| 文档属性 | 内容 |
|---|---|
| 版本 | V0.1 |
| 日期 | 2026-09-21 |
| 类型 | 练习项目事实整理 |
| 对应正式文档 | IDS 当前系统、业务与现场事实记录 第 3 节 |
| 状态 | DRAFT |

## 文档说明

本文件把正式 IDS 事实记录里的证据按三轴分类整理，便于区分"已确认"和"待验证"。

**它做什么：**

- 按 Evidence Level 分类现有事实
- 按 Fact Status 标注当前被证明到什么程度
- 按 Classification 标注事实的用途

**它不做什么：**

- 不产生新的事实
- 不替代正式事实记录
- 不关闭任何 TO_VERIFY

依据：

- IDS 当前系统、业务与现场事实记录 第 3 节 三轴证据口径
- 第 3.8 节 旭创一厂
- 第 3.9 节 旭创三厂
- 第 4 节 当前集成与外部能力事实
- 第 6 节 M4 Lab 时间切片

## 三轴说明

### Evidence Level

| 值 | 含义 |
|---|---|
| L1_OWNER_CONFIRMATION | 责任主体确认 |
| L3_FORMAL_REFERENCE | 受控参考资料 |
| L4_STATIC_CODE | 静态代码证据 |
| L5_VENDOR_DOCUMENT | 厂商文档 |
| L6_HISTORICAL_INPUT | 历史输入 |

### Fact Status

| 值 | 含义 |
|---|---|
| CONFIRMED | 已确认 |
| DOCUMENTED | 有文档但未运行验证 |
| STATIC_CODE_EVIDENCE | 只有静态代码证据 |
| VERIFIED_LAB | Lab 已验证 |
| VERIFIED_SITE | 现场已验证 |
| TO_VERIFY | 待验证 |
| CONFLICTING_EVIDENCE | 证据冲突 |

### Classification / Product Role

| 值 | 含义 |
|---|---|
| CURRENT_FACT | 当前事实 |
| CURRENT_HISTORICAL_ROLE_ONLY | 仅历史角色 |
| PROJECT_PLAN_INPUT | 项目计划输入 |
| PRODUCT_CANDIDATE | 产品候选 |
| DEPRECATED_FOR_CURRENT_IDS | 已废弃 |
| OUT_OF_SCOPE | 不在范围 |

## 一、旭创一厂事实

| 编号 | 陈述 | Evidence Level | Fact Status | Classification |
|---|---|---|---|---|
| L1-XC-001 | 当前 33 个线边仓 | L1_OWNER_CONFIRMATION | CONFIRMED | CURRENT_FACT |
| L1-XC-002 | 当前 7 台 AGV，4 台在现场 | L1_OWNER_CONFIRMATION | CONFIRMED | CURRENT_FACT |
| L1-XC-003 | 当前使用 2 台服务器 | L1_OWNER_CONFIRMATION | CONFIRMED | CURRENT_FACT |
| L1-XC-004 | MES 中心服务器在客户总部 | L1_OWNER_CONFIRMATION | CONFIRMED | CURRENT_FACT |
| L1-XC-005 | 当前道路单车道 | L1_OWNER_CONFIRMATION | CONFIRMED | CURRENT_FACT |
| L1-XC-006 | LineSide → 陆科系统主要为 MQTT | L1_OWNER_CONFIRMATION | CONFIRMED | CURRENT_FACT |
| L1-XC-007 | 陆科系统 → M4 为 HTTP，M4 → AGV 为 TCP | L1_OWNER_CONFIRMATION | CONFIRMED | CURRENT_FACT |
| L1-XC-008 | 地图、路线、交通依赖 M4 | L1_OWNER_CONFIRMATION | CONFIRMED | CURRENT_FACT |
| L1-XC-009 | AGV 到位脚本用于本地点位交互 | L1_OWNER_CONFIRMATION | CONFIRMED | CURRENT_FACT |

补充说明：

- 精确主机、IP、补丁、构建、端口、配置仍是 TO_VERIFY
- 33 / 7 / 2 属于特定时间切片，不构成产品常量

## 二、旭创三厂事实

| 编号 | 陈述 | Evidence Level | Fact Status | Classification |
|---|---|---|---|---|
| F3-001 | 三厂为首个生产主目标 | L1_OWNER_CONFIRMATION | CONFIRMED | PROJECT_PLAN_INPUT |
| F3-002 | 2026-09 约 50 台继续使用既有系统 | L1_OWNER_CONFIRMATION | CONFIRMED | PROJECT_PLAN_INPUT |
| F3-003 | 面积、主干道、多车型 | L6_HISTORICAL_INPUT | TO_VERIFY | PROJECT_PLAN_INPUT |
| F3-004 | MES / 多入口 | L6_HISTORICAL_INPUT | TO_VERIFY | PROJECT_PLAN_INPUT |
| F3-005 | 缓存 / 中转、烘箱热点 | L6_HISTORICAL_INPUT | TO_VERIFY | PROJECT_PLAN_INPUT |
| F3-006 | 快速地图更新、人车混行 | L6_HISTORICAL_INPUT | TO_VERIFY | PROJECT_PLAN_INPUT |

## 三、当前参与系统

| 参与者 | Evidence Level | Fact Status | Classification |
|---|---|---|---|
| MES / 上位业务系统 | L1_OWNER_CONFIRMATION | CONFIRMED | CURRENT_FACT |
| 当前项目服务 | L4_STATIC_CODE | STATIC_CODE_EVIDENCE | CURRENT_FACT |
| M4 | L5_VENDOR_DOCUMENT | DOCUMENTED | CURRENT_FACT |
| LineSide / 线边仓控制 | L3_FORMAL_REFERENCE + L4_STATIC_CODE | STATIC_CODE_EVIDENCE | CURRENT_FACT |
| PLC / 烘箱 | L3_FORMAL_REFERENCE + L4_STATIC_CODE | STATIC_CODE_EVIDENCE | CURRENT_FACT |
| AGV / AMR | L5_VENDOR_DOCUMENT + L4_STATIC_CODE | DOCUMENTED | CURRENT_FACT |
| RDS | L1_OWNER_CONFIRMATION | CONFIRMED | CURRENT_HISTORICAL_ROLE_ONLY |
| 人员 / 现场操作 | L6_HISTORICAL_INPUT | TO_VERIFY | CURRENT_FACT |

## 四、当前任务来源

| 编号 | 陈述 | Evidence Level | Fact Status | Classification |
|---|---|---|---|---|
| TS-001 | 主要搬运请求来自 LineSide 按钮 / 上位机 | L1_OWNER_CONFIRMATION | CONFIRMED | CURRENT_FACT |
| TS-002 | 其他来源包括自动空框任务与人工任务 | L1_OWNER_CONFIRMATION | CONFIRMED | CURRENT_FACT |
| TS-003 | MES 参与业务 / 工序 / 目的地信息 | L1_OWNER_CONFIRMATION | CONFIRMED | CURRENT_FACT |
| TS-004 | MES 不是当前主要直接搬运任务生产者 | L1_OWNER_CONFIRMATION | CONFIRMED | CURRENT_FACT |
| TS-005 | 多入口统一接入尚未闭合 | L4_STATIC_CODE | STATIC_CODE_EVIDENCE | PROJECT_PLAN_INPUT |

## 五、M4 六轴能力

| 能力主题 | Vendor Documented | Static Code | Owner Confirmed | Verified Lab | Target Contract |
|---|---|---|---|---|---|
| Ping / 登录 | DOCUMENTED | 有 | CONFIRMED | NO_EVIDENCE | TO_VERIFY |
| 创建运单 / externalId | DOCUMENTED | 有 | CONFIRMED | NO_EVIDENCE | TO_VERIFY |
| 查询运单 | DOCUMENTED | 有 | CONFIRMED | NO_EVIDENCE | TO_VERIFY |
| 取消运单 | DOCUMENTED | 有 | CONFIRMED | NO_EVIDENCE | TO_VERIFY |
| 优先级 | DOCUMENTED | 有 | CONFIRMED | NO_EVIDENCE | TO_VERIFY |
| 车辆状态 | DOCUMENTED | 有 | CONFIRMED | NO_EVIDENCE | TO_VERIFY |
| 车辆位置 | DOCUMENTED | 有 | CONFIRMED | NO_EVIDENCE | TO_VERIFY |
| Task Callback | DOCUMENTED | 有 | CONFIRMED | NO_EVIDENCE | TO_VERIFY |
| Alarm | DOCUMENTED | 有 | CONFIRMED | NO_EVIDENCE | TO_VERIFY |
| Candidate Vehicle | DOCUMENTED | 有 | CONFIRMED | NO_EVIDENCE | TO_VERIFY |
| Forced Vehicle | DOCUMENTED | 有 | CONFIRMED | NO_EVIDENCE | TO_VERIFY |
| retry | DOCUMENTED | 有 | UNVERIFIED | NO_EVIDENCE | TO_VERIFY |
| suspend / hold | DOCUMENTED | 有 | UNVERIFIED | NO_EVIDENCE | TO_VERIFY |
| map / external map resource | DOCUMENTED | 有 | UNVERIFIED | NO_EVIDENCE | TO_VERIFY |

## 六、当前运行环境

| 输入 | Evidence Level | Fact Status | Classification |
|---|---|---|---|
| Ubuntu 22.04 | L1_OWNER_CONFIRMATION | CONFIRMED | CURRENT_FACT |
| M4 JDK 17 only | L1_OWNER_CONFIRMATION | CONFIRMED | CURRENT_FACT |
| 陆科系统 JDK 21+ | L1_OWNER_CONFIRMATION + L4_STATIC_CODE | CONFIRMED | CURRENT_FACT |
| 陆科系统 Docker | L1_OWNER_CONFIRMATION + L4_STATIC_CODE | CONFIRMED | CURRENT_FACT |
| MySQL | L1_OWNER_CONFIRMATION + L4_STATIC_CODE | CONFIRMED | CURRENT_FACT |
| 旭创 2 台服务器 | L1_OWNER_CONFIRMATION | CONFIRMED | CURRENT_FACT |
| M4 文本存储 | L1_OWNER_CONFIRMATION | CONFIRMED | CURRENT_FACT |

## 七、M4 Lab 时间切片

| 时间 | 事实 | Evidence Level | Fact Status |
|---|---|---|---|
| 2026-08-28 | M4 Boot 2.1.5 已安装 | L3_FORMAL_REFERENCE | CONFIRMED |
| 2026-08-28 | JDK Temurin 17.0.15+6 | L3_FORMAL_REFERENCE | CONFIRMED |
| 2026-08-28 | trick-m4-6.16.2 就绪 | L3_FORMAL_REFERENCE | CONFIRMED |
| 2026-08-28 | License 未激活 | L3_FORMAL_REFERENCE | CONFIRMED |
| 2026-08-31 | License 文件存在 | L6_HISTORICAL_INPUT | INTERIM_REPORTED |
| 2026-08-31 | M4 Runtime 运行 | L6_HISTORICAL_INPUT | INTERIM_REPORTED |
| 2026-08-31 | 5800 已监听 | L6_HISTORICAL_INPUT | INTERIM_REPORTED |
| 2026-08-31 | /api/ping 未认证返回 401 | L6_HISTORICAL_INPUT | INTERIM_REPORTED |

## 八、当前 SoT 矩阵

| 数据主题 | 当前候选权威 | Fact Status |
|---|---|---|
| M4 运单执行状态 | M4 | DOCUMENTED / TO_VERIFY |
| 机器人位置 / 报警 | M4 / 机器人 | DOCUMENTED / TO_VERIFY |
| 本地任务业务字段 | 当前项目数据库 | STATIC_CODE_EVIDENCE |
| 线边仓仓位状态 | 设备上报 + 缓存 / DB | STATIC_CODE_EVIDENCE |
| 料框 / 料笼绑定 | 当前项目数据库 + 现场实物 | STATIC_CODE_EVIDENCE / TO_VERIFY |
| MES 工单 / SN | MES / 业务系统 | TO_VERIFY |
| 地图 / 点位 / 路径 | M4 地图 / 场景 | DOCUMENTED / TO_VERIFY |
| 问题 / PI / Candidate REQ | REF-XC-ISSUE-001 | DOCUMENTED |
| 产品需求 | 历史现场输入 | SOURCE_ONLY |

## 九、练习项目与正式项目的关系

| 练习实现 | 对应正式对象 | 差异 |
|---|---|---|
| Point | core ServicePoint / Station | 练习只保存编号和名称 |
| TransferApplication | TransportRequest / TransportTask | 练习只有单层状态，没有 Request / Task 分离 |
| OutboxEntry | Outbox | 练习用文本文件，正式用数据库 |
| ApplicationState | 正式状态机 | 练习简化到六态 |
| RcsExecutionPort | RcsExecutionPort | 一致 |
| FakeRcsAdapter | FakeRcsAdapter | 练习简化到四种模式 |

## 变更记录

| 版本 | 日期 | 说明 |
|---|---|---|
| V0.1 | 2026-09-21 | 初稿，从正式事实记录抽取 |
