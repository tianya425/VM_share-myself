# Open Item 与验证债务清单（STU-SIM-02-R1）

| 属性 | 内容 |
|---|---|
| 任务 | STU-SIM-02-R1 |
| 版本 | V2.0 |
| 日期 | 2026-10-30 |
| Gate | `WAIT_FOR_INDEPENDENT_REVIEW` |

## 一、原则

本清单只登记**真实未关闭**事项；不登记已由测试与 Evidence 关闭的合同项，也不写任何自授予的 Gate 通过状态。Gate 只能由独立复核改变。

## 二、当前未关闭项

| 编号 | 类型 | 说明 | 影响 | 处理 |
|---|---|---|---|---|
| OI-R1-01 | 复核 | 独立复核尚未执行；`GATE_B=WAIT_FOR_INDEPENDENT_REVIEW` | 不能声明正式接受 | 等待独立复核 |
| OI-R1-02 | 环境 | M4 / 实验室服务器 / 真实设备联调不在本任务范围 | 不证明真实设备合同与容量 | 明确不声称 |
| OI-R1-03 | 文档 | `docs/delivery-verification.md` 为历史分阶段记录，含整改前语境 | 阅读时可能与最终状态混淆 | 以 `summary.md` / `simulation-handoff.md` / `resource-world.md` 为准 |

## 三、已关闭的关键合同项（不作为开放项）

- 42 个资源整改合同（`R1-CON` / `R1-DOM` / `R1-LOAD` / `R1-ORC` / `R1-DSL` / `R1-STORE`）全部通过。
- 24 个资源世界固定 case、10 个场景 case、6 组双跑回放、6 项 mutation 全部通过。
- 非法场景固定错误码、首错停止、保留快照、`realExternalWrites=0` 已由脚本与 Evidence 覆盖。

## 四、验证入口

```text
bash scripts/verify-simulation.sh
```

## 五、回执绑定

FINAL_SOURCE_IDENTITY=BOUND_BY_EXTERNAL_DELIVERY_RECEIPT
DELIVERY_RECEIPT=delivery-receipt.txt
