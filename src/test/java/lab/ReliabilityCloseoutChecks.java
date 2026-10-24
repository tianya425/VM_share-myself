package lab;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * R1 可靠性整改（PR1）的 14 项具名检查：R1-REL-01 — R1-REL-14。
 *
 * <p>本类只使用公开 API 与"存储/外部端口"测试替身，不修改任何业务对象。
 * 每个编号一个独立方法 {@code caseR1Rel01()} … {@code caseR1Rel14()}，
 * 只有该编号的全部断言都通过，才打印 {@code R1-REL-XX PASS} 并让 passed 加一。
 *
 * <p>失败处理：为了让 14 项的真实结果都能出现在同一次输出里（先红阶段需要
 * 逐项看到失败原因），每个 case 单独捕获失败并打印
 * {@code R1-REL-XX FAIL: 失败行} 与 {@code R1-REL-XX INTENT: 断言意图}，
 * 全部跑完后只要有任一失败就以退出码 1 结束；14 项全过时最后一行是
 * {@code RELIABILITY_R1_CASES_PASSED=14} 且退出码为 0。
 *
 * <p>注意：本类在未修复的基线上运行时应出现失败（先红）。
 */
public final class ReliabilityCloseoutChecks {

    private static final int CASE_TOTAL = 14;

    private static final String INTENT_01 =
            "timeout-created 后 processOutbox 只 query 不 create：create 累计=1、query=1、"
                    + "申请=DISPATCHED、outbox=SENT，内存与已提交快照一致";
    private static final String INTENT_02 =
            "point 保存失败后，内存与已提交存储都不得出现 P01，且失败后实例仍可继续使用";
    private static final String INTENT_03 =
            "transition（execute/complete/cancel）保存失败后，内存状态必须停留在已提交快照，不得前移";
    private static final String INTENT_04 =
            "dispatch 首次意图保存失败：outbox 内存/已提交均为 0、外部 create 调用=0、申请仍为 CREATED";
    private static final String INTENT_05 =
            "dispatch 第二次保存失败：不得报成功，内存必须回到第一次提交快照（CREATED + PENDING），"
                    + "恢复时先 query 且不再 create";
    private static final String INTENT_06 =
            "processOutbox 提交失败：不得盲目重发（create 不增加）、申请与 outbox 必须回到已提交状态；"
                    + "子断言 query ACCEPTED 时 application=DISPATCHED 与 outbox=SENT 同一次提交、失败两侧一起回退";
    private static final String INTENT_07 =
            "receiveCallback 提交失败：application 与 inbox 在内存和已提交存储中都必须保持不变";
    private static final String INTENT_08 =
            "startWorkflow/advanceWorkflow 提交失败：application、outbox、workflow 三者都不得改变";
    private static final String INTENT_09 =
            "超时（UNKNOWN/UNCERTAIN）后 workflow 必须保持 RUNNING 0/3、step0=PENDING";
    private static final String INTENT_10 =
            "SqliteStorage(db,true) 直接写必须抛 STORAGE_READ_ONLY，且库文件与 sidecar 字节不变";
    private static final String INTENT_11 =
            "只读打开不存在的库必须报 READ_ONLY_STORAGE_NOT_FOUND，且不创建目录/文件";
    private static final String INTENT_12 =
            "MigrationRunner.run 注入失败迁移：失败迁移的表不存在、版本不前进、autoCommit 恢复；"
                    + "注入 RuntimeException（非 SQLException）同样必须整体回滚";
    private static final String INTENT_13 =
            "ProcessBuilder 跑 lab.Main：未知/重复/冲突参数（含重复 storage、重复 fake、混合冲突、--help）"
                    + "退出码=2，且不进入命令循环";
    private static final String INTENT_14 =
            "三步 workflow 后 application 与 workflow 必须同时 COMPLETED，且已提交快照一致";

    private static int passed = 0;
    private static int failed = 0;

    private ReliabilityCloseoutChecks() {
    }

    public static void main(String[] args) {
        run("R1-REL-01", INTENT_01, ReliabilityCloseoutChecks::caseR1Rel01);
        run("R1-REL-02", INTENT_02, ReliabilityCloseoutChecks::caseR1Rel02);
        run("R1-REL-03", INTENT_03, ReliabilityCloseoutChecks::caseR1Rel03);
        run("R1-REL-04", INTENT_04, ReliabilityCloseoutChecks::caseR1Rel04);
        run("R1-REL-05", INTENT_05, ReliabilityCloseoutChecks::caseR1Rel05);
        run("R1-REL-06", INTENT_06, ReliabilityCloseoutChecks::caseR1Rel06);
        run("R1-REL-07", INTENT_07, ReliabilityCloseoutChecks::caseR1Rel07);
        run("R1-REL-08", INTENT_08, ReliabilityCloseoutChecks::caseR1Rel08);
        run("R1-REL-09", INTENT_09, ReliabilityCloseoutChecks::caseR1Rel09);
        run("R1-REL-10", INTENT_10, ReliabilityCloseoutChecks::caseR1Rel10);
        run("R1-REL-11", INTENT_11, ReliabilityCloseoutChecks::caseR1Rel11);
        run("R1-REL-12", INTENT_12, ReliabilityCloseoutChecks::caseR1Rel12);
        run("R1-REL-13", INTENT_13, ReliabilityCloseoutChecks::caseR1Rel13);
        run("R1-REL-14", INTENT_14, ReliabilityCloseoutChecks::caseR1Rel14);

        if (passed + failed != CASE_TOTAL) {
            throw new AssertionError("case 数量不符：passed+failed=" + (passed + failed)
                    + " 期望=" + CASE_TOTAL);
        }
        if (failed > 0) {
            System.out.println("RELIABILITY_R1_CASES_FAILED=" + failed);
        }
        System.out.println("RELIABILITY_R1_CASES_PASSED=" + passed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ================= R1-REL-01 =================

    /**
     * timeout-created 后 processOutbox：create=1、query=1、DISPATCHED、SENT。
     *
     * <p>dispatch 在"外部已建单但应答超时"时留下 UNKNOWN/UNCERTAIN；恢复时只能
     * query，不得重复 create。
     */
    private static void caseR1Rel01() {
        MemoryStorage committed = new MemoryStorage();
        CountingPort port = new CountingPort(FakeRcsAdapter.Mode.TIMEOUT_BUT_CREATED);
        Registry registry = new Registry(committed, port);
        registerBaseline(registry);

        registry.dispatchApplication("APP-001");
        require(port.createCalls == 1, "dispatch 后 create 调用=" + port.createCalls + " 期望=1");
        require(port.queryCalls == 0, "dispatch 后 query 调用=" + port.queryCalls + " 期望=0");
        require(appState(registry) == ApplicationState.UNKNOWN,
                "dispatch 后申请状态=" + appState(registry) + " 期望=UNKNOWN");
        require(outboxStatus(registry) == OutboxStatus.UNCERTAIN,
                "dispatch 后 outbox=" + outboxStatus(registry) + " 期望=UNCERTAIN");

        int processed = registry.processOutbox();
        require(processed == 1, "processOutbox 返回=" + processed + " 期望=1");
        require(port.createCalls == 1,
                "恢复后 create 调用=" + port.createCalls + " 期望仍为 1（UNCERTAIN 只 query，不得重发 create）");
        require(port.queryCalls == 1, "恢复后 query 调用=" + port.queryCalls + " 期望=1");

        require(appState(registry) == ApplicationState.DISPATCHED,
                "恢复后申请状态=" + appState(registry) + " 期望=DISPATCHED");
        require("EXT-APP-001".equals(registry.findApplication("APP-001").orElseThrow().externalOrderId()),
                "恢复后申请外单号=" + registry.findApplication("APP-001").orElseThrow().externalOrderId()
                        + " 期望=EXT-APP-001");
        require(outboxStatus(registry) == OutboxStatus.SENT,
                "恢复后 outbox=" + outboxStatus(registry) + " 期望=SENT");
        require("EXT-APP-001".equals(onlyOutbox(registry).externalOrderId()),
                "恢复后 outbox 外单号=" + onlyOutbox(registry).externalOrderId() + " 期望=EXT-APP-001");

        require(committedApp(committed, "APP-001").state() == ApplicationState.DISPATCHED,
                "已提交申请状态=" + committedApp(committed, "APP-001").state() + " 期望=DISPATCHED（内存与已提交不一致）");
        require(committedOutbox(committed, "OBX-APP-001").status() == OutboxStatus.SENT,
                "已提交 outbox=" + committedOutbox(committed, "OBX-APP-001").status()
                        + " 期望=SENT（内存与已提交不一致）");

        int again = registry.processOutbox();
        require(again == 0, "第二次 processOutbox 返回=" + again + " 期望=0（SENT 不应再处理）");
        require(port.createCalls == 1 && port.queryCalls == 1,
                "第二次 processOutbox 又调用了外部端口 create=" + port.createCalls + " query=" + port.queryCalls);
    }

    // ================= R1-REL-02 =================

    /**
     * point 保存失败后，内存与存储均无 P01。
     */
    private static void caseR1Rel02() {
        MemoryStorage committed = new MemoryStorage();
        FaultStorage storage = new FaultStorage(committed);
        Registry registry = new Registry(storage, new FakeRcsAdapter(FakeRcsAdapter.Mode.ACCEPT));

        storage.armCommit(1);
        expectFailure("registerPoint 在存储失败时必须抛出异常", () -> registry.registerPoint("P01", "A"));

        require(registry.listPoints().isEmpty(),
                "保存失败后内存 points=" + registry.listPoints().size() + " 期望=0（幽灵状态）");
        require(committed.loadPoints().isEmpty(),
                "保存失败后已提交 points=" + committed.loadPoints().size() + " 期望=0");

        // 失败必须可恢复：同一实例再次注册应成功，且只剩一条
        storage.clearFaults();
        registry.registerPoint("P01", "A");
        require(registry.listPoints().size() == 1,
                "失败后重试，内存 points=" + registry.listPoints().size() + " 期望=1");
        require(committed.loadPoints().size() == 1,
                "失败后重试，已提交 points=" + committed.loadPoints().size() + " 期望=1");
    }

    // ================= R1-REL-03 =================

    /**
     * transition（execute/complete/cancel）保存失败后状态不变。
     */
    private static void caseR1Rel03() {
        assertTransitionRollback("executeApplication", ApplicationState.DISPATCHED,
                registry -> registry.executeApplication("APP-001"));
        assertTransitionRollback("cancelApplication", ApplicationState.DISPATCHED,
                registry -> registry.cancelApplication("APP-001"));
        assertTransitionRollback("completeApplication", ApplicationState.EXECUTING,
                registry -> registry.completeApplication("APP-001"));
    }

    private static void assertTransitionRollback(String label, ApplicationState stateBeforeTransition,
                                                 TransitionOp operation) {
        MemoryStorage committed = new MemoryStorage();
        FaultStorage storage = new FaultStorage(committed);
        Registry registry = new Registry(storage, new FakeRcsAdapter(FakeRcsAdapter.Mode.ACCEPT));
        registerBaseline(registry);
        registry.dispatchApplication("APP-001");
        if (stateBeforeTransition == ApplicationState.EXECUTING) {
            registry.executeApplication("APP-001");
        }
        require(appState(registry) == stateBeforeTransition,
                label + " 前置状态=" + appState(registry) + " 期望=" + stateBeforeTransition);

        storage.armCommit(1);
        expectFailure(label + " 保存失败时必须抛出异常", () -> operation.apply(registry));

        require(appState(registry) == stateBeforeTransition,
                label + " 保存失败后内存状态=" + appState(registry)
                        + " 期望回到已提交快照=" + stateBeforeTransition);
        require(committedApp(committed, "APP-001").state() == stateBeforeTransition,
                label + " 已提交状态=" + committedApp(committed, "APP-001").state()
                        + " 期望=" + stateBeforeTransition);
    }

    // ================= R1-REL-04 =================

    /**
     * dispatch 首次意图保存失败：outbox=0、create=0、申请仍 CREATED。
     */
    private static void caseR1Rel04() {
        MemoryStorage committed = new MemoryStorage();
        FaultStorage storage = new FaultStorage(committed);
        CountingPort port = new CountingPort(FakeRcsAdapter.Mode.ACCEPT);
        Registry registry = new Registry(storage, port);
        registerBaseline(registry);

        storage.armCommit(1);
        expectFailure("dispatch 首次意图保存失败时必须抛出异常",
                () -> registry.dispatchApplication("APP-001"));

        require(port.createCalls == 0,
                "create 调用=" + port.createCalls + " 期望=0（意图未落盘不得调用外部端口）");
        require(registry.listOutbox().isEmpty(),
                "内存 outbox 条数=" + registry.listOutbox().size() + " 期望=0（幽灵 outbox 记录）");
        require(committed.loadOutbox().isEmpty(),
                "已提交 outbox 条数=" + committed.loadOutbox().size() + " 期望=0");
        require(appState(registry) == ApplicationState.CREATED,
                "内存申请状态=" + appState(registry) + " 期望=CREATED");
        require(committedApp(committed, "APP-001").state() == ApplicationState.CREATED,
                "已提交申请状态=" + committedApp(committed, "APP-001").state() + " 期望=CREATED");
    }

    // ================= R1-REL-05 =================

    /**
     * dispatch 第二次保存失败：不报成功，内存回到第一次提交快照，恢复时先 query。
     */
    private static void caseR1Rel05() {
        MemoryStorage committed = new MemoryStorage();
        FaultStorage storage = new FaultStorage(committed);
        CountingPort port = new CountingPort(FakeRcsAdapter.Mode.ACCEPT);
        Registry registry = new Registry(storage, port);
        registerBaseline(registry);

        // 第一次提交（PENDING 意图）成功；第二次提交（申请结果）注入失败
        storage.armCommit(2);
        expectFailure("dispatch 第二次保存失败必须抛出异常，不得返回成功",
                () -> registry.dispatchApplication("APP-001"));

        require(appState(registry) == ApplicationState.CREATED,
                "dispatch 失败后内存申请状态=" + appState(registry)
                        + " 期望回到第一次提交快照=CREATED");
        require(committedApp(committed, "APP-001").state() == ApplicationState.CREATED,
                "已提交申请状态=" + committedApp(committed, "APP-001").state() + " 期望=CREATED");
        require(registry.listOutbox().size() == 1,
                "dispatch 失败后内存 outbox 条数=" + registry.listOutbox().size()
                        + " 期望=1（第一次提交的 PENDING 意图）");
        require(outboxStatus(registry) == OutboxStatus.PENDING,
                "dispatch 失败后内存 outbox=" + outboxStatus(registry) + " 期望=PENDING");
        require(onlyOutbox(registry).externalOrderId() == null,
                "dispatch 失败后内存 outbox 外单号=" + onlyOutbox(registry).externalOrderId()
                        + " 期望=null（第二次提交未完成）");
        require(committedOutbox(committed, "OBX-APP-001").status() == OutboxStatus.PENDING,
                "已提交 outbox=" + committedOutbox(committed, "OBX-APP-001").status() + " 期望=PENDING");

        // 重启恢复：必须先 query（外部可能已建单），不得再次 create
        Registry recovered = new Registry(committed, port);
        int processed = recovered.processOutbox();
        require(port.createCalls == 1,
                "恢复后 create 调用=" + port.createCalls + " 期望=1（PENDING 也必须先 query，不得重复 create）");
        require(port.queryCalls == 1, "恢复后 query 调用=" + port.queryCalls + " 期望=1");
        require(processed == 1, "恢复时 processOutbox 返回=" + processed + " 期望=1");
        require(appState(recovered) == ApplicationState.DISPATCHED,
                "恢复后申请状态=" + appState(recovered) + " 期望=DISPATCHED");
        require(outboxStatus(recovered) == OutboxStatus.SENT,
                "恢复后 outbox=" + outboxStatus(recovered) + " 期望=SENT");
    }

    // ================= R1-REL-06 =================

    /**
     * query/processOutbox 提交失败：create 不增加、状态回到已提交。
     *
     * <p>含子断言 query_accepted_updates_application_and_outbox：
     * query ACCEPTED 时 application=DISPATCHED 与 outbox=SENT 必须落在同一次提交；
     * 提交失败时两侧一起回退。
     */
    private static void caseR1Rel06() {
        MemoryStorage committed = new MemoryStorage();
        FaultStorage storage = new FaultStorage(committed);
        CountingPort port = new CountingPort(FakeRcsAdapter.Mode.TIMEOUT_BUT_CREATED);
        Registry registry = new Registry(storage, port);
        registerBaseline(registry);

        registry.dispatchApplication("APP-001");
        require(appState(registry) == ApplicationState.UNKNOWN,
                "dispatch 后申请状态=" + appState(registry) + " 期望=UNKNOWN");
        require(outboxStatus(registry) == OutboxStatus.UNCERTAIN,
                "dispatch 后 outbox=" + outboxStatus(registry) + " 期望=UNCERTAIN");

        storage.armCommit(1);
        expectFailure("processOutbox 提交失败时必须抛出异常", registry::processOutbox);

        require(port.createCalls == 1,
                "processOutbox 失败后 create 调用=" + port.createCalls + " 期望=1（禁止盲目重发）");
        require(port.queryCalls == 1, "processOutbox 失败后 query 调用=" + port.queryCalls + " 期望=1");
        require(appState(registry) == ApplicationState.UNKNOWN,
                "提交失败后内存申请状态=" + appState(registry) + " 期望回到已提交=UNKNOWN");
        require(outboxStatus(registry) == OutboxStatus.UNCERTAIN,
                "提交失败后内存 outbox=" + outboxStatus(registry) + " 期望回到已提交=UNCERTAIN");
        require(committedApp(committed, "APP-001").state() == ApplicationState.UNKNOWN,
                "已提交申请状态=" + committedApp(committed, "APP-001").state() + " 期望=UNKNOWN");
        require(committedOutbox(committed, "OBX-APP-001").status() == OutboxStatus.UNCERTAIN,
                "已提交 outbox=" + committedOutbox(committed, "OBX-APP-001").status() + " 期望=UNCERTAIN");

        // 子断言 query_accepted_updates_application_and_outbox
        MemoryStorage queryCommitted = new MemoryStorage();
        FaultStorage queryStorage = new FaultStorage(queryCommitted);
        CountingPort queryPort = new CountingPort(FakeRcsAdapter.Mode.TIMEOUT_BUT_CREATED);
        Registry queryRegistry = new Registry(queryStorage, queryPort);
        registerBaseline(queryRegistry);
        queryRegistry.dispatchApplication("APP-001");

        // 只允许一次提交：若 query 被拆成两次提交，第 2 次会被注入失败
        queryStorage.armCommit(2);
        TransferApplication queried = queryRegistry.queryApplication("APP-001");
        require(queried.state() == ApplicationState.DISPATCHED,
                "query ACCEPTED 后申请状态=" + queried.state()
                        + " 期望=DISPATCHED（query 不得被拆成两次提交）");
        require(outboxStatus(queryRegistry) == OutboxStatus.SENT,
                "query ACCEPTED 后 outbox=" + outboxStatus(queryRegistry) + " 期望=SENT");
        require("EXT-APP-001".equals(onlyOutbox(queryRegistry).externalOrderId()),
                "query ACCEPTED 后 outbox 外单号=" + onlyOutbox(queryRegistry).externalOrderId()
                        + " 期望=EXT-APP-001");
        require(committedApp(queryCommitted, "APP-001").state() == ApplicationState.DISPATCHED,
                "query 后已提交申请状态=" + committedApp(queryCommitted, "APP-001").state()
                        + " 期望=DISPATCHED");
        require(committedOutbox(queryCommitted, "OBX-APP-001").status() == OutboxStatus.SENT,
                "query 后已提交 outbox=" + committedOutbox(queryCommitted, "OBX-APP-001").status()
                        + " 期望=SENT");

        // 提交失败时两侧一起回退
        MemoryStorage failCommitted = new MemoryStorage();
        FaultStorage failStorage = new FaultStorage(failCommitted);
        CountingPort failPort = new CountingPort(FakeRcsAdapter.Mode.TIMEOUT_BUT_CREATED);
        Registry failRegistry = new Registry(failStorage, failPort);
        registerBaseline(failRegistry);
        failRegistry.dispatchApplication("APP-001");

        failStorage.armCommit(1);
        expectFailure("query 提交失败时必须抛出异常", () -> failRegistry.queryApplication("APP-001"));

        require(appState(failRegistry) == ApplicationState.UNKNOWN,
                "query 提交失败后内存申请状态=" + appState(failRegistry) + " 期望回到已提交=UNKNOWN");
        require(outboxStatus(failRegistry) == OutboxStatus.UNCERTAIN,
                "query 提交失败后内存 outbox=" + outboxStatus(failRegistry) + " 期望回到已提交=UNCERTAIN");
        require(committedApp(failCommitted, "APP-001").state() == ApplicationState.UNKNOWN,
                "query 提交失败后已提交申请状态=" + committedApp(failCommitted, "APP-001").state()
                        + " 期望=UNKNOWN");
        require(committedOutbox(failCommitted, "OBX-APP-001").status() == OutboxStatus.UNCERTAIN,
                "query 提交失败后已提交 outbox=" + committedOutbox(failCommitted, "OBX-APP-001").status()
                        + " 期望=UNCERTAIN");
    }

    // ================= R1-REL-07 =================

    /**
     * receiveCallback 提交失败：application 与 inbox 均不变。
     */
    private static void caseR1Rel07() {
        MemoryStorage committed = new MemoryStorage();
        FaultStorage storage = new FaultStorage(committed);
        Registry registry = new Registry(storage, new FakeRcsAdapter(FakeRcsAdapter.Mode.ACCEPT));
        registerBaseline(registry);
        registry.dispatchApplication("APP-001");

        storage.armCommit(1);
        expectFailure("receiveCallback 提交失败时必须抛出异常",
                () -> registry.receiveCallback("APP-001", "STARTED"));

        require(appState(registry) == ApplicationState.DISPATCHED,
                "提交失败后内存申请状态=" + appState(registry) + " 期望回到已提交=DISPATCHED");
        require(committedApp(committed, "APP-001").state() == ApplicationState.DISPATCHED,
                "已提交申请状态=" + committedApp(committed, "APP-001").state() + " 期望=DISPATCHED");
        require(registry.listInbox().isEmpty(),
                "提交失败后内存 inbox 条数=" + registry.listInbox().size() + " 期望=0");
        require(committed.loadInbox().isEmpty(),
                "提交失败后已提交 inbox 条数=" + committed.loadInbox().size() + " 期望=0");
    }

    // ================= R1-REL-08 =================

    /**
     * startWorkflow/advanceWorkflow 提交失败：三者不变。
     */
    private static void caseR1Rel08() {
        // ① startWorkflow 提交失败
        MemoryStorage committedStart = new MemoryStorage();
        FaultStorage storageStart = new FaultStorage(committedStart);
        Registry registryStart = new Registry(storageStart, new FakeRcsAdapter(FakeRcsAdapter.Mode.ACCEPT));
        registerBaseline(registryStart);

        storageStart.armCommit(1);
        expectFailure("startWorkflow 提交失败时必须抛出异常",
                () -> registryStart.startWorkflow("APP-001"));

        require(registryStart.findWorkflow("APP-001").isEmpty(),
                "startWorkflow 失败后内存仍有 workflow（幽灵状态）");
        require(committedStart.loadWorkflows().isEmpty(),
                "startWorkflow 失败后已提交 workflow 条数=" + committedStart.loadWorkflows().size()
                        + " 期望=0");
        require(committedStart.loadApplications().get(0).state() == ApplicationState.CREATED,
                "startWorkflow 失败后已提交申请状态=" + committedStart.loadApplications().get(0).state()
                        + " 期望=CREATED");

        // ② advanceWorkflow 提交失败（DISPATCH 步会同时触碰 application/outbox/workflow）
        MemoryStorage committed = new MemoryStorage();
        FaultStorage storage = new FaultStorage(committed);
        Registry registry = new Registry(storage, new FakeRcsAdapter(FakeRcsAdapter.Mode.ACCEPT));
        registerBaseline(registry);
        registry.startWorkflow("APP-001");

        storage.armWhen(state -> state.workflows().stream()
                .anyMatch(instance -> instance.currentStepIndex() > 0));
        expectFailure("advanceWorkflow 提交失败时必须抛出异常",
                () -> registry.advanceWorkflow("APP-001"));

        require(appState(registry) == ApplicationState.CREATED,
                "advanceWorkflow 失败后内存申请状态=" + appState(registry) + " 期望=CREATED");
        require(committedApp(committed, "APP-001").state() == ApplicationState.CREATED,
                "advanceWorkflow 失败后已提交申请状态=" + committedApp(committed, "APP-001").state()
                        + " 期望=CREATED");
        require(registry.listOutbox().isEmpty(),
                "advanceWorkflow 失败后内存 outbox 条数=" + registry.listOutbox().size() + " 期望=0");
        require(committed.loadOutbox().isEmpty(),
                "advanceWorkflow 失败后已提交 outbox 条数=" + committed.loadOutbox().size() + " 期望=0");

        WorkflowInstance workflow = workflow(registry, "APP-001");
        require(workflow.state() == WorkflowState.RUNNING,
                "advanceWorkflow 失败后 workflow 状态=" + workflow.state() + " 期望=RUNNING");
        require(workflow.currentStepIndex() == 0,
                "advanceWorkflow 失败后 stepIndex=" + workflow.currentStepIndex() + " 期望=0");
        require(workflow.steps().get(0).state() == WorkflowStepState.PENDING,
                "advanceWorkflow 失败后 step0=" + workflow.steps().get(0).state() + " 期望=PENDING");
        WorkflowInstance committedInstance = committedWorkflow(committed, "APP-001");
        require(committedInstance.currentStepIndex() == 0
                        && committedInstance.steps().get(0).state() == WorkflowStepState.PENDING,
                "已提交 workflow stepIndex=" + committedInstance.currentStepIndex()
                        + " step0=" + committedInstance.steps().get(0).state() + " 期望=0/PENDING");
    }

    // ================= R1-REL-09 =================

    /**
     * timeout 后 workflow 保持 RUNNING 0/3、step0 PENDING。
     */
    private static void caseR1Rel09() {
        MemoryStorage committed = new MemoryStorage();
        CountingPort port = new CountingPort(FakeRcsAdapter.Mode.TIMEOUT_BUT_CREATED);
        Registry registry = new Registry(committed, port);
        registerBaseline(registry);
        registry.startWorkflow("APP-001");

        String context = "advanceWorkflow 未抛异常";
        try {
            registry.advanceWorkflow("APP-001");
        } catch (RegistryException e) {
            context = "advanceWorkflow 抛出 " + e.code();
        }

        require(appState(registry) == ApplicationState.UNKNOWN,
                "超时后申请状态=" + appState(registry) + " 期望=UNKNOWN（" + context + "）");

        WorkflowInstance workflow = workflow(registry, "APP-001");
        require(workflow.state() == WorkflowState.RUNNING,
                "超时后 workflow 状态=" + workflow.state() + " 期望=RUNNING（" + context + "）");
        require(workflow.currentStepIndex() == 0,
                "超时后 stepIndex=" + workflow.currentStepIndex() + " 期望=0 即 RUNNING 0/3（"
                        + context + "）");
        require(workflow.steps().get(0).state() == WorkflowStepState.PENDING,
                "超时后 step0=" + workflow.steps().get(0).state() + " 期望=PENDING（" + context + "）");

        WorkflowInstance committedInstance = committedWorkflow(committed, "APP-001");
        require(committedInstance.state() == WorkflowState.RUNNING
                        && committedInstance.currentStepIndex() == 0
                        && committedInstance.steps().get(0).state() == WorkflowStepState.PENDING,
                "已提交 workflow=" + committedInstance.state()
                        + " " + committedInstance.currentStepIndex() + "/" + committedInstance.steps().size()
                        + " step0=" + committedInstance.steps().get(0).state()
                        + " 期望=RUNNING 0/3 PENDING");
    }

    // ================= R1-REL-10 =================

    /**
     * SqliteStorage(db,true) 直接写：抛 STORAGE_READ_ONLY，文件/sidecar 字节不变。
     *
     * <p>覆盖五层 readonly 中可观测的四层：
     * ①目标必须是普通文件（另见 R1-REL-11）；②只读连接；③不跑 Migration；
     * ④{@code commitState} 与兼容 {@code save*} 在写 SQL 之前就抛 {@code STORAGE_READ_ONLY}；
     * ⑤只读打开/尝试写/关闭之后，库文件与 sidecar 的 bytes+size+mtime 不变。
     */
    private static void caseR1Rel10() throws Exception {
        Path tmp = Files.createTempDirectory("r1-rel10");
        try {
            Path db = tmp.resolve("db.sqlite");
            try (SqliteStorage writable = new SqliteStorage(db)) {
                writable.commitState(new RegistryState(List.of(new Point("P01", "A")),
                        List.of(), List.of(), List.of(), List.of()));
            }
            Map<String, String> before = snapshot(tmp);
            require(before.containsKey("db.sqlite"), "基准库文件未生成：" + before.keySet());
            long beforeSize = Files.size(db);
            long beforeMtime = Files.getLastModifiedTime(db).toMillis();

            try (SqliteStorage readOnly = new SqliteStorage(db, true)) {
                // ④ 兼容 save*：写 SQL 之前就拒绝
                requireStorageReadOnly("只读 SqliteStorage.savePoints",
                        captureFailure(() -> readOnly.savePoints(
                                List.of(new Point("P01", "A"), new Point("P02", "B")))));

                // ④ commitState：候选状态本身非法（主键重复）时也必须先报 STORAGE_READ_ONLY，
                //    证明拒绝发生在任何写 SQL 之前（否则会先撞主键错误或数据库只读错误）
                requireStorageReadOnly("只读 SqliteStorage.commitState",
                        captureFailure(() -> readOnly.commitState(new RegistryState(
                                List.of(new Point("P01", "A"), new Point("P01", "B")),
                                List.of(), List.of(), List.of(), List.of()))));

                require(readOnly.loadPoints().size() == 1,
                        "只读实例读到 points=" + readOnly.loadPoints().size() + " 期望=1");
                require(readOnly.currentVersion() == 2,
                        "只读实例 currentVersion=" + readOnly.currentVersion() + " 期望=2");
            }

            // ⑤ close 之后：bytes+size+mtime+sidecar 全部不变
            Map<String, String> after = snapshot(tmp);
            require(after.equals(before),
                    "只读打开/拒写/关闭之后库文件或 sidecar 变了（bytes/size/mtime）："
                            + diff(before, after));
            require(Files.size(db) == beforeSize,
                    "库文件大小=" + Files.size(db) + " 期望仍为 " + beforeSize);
            require(Files.getLastModifiedTime(db).toMillis() == beforeMtime,
                    "库文件 mtime=" + Files.getLastModifiedTime(db).toMillis()
                            + " 期望仍为 " + beforeMtime);

            // ③ 只读不跑 Migration：空库文件只读打开后仍无表，文件仍为空
            Path blank = tmp.resolve("blank.sqlite");
            Files.write(blank, new byte[0]);
            try (SqliteStorage blankStorage = new SqliteStorage(blank, true)) {
                require(captureFailure(blankStorage::loadState) != null,
                        "空库只读打开后 loadState 竟成功：说明只读时执行了 Migration");
            }
            require(Files.size(blank) == 0,
                    "空库只读打开后大小=" + Files.size(blank) + " 期望仍为 0");
            require(!Files.exists(tmp.resolve("blank.sqlite-journal")),
                    "只读打开空库后出现了 journal sidecar");
        } finally {
            deleteRecursively(tmp);
        }
    }

    // ================= R1-REL-11 =================

    /**
     * 只读 + 库不存在（以及目录/符号链接）：报 READ_ONLY_STORAGE_NOT_FOUND，不建目录/文件。
     *
     * <p>含启动路径子断言：`lab.Main sqlite readonly` 在缺库目录下必须非零退出、
     * 首行 `ERR READ_ONLY_STORAGE_NOT_FOUND`，且不创建 `data/`。
     */
    private static void caseR1Rel11() throws Exception {
        Path tmp = Files.createTempDirectory("r1-rel11");
        try {
            // ① 缺库
            Path db = tmp.resolve("absent").resolve("db.sqlite");
            requireReadOnlyNotFound("只读打开不存在的库",
                    captureFailure(() -> new SqliteStorage(db, true)));
            require(!Files.exists(db), "只读启动创建了库文件 " + db);
            require(!Files.exists(db.getParent()), "只读启动创建了目录 " + db.getParent());

            // ② 目标是目录
            Path asDirectory = tmp.resolve("dir.sqlite");
            Files.createDirectory(asDirectory);
            requireReadOnlyNotFound("只读目标是目录",
                    captureFailure(() -> new SqliteStorage(asDirectory, true)));

            // ③ 目标是符号链接：NOFOLLOW，即使指向真实库也必须拒绝
            Path real = tmp.resolve("real.sqlite");
            try (SqliteStorage realStorage = new SqliteStorage(real)) {
                realStorage.commitState(new RegistryState(List.of(new Point("P01", "A")),
                        List.of(), List.of(), List.of(), List.of()));
            }
            Path link = tmp.resolve("link.sqlite");
            Files.createSymbolicLink(link, real.getFileName());
            Map<String, String> beforeLinkAttempt = snapshot(tmp);
            requireReadOnlyNotFound("只读目标是符号链接",
                    captureFailure(() -> new SqliteStorage(link, true)));
            require(snapshot(tmp).equals(beforeLinkAttempt),
                    "符号链接被拒绝后目录内容发生了变化：" + diff(beforeLinkAttempt, snapshot(tmp)));

            // 临时目录里只允许出现测试自己创建的三项
            List<String> leftovers = new ArrayList<>();
            try (Stream<Path> stream = Files.walk(tmp)) {
                stream.skip(1).forEach(p -> leftovers.add(tmp.relativize(p).toString()));
            }
            List<String> unexpected = leftovers.stream()
                    .filter(name -> !name.equals("dir.sqlite")
                            && !name.equals("real.sqlite")
                            && !name.equals("link.sqlite")
                            && !name.startsWith("real.sqlite-journal")
                            && !name.startsWith("real.sqlite-wal")
                            && !name.startsWith("real.sqlite-shm"))
                    .toList();
            require(unexpected.isEmpty(), "只读启动在临时目录留下了意外条目：" + unexpected);

            // ④ 启动路径：sqlite readonly + 缺库 → ERR READ_ONLY_STORAGE_NOT_FOUND，且不建 data/
            Path workDir = Files.createTempDirectory("r1-rel11-main");
            try {
                MainRun run = runMain(javaExecutable(), runtimeClassPath(), workDir,
                        new String[]{"sqlite", "readonly"}, "exit\n");
                require(run.exitCode() != 0,
                        "lab.Main sqlite readonly 缺库时退出码=" + run.exitCode() + " 期望非零");
                require("ERR READ_ONLY_STORAGE_NOT_FOUND".equals(firstLine(run.output())),
                        "lab.Main sqlite readonly 缺库首行=" + firstLine(run.output())
                                + " 期望=ERR READ_ONLY_STORAGE_NOT_FOUND");
                require(!Files.exists(workDir.resolve("data")),
                        "只读启动创建了 data/ 目录");
            } finally {
                deleteRecursively(workDir);
            }
        } finally {
            deleteRecursively(tmp);
        }
    }

    // ================= R1-REL-12 =================

    /**
     * MigrationRunner.run 注入失败迁移：表不存在、版本不前进、autoCommit 恢复。
     */
    private static void caseR1Rel12() throws Exception {
        Path tmp = Files.createTempDirectory("r1-rel12");
        try {
            Path db = tmp.resolve("db.sqlite");
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db)) {
                boolean originalAutoCommit = conn.getAutoCommit();
                List<Migration> migrations = List.of(
                        new Migration(1, "ok", List.of(
                                "CREATE TABLE r1_ok (id INTEGER PRIMARY KEY)",
                                "INSERT INTO r1_ok (id) VALUES (1)")),
                        new Migration(2, "bad", List.of(
                                "CREATE TABLE r1_bad (id INTEGER PRIMARY KEY)",
                                "THIS IS NOT VALID SQL")));

                Throwable thrown = null;
                try {
                    MigrationRunner.run(conn, migrations);
                } catch (SQLException e) {
                    thrown = e;
                }
                require(thrown != null, "注入的失败迁移未抛出异常（异常被吞掉）");
                require(!tableExists(conn, "r1_bad"),
                        "失败迁移建的表 r1_bad 仍存在（未回滚）");
                require(tableExists(conn, "r1_ok"), "成功迁移的表 r1_ok 不存在");
                require(countRows(conn, "r1_ok") == 1,
                        "成功迁移的数据行数=" + countRows(conn, "r1_ok") + " 期望=1");
                require(MigrationRunner.currentVersion(conn) == 1,
                        "版本=" + MigrationRunner.currentVersion(conn) + " 期望=1（不得前进到 2）");
                require(conn.getAutoCommit() == originalAutoCommit,
                        "autoCommit=" + conn.getAutoCommit() + " 期望恢复为 " + originalAutoCommit);

                // 首条迁移就失败：版本停在 0
                Path db2 = tmp.resolve("db2.sqlite");
                try (Connection conn2 = DriverManager.getConnection("jdbc:sqlite:" + db2)) {
                    boolean originalAutoCommit2 = conn2.getAutoCommit();
                    Throwable thrown2 = null;
                    try {
                        MigrationRunner.run(conn2, List.of(new Migration(1, "bad", List.of(
                                "CREATE TABLE r1_first_bad (id INTEGER)",
                                "ALSO NOT VALID SQL"))));
                    } catch (SQLException e) {
                        thrown2 = e;
                    }
                    require(thrown2 != null, "首条迁移失败未抛出异常（异常被吞掉）");
                    require(!tableExists(conn2, "r1_first_bad"),
                            "首条失败迁移建的表仍存在（未回滚）");
                    require(MigrationRunner.currentVersion(conn2) == 0,
                            "版本=" + MigrationRunner.currentVersion(conn2) + " 期望=0（不得前进）");
                    require(conn2.getAutoCommit() == originalAutoCommit2,
                            "autoCommit=" + conn2.getAutoCommit() + " 期望恢复为 " + originalAutoCommit2);
                }
            }

            // 子断言：注入 RuntimeException（非 SQLException）也必须整体回滚、恢复 autoCommit
            Path db3 = tmp.resolve("db3.sqlite");
            try (Connection conn3 = DriverManager.getConnection("jdbc:sqlite:" + db3)) {
                boolean originalAutoCommit3 = conn3.getAutoCommit();
                List<String> runtimeFailure = new AbstractList<String>() {
                    @Override
                    public String get(int index) {
                        if (index == 0) {
                            return "CREATE TABLE r1_runtime_bad (id INTEGER)";
                        }
                        throw new IllegalStateException("INJECTED_RUNTIME_FAILURE");
                    }

                    @Override
                    public int size() {
                        return 2;
                    }
                };

                Throwable thrown3 = null;
                try {
                    MigrationRunner.run(conn3,
                            List.of(new Migration(1, "runtime-failure", runtimeFailure)));
                } catch (RuntimeException e) {
                    thrown3 = e;
                }
                require(thrown3 instanceof IllegalStateException,
                        "注入 RuntimeException 后抛出=" + describe(thrown3)
                                + " 期望=IllegalStateException");
                require(!tableExists(conn3, "r1_runtime_bad"),
                        "RuntimeException 时部分迁移被提交（表 r1_runtime_bad 仍存在，未回滚）");
                require(MigrationRunner.currentVersion(conn3) == 0,
                        "版本=" + MigrationRunner.currentVersion(conn3) + " 期望=0（不得前进）");
                require(conn3.getAutoCommit() == originalAutoCommit3,
                        "autoCommit=" + conn3.getAutoCommit() + " 期望恢复为 " + originalAutoCommit3);
            }
        } finally {
            deleteRecursively(tmp);
        }
    }

    // ================= R1-REL-13 =================

    /**
     * ProcessBuilder 跑 main：未知/重复/冲突参数 → 退出码 2、不进命令循环。
     */
    private static void caseR1Rel13() throws Exception {
        Path javaExe = javaExecutable();
        String classPath = runtimeClassPath();
        Path workDir = Path.of(System.getProperty("user.dir"));

        runMainExpectStartupError(javaExe, classPath, workDir,
                new String[]{"bogus"}, "ERR STARTUP_ARGUMENT bogus");
        runMainExpectStartupError(javaExe, classPath, workDir,
                new String[]{"readonly", "readonly"}, "ERR STARTUP_ARGUMENT readonly");
        runMainExpectStartupError(javaExe, classPath, workDir,
                new String[]{"file", "sqlite"}, "ERR STARTUP_CONFLICT storage");
        runMainExpectStartupError(javaExe, classPath, workDir,
                new String[]{"fake-accept", "fake-reject"}, "ERR STARTUP_CONFLICT fake");
        // 重复参数 / 混合冲突 / 未知选项：同样退出码 2 且不进命令循环
        runMainExpectStartupError(javaExe, classPath, workDir,
                new String[]{"file", "file"}, "ERR STARTUP_CONFLICT storage");
        runMainExpectStartupError(javaExe, classPath, workDir,
                new String[]{"fake-timeout-created", "fake-accept"}, "ERR STARTUP_CONFLICT fake");
        runMainExpectStartupError(javaExe, classPath, workDir,
                new String[]{"sqlite", "readonly", "file"}, "ERR STARTUP_CONFLICT storage");
        runMainExpectStartupError(javaExe, classPath, workDir,
                new String[]{"--help"}, "ERR STARTUP_ARGUMENT --help");
    }

    private static void runMainExpectStartupError(Path javaExe, String classPath, Path workDir,
                                                  String[] args, String expectedErrorLine) throws Exception {
        MainRun run = runMain(javaExe, classPath, workDir, args,
                "stats\npoint add P01 A\napplication list\nexit\n");
        String text = run.output();
        require(run.exitCode() == 2, "参数 " + Arrays.toString(args) + " 退出码=" + run.exitCode()
                + " 期望=2 输出=" + text.trim());
        require(expectedErrorLine.equals(firstLine(text)),
                "参数 " + Arrays.toString(args) + " 首行=" + firstLine(text)
                        + " 期望=" + expectedErrorLine);
        require(!text.contains("STATS"), "启动参数错误后仍进入命令循环（出现了 STATS）：" + text.trim());
        require(!text.contains("BYE"), "启动参数错误后仍执行了 exit（出现了 BYE）：" + text.trim());
        require(!text.contains("OK POINT_ADDED"),
                "启动参数错误后仍执行了命令（出现了 OK POINT_ADDED）：" + text.trim());
    }

    /**
     * 用 ProcessBuilder 起一个 {@code lab.Main} 子进程，返回退出码与合并后的输出。
     */
    private static MainRun runMain(Path javaExe, String classPath, Path workDir,
                                   String[] args, String stdinContent) throws Exception {
        Path stdinFile = Files.createTempFile("r1-main", ".in");
        try {
            Files.writeString(stdinFile, stdinContent, StandardCharsets.UTF_8);

            List<String> command = new ArrayList<>();
            command.add(javaExe.toString());
            command.add("-cp");
            command.add(classPath);
            command.add("lab.Main");
            command.addAll(Arrays.asList(args));

            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(workDir.toFile());
            builder.redirectErrorStream(true);
            builder.redirectInput(stdinFile.toFile());

            Process process = builder.start();
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            require(finished, "lab.Main 未在 30s 内退出：args=" + Arrays.toString(args)
                    + " 输出=" + output.toString().trim());
            return new MainRun(process.exitValue(), output.toString());
        } finally {
            Files.deleteIfExists(stdinFile);
        }
    }

    private static Path javaExecutable() {
        Path javaExe = Path.of(System.getProperty("java.home"), "bin", "java");
        require(Files.isExecutable(javaExe), "找不到 java 可执行文件：" + javaExe);
        return javaExe;
    }

    /**
     * 子进程可用的绝对 classpath（子进程的 cwd 可能与测试进程不同）。
     */
    private static String runtimeClassPath() {
        List<String> absolute = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
            absolute.add(Path.of(entry).toAbsolutePath().toString());
        }
        return String.join(java.io.File.pathSeparator, absolute);
    }

    private static String firstLine(String text) {
        return text.isEmpty() ? "" : text.split("\n", -1)[0].trim();
    }

    private record MainRun(int exitCode, String output) {
    }

    // ================= R1-REL-14 =================

    /**
     * 三步 workflow 后 application 与 workflow 同时 COMPLETED。
     */
    private static void caseR1Rel14() {
        MemoryStorage committed = new MemoryStorage();
        CountingPort port = new CountingPort(FakeRcsAdapter.Mode.ACCEPT);
        Registry registry = new Registry(committed, port);
        registerBaseline(registry);
        registry.startWorkflow("APP-001");

        WorkflowInstance dispatchStep = registry.advanceWorkflow("APP-001");
        require(appState(registry) == ApplicationState.DISPATCHED,
                "第 1 步后申请状态=" + appState(registry) + " 期望=DISPATCHED");
        require(dispatchStep.currentStepIndex() == 1
                        && dispatchStep.steps().get(0).state() == WorkflowStepState.COMPLETED,
                "第 1 步后 stepIndex=" + dispatchStep.currentStepIndex()
                        + " step0=" + dispatchStep.steps().get(0).state() + " 期望=1/COMPLETED");
        require(dispatchStep.state() == WorkflowState.RUNNING,
                "第 1 步后 workflow 状态=" + dispatchStep.state() + " 期望=RUNNING");

        WorkflowInstance executeStep = registry.advanceWorkflow("APP-001");
        require(appState(registry) == ApplicationState.EXECUTING,
                "第 2 步后申请状态=" + appState(registry) + " 期望=EXECUTING");
        require(executeStep.currentStepIndex() == 2
                        && executeStep.steps().get(1).state() == WorkflowStepState.COMPLETED,
                "第 2 步后 stepIndex=" + executeStep.currentStepIndex()
                        + " step1=" + executeStep.steps().get(1).state() + " 期望=2/COMPLETED");

        WorkflowInstance completeStep = registry.advanceWorkflow("APP-001");
        require(appState(registry) == ApplicationState.COMPLETED,
                "第 3 步后申请状态=" + appState(registry) + " 期望=COMPLETED");
        require(completeStep.state() == WorkflowState.COMPLETED,
                "第 3 步后 workflow 状态=" + completeStep.state() + " 期望=COMPLETED");
        require(completeStep.currentStepIndex() == 3,
                "第 3 步后 stepIndex=" + completeStep.currentStepIndex() + " 期望=3");
        for (WorkflowStep step : completeStep.steps()) {
            require(step.state() == WorkflowStepState.COMPLETED,
                    "第 3 步后 " + step.name() + "=" + step.state() + " 期望=COMPLETED");
        }

        require(committedApp(committed, "APP-001").state() == ApplicationState.COMPLETED,
                "已提交申请状态=" + committedApp(committed, "APP-001").state() + " 期望=COMPLETED");
        require(committedWorkflow(committed, "APP-001").state() == WorkflowState.COMPLETED,
                "已提交 workflow 状态=" + committedWorkflow(committed, "APP-001").state()
                        + " 期望=COMPLETED（内存与已提交不一致）");
    }

    // ================= 运行与断言基础设施 =================

    private interface CaseBody {
        void run() throws Exception;
    }

    private interface Action {
        void run();
    }

    private interface TransitionOp {
        TransferApplication apply(Registry registry);
    }

    private static void run(String id, String intent, CaseBody body) {
        try {
            body.run();
        } catch (Throwable failure) {
            failed++;
            System.out.println(id + " FAIL: " + failureText(failure));
            System.out.println(id + " INTENT: " + intent);
            return;
        }
        passed++;
        System.out.println(id + " PASS");
    }

    private static String failureText(Throwable failure) {
        if (failure instanceof AssertionError) {
            return failure.getMessage() == null ? "断言失败（无详细信息）" : failure.getMessage();
        }
        return describe(failure);
    }

    private static String describe(Throwable failure) {
        if (failure == null) {
            return "(无异常)";
        }
        String message = failure.getMessage();
        return failure.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void expectFailure(String message, Action action) {
        try {
            action.run();
        } catch (RuntimeException expected) {
            return;
        }
        throw new AssertionError(message + "（实际未抛出异常）");
    }

    /**
     * 执行动作并返回异常（没有异常时返回 null）。
     */
    private static Throwable captureFailure(Action action) {
        try {
            action.run();
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }

    private static void requireStorageReadOnly(String label, Throwable failure) {
        require(failure != null, label + " 未抛异常（期望 STORAGE_READ_ONLY）");
        require(failure instanceof RegistryException
                        && "STORAGE_READ_ONLY".equals(((RegistryException) failure).code()),
                label + " 抛出 " + describe(failure) + " 期望=RegistryException/STORAGE_READ_ONLY");
    }

    private static void requireReadOnlyNotFound(String label, Throwable failure) {
        require(failure != null, label + " 未抛异常（期望 READ_ONLY_STORAGE_NOT_FOUND）");
        require(failure instanceof RegistryException
                        && "READ_ONLY_STORAGE_NOT_FOUND".equals(((RegistryException) failure).code()),
                label + " 抛出 " + describe(failure)
                        + " 期望=RegistryException/READ_ONLY_STORAGE_NOT_FOUND");
    }

    // ================= 业务状态读取 =================

    private static void registerBaseline(Registry registry) {
        registry.registerPoint("P01", "A");
        registry.registerPoint("P02", "B");
        registry.registerApplication("APP-001", "P01", "P02");
    }

    private static ApplicationState appState(Registry registry) {
        return registry.findApplication("APP-001")
                .orElseThrow(() -> new AssertionError("申请 APP-001 不存在"))
                .state();
    }

    private static OutboxEntry onlyOutbox(Registry registry) {
        List<OutboxEntry> all = registry.listOutbox();
        require(all.size() == 1, "outbox 条数=" + all.size() + " 期望=1");
        return all.get(0);
    }

    private static OutboxStatus outboxStatus(Registry registry) {
        return onlyOutbox(registry).status();
    }

    private static WorkflowInstance workflow(Registry registry, String applicationId) {
        return registry.findWorkflow(applicationId)
                .orElseThrow(() -> new AssertionError("workflow " + applicationId + " 不存在"));
    }

    private static TransferApplication committedApp(Storage storage, String applicationId) {
        for (TransferApplication application : storage.loadApplications()) {
            if (application.applicationId().equals(applicationId)) {
                return application;
            }
        }
        throw new AssertionError("已提交存储中没有申请 " + applicationId);
    }

    private static OutboxEntry committedOutbox(Storage storage, String entryId) {
        for (OutboxEntry entry : storage.loadOutbox()) {
            if (entry.entryId().equals(entryId)) {
                return entry;
            }
        }
        throw new AssertionError("已提交存储中没有 outbox " + entryId);
    }

    private static WorkflowInstance committedWorkflow(Storage storage, String applicationId) {
        for (WorkflowInstance instance : storage.loadWorkflows()) {
            if (instance.applicationId().equals(applicationId)) {
                return instance;
            }
        }
        throw new AssertionError("已提交存储中没有 workflow " + applicationId);
    }

    // ================= 测试替身 =================

    /**
     * 计数外部端口：委托给 FakeRcsAdapter，同时记录 create/query 调用次数。
     */
    private static final class CountingPort implements RcsExecutionPort {

        private final FakeRcsAdapter delegate;
        private int createCalls;
        private int queryCalls;

        CountingPort(FakeRcsAdapter.Mode mode) {
            this.delegate = new FakeRcsAdapter(mode);
        }

        @Override
        public ExternalOrderResult createOrder(String applicationId,
                                               String fromPointId,
                                               String toPointId) {
            createCalls++;
            return delegate.createOrder(applicationId, fromPointId, toPointId);
        }

        @Override
        public ExternalOrderResult queryOrder(String applicationId) {
            queryCalls++;
            return delegate.queryOrder(applicationId);
        }
    }

    /**
     * 故障注入存储：委托给真实存储，在"第 N 次提交"或"满足条件的提交"上注入一次失败。
     *
     * <p>T2 起 Storage 只接受整状态提交，故障注入点随之从 save* 移到 commitState；
     * 各 case 的断言与期望值保持不变。
     */
    private static final class FaultStorage implements Storage {

        private final Storage delegate;
        private Predicate<RegistryState> predicate;
        private int armAtCommit = -1;
        private int commitsSinceArm;

        FaultStorage(Storage delegate) {
            this.delegate = delegate;
        }

        /**
         * 从此刻起，第 nth 次 commitState 抛一次失败。
         */
        void armCommit(int nth) {
            this.predicate = null;
            this.armAtCommit = Math.max(1, nth);
            this.commitsSinceArm = 0;
        }

        /**
         * 从此刻起，第一个满足条件的候选状态提交失败。
         */
        void armWhen(Predicate<RegistryState> condition) {
            this.predicate = condition;
            this.armAtCommit = -1;
            this.commitsSinceArm = 0;
        }

        void clearFaults() {
            this.predicate = null;
            this.armAtCommit = -1;
        }

        @Override
        public RegistryState loadState() {
            return delegate.loadState();
        }

        @Override
        public void commitState(RegistryState next) {
            if (predicate != null) {
                if (predicate.test(next)) {
                    predicate = null;
                    throw new IllegalStateException("INJECTED_FAILURE commitState");
                }
            } else if (armAtCommit > 0) {
                commitsSinceArm++;
                if (commitsSinceArm >= armAtCommit) {
                    armAtCommit = -1;
                    throw new IllegalStateException("INJECTED_FAILURE commitState");
                }
            }
            delegate.commitState(next);
        }
    }

    // ================= 文件与 SQL 工具 =================

    /**
     * 记录目录下每个条目的"大小:sha256"（含 sidecar 文件）。
     */
    private static Map<String, String> snapshot(Path dir) throws Exception {
        Map<String, String> result = new TreeMap<>();
        try (Stream<Path> stream = Files.list(dir)) {
            for (Path path : stream.toList()) {
                String name = path.getFileName().toString();
                if (!Files.isRegularFile(path)) {
                    result.put(name, "NON_REGULAR_FILE");
                    continue;
                }
                result.put(name, Files.size(path) + ":" + sha256(path)
                        + ":" + Files.getLastModifiedTime(path).toMillis());
            }
        }
        return result;
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = digest.digest(Files.readAllBytes(file));
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            builder.append(String.format("%02x", b));
        }
        return builder.toString();
    }

    private static String diff(Map<String, String> before, Map<String, String> after) {
        Map<String, String> names = new TreeMap<>();
        before.forEach((key, value) -> names.put(key, value));
        after.forEach((key, value) -> names.putIfAbsent(key, value));
        List<String> changed = new ArrayList<>();
        for (String name : names.keySet()) {
            String left = before.get(name);
            String right = after.get(name);
            if (left == null) {
                changed.add(name + "=新增");
            } else if (right == null) {
                changed.add(name + "=消失");
            } else if (!left.equals(right)) {
                changed.add(name + "=" + left + "->" + right);
            }
        }
        return changed.isEmpty() ? "(无差异)" : changed.toString();
    }

    private static boolean tableExists(Connection conn, String tableName) throws SQLException {
        try (Statement statement = conn.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table' AND name='" + tableName + "'")) {
            return resultSet.next();
        }
    }

    private static int countRows(Connection conn, String tableName) throws SQLException {
        try (Statement statement = conn.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + tableName)) {
            return resultSet.next() ? resultSet.getInt(1) : -1;
        }
    }

    private static void deleteRecursively(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(path)) {
            stream.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception e) {
                    throw new IllegalStateException("cannot delete " + p, e);
                }
            });
        } catch (Exception e) {
            throw new IllegalStateException("cannot clean temp dir " + path, e);
        }
    }

}
