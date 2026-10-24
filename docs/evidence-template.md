# Evidence Bundle 模板

| 文档属性 | 内容 |
|---|---|
| 版本 | V0.2 |
| 日期 | 2026-10-24 |
| 类型 | 练习项目证据模板 |
| 对应正式文档 | IDS 仿真、回放与验证架构说明书 第 19 节、附录 C |
| 状态 | FROZEN |

## 文档说明

本文件是练习项目 `logistics-intake-practice` 的人工填写版 Evidence Bundle 模板。

**它做什么：**

- 定义一次验证运行的人工记录格式
- 说明每份证据应包含哪些字段
- 为练习的阶段复验记录提供参考

**它不做什么：**

- 不生成实际证据
- 不替代正式 EvidenceProfile
- 不声称练习证据等于 VERIFIED_LAB

**机器可读证据包（仿真 `run`）不在本文件定义**：7 个文件、`run-summary.txt` 的 21 个固定字段、三个 TSV 的列格式与稳定错误码以 `docs/simulation-format.md` 为准；`scripts/verify-simulation.sh` 第 24 步逐字核对该 21 字段顺序。

依据：

- IDS 仿真、回放与验证架构说明书 第 19 节 Evidence 架构
- IDS 仿真、回放与验证架构说明书 附录 C Evidence Bundle 最小清单
- IDS v0.1 MVP 第 9 节测试分层与证据包

## 使用方式

每次验证运行，复制下面的模板，填充实际内容，保存为：

    cases/evidence-<testRunId>.md

练习里 testRunId 可以用时间戳，例如 `20260921-0830`。

## 模板正文

---

# Evidence Bundle: <testRunId>

## 1. Run Identity

| 项目 | 内容 |
|---|---|
| testRunId | |
| 日期 | |
| 操作者 | |
| 环境 | |
| 目的 | |

## 2. Artifact / Source Identity

| 项目 | 内容 |
|---|---|
| 仓库 | logistics-intake-practice |
| 分支 | |
| Commit | |
| Java 版本 | |
| javac 版本 | |
| 源文件数 | |

## 3. Configuration Identity

| 项目 | 内容 |
|---|---|
| 存储模式 | memory / file |
| Fake 模式 | fake-accept / fake-reject / fake-timeout-created / fake-timeout-not-created |
| 数据目录 | data/ |
| 其他参数 | |

## 4. Input

| 项目 | 内容 |
|---|---|
| 输入文件 | |
| 输入行数 | |
| 输入摘要 | |
| 初始数据 | |

## 5. Commands Executed

| 序号 | 命令 | 期望 | 实际 |
|---|---|---|---|
| 1 | | | |
| 2 | | | |

## 6. Timeline

| 时间 | 事件 |
|---|---|
| | 编译开始 |
| | 编译结束 |
| | 运行开始 |
| | 运行结束 |
| | diff 结束 |

## 7. Oracle / Expected

| 项目 | 内容 |
|---|---|
| 断言 | |
| 期望状态 | |
| 期望副作用数 | |
| 期望 externalOrderId | |

## 8. Actual Result

| 项目 | 内容 |
|---|---|
| 实际输出 | |
| diff 结果 | |
| 副作用计数 | |
| 意外行为 | |

## 9. Result

只允许以下之一：

    PASS
    FAIL
    INCONCLUSIVE
    BLOCKED
    NOT_EXECUTED

结果：

## 10. Side Effect Boundary

| 项目 | 内容 |
|---|---|
| 真实 M4 写 | 无 |
| 真实设备写 | 无 |
| 生产数据库写 | 无 |
| 外部副作用计数 | 0 |

## 11. Evidence Files

| 文件 | 路径 | SHA-256 |
|---|---|---|
| 输入 | | |
| 期望 | | |
| 实际 | | |
| 日志 | | |

## 12. Reviewer

| 项目 | 内容 |
|---|---|
| 评审人 | |
| 评审结论 | |
| 备注 | |

## 13. 已知限制

| 项目 | 内容 |
|---|---|
| 未覆盖场景 | |
| 简化点 | |
| 与正式项目的差异 | |

---

## 附录：练习里的最小证据

练习里目前使用的最简证据是：

    cases/<sample>.in
    cases/<sample>.expected.txt
    cases/<sample>.actual.txt
    diff 无差异

对应到本模板：

| 模板字段 | 练习对应 |
|---|---|
| Input | cases/<sample>.in |
| Oracle | cases/<sample>.expected.txt |
| Actual Result | cases/<sample>.actual.txt |
| Result | diff 退出码 0 |
| Side Effect Boundary | 无真实 M4 / 设备 / 生产库 |

完整模板的其余字段（Run Identity、Artifact、Configuration、Timeline、Reviewer 等），练习里目前没有完整记录，属于正式项目的扩展要求。

## 变更记录

| 版本 | 日期 | 说明 |
|---|---|---|
| V0.1 | 2026-09-21 | 初稿 |
