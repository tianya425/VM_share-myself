# 仿真边界与证据等级（STU-SIM-02-R1）

## 一、范围

本仓库只做**本地确定性仿真**：由固定 `.scenario` 文件驱动既有业务入口，在进程内生成 Evidence。

- 证据等级固定：`LOCAL_SIMULATION`
- 真实外部写固定：`realExternalWrites=0`
- 虚拟机时间、网络、真实设备均不参与业务结论
- 全部时间来自场景 `START_AT` 与虚拟偏移，禁止系统时钟

## 二、允许边界

- 本地 Java 21 编译与运行
- 本地文件 / SQLite 存储（仅 `build/`、临时目录）
- Fake RCS 适配器（`ScriptedFakeRcsAdapter` / `FakeRcsAdapter`）
- `WORLD_RESTART`：保留同一 Storage / Clock / Fake，丢弃 Registry 与 Target 后用 `loadState()` 重建

## 三、禁止边界

- 不连接真实 M4、AGV、线边仓、PLC、MES、上位机或生产数据库
- 不登录、探测或修改实验室服务器与现场网络
- 不产生任何真实外部写；不以网络请求替代本地 Fake
- 不声称 `M4_CONTRACT_PASS`、`VERIFIED_LAB`、`SITE_PASS`、`PRODUCTION_READY`、`CAPACITY_PASS`
- 不把本地仿真结论写成实验环境、Shadow、Canary 或生产就绪

## 四、M4 不适用

本任务的资源合同（Destination / ServicePoint / VirtualResource / Reservation）是练习用的本地领域模型，不是真实 M4 字段合同，也不对接真实调度系统。六项资源 mutation 只在临时 clone 中注入，主分支不保留错误代码。

## 五、验证事实（2026-10-30）

- 唯一命令：`bash scripts/verify-simulation.sh`，24 项全过，退出码 0
- 终点行：`SIMULATION_RESOURCE_VERIFICATION_PASS originalCases=30 ... realExternalWrites=0`
- 30 旧 case 逐字节通过；24 个资源世界 case、42 个整改合同 case、10 个场景 case 全过
- 6 合法场景各双跑一致；5 非法场景 exit=1 且保留最后已提交快照
- 6 项 mutation 均被目标检查检出，随后恢复干净源码

## 六、回执绑定

最终 commit / tree / ZIP / bundle 不在 Git 跟踪文档内自引用，统一由仓库外回执绑定：

FINAL_SOURCE_IDENTITY=BOUND_BY_EXTERNAL_DELIVERY_RECEIPT
DELIVERY_RECEIPT=delivery-receipt.txt
