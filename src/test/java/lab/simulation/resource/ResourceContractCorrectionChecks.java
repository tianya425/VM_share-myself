package lab.simulation.resource;

import lab.ApplicationState;
import lab.FileStorage;
import lab.MemoryStorage;
import lab.Point;
import lab.Registry;
import lab.RegistryException;
import lab.RegistryState;
import lab.StateFileOps;
import lab.Storage;
import lab.TransferApplication;
import lab.simulation.OracleResult;
import lab.simulation.OracleRule;
import lab.simulation.ResourceInvariantOracle;
import lab.simulation.ResourceStep;
import lab.simulation.RestartPair;
import lab.simulation.ScenarioEvent;
import lab.simulation.ScenarioPackage;
import lab.simulation.ScenarioParser;
import lab.simulation.ScenarioValidationException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * STU-SIM-02-R1 资源合同锁（C1 先红）：R1-CON / R1-DOM / R1-LOAD / R1-ORC / R1-DSL / R1-STORE
 * 共 42 个具名 case，每个 case 一个独立方法。
 *
 * <p>每个 case 只做合同断言：合法输入必须被接受，非法输入必须失败，提交失败必须无幽灵状态。
 * 对生产代码中尚不存在的符号（枚举常量 / 字段 / 方法），一律用反射检查存在性与签名并输出
 * 合同缺失，绝不直接引用不存在的符号导致测试自身无法编译。
 *
 * <p>整改前在未修复的生产代码上运行：编译成功 + 合同断言失败（真实红灯），
 * 最后打印 {@code RESOURCE_R1_CONTRACT_CASES_FAILED=<n>} 与
 * {@code RESOURCE_R1_CONTRACT_CASES_PASSED=<m>}，n+m=42 且 n&gt;0，退出码 1。
 * 整改后全部通过时打印 {@code RESOURCE_R1_CONTRACT_CASES_PASSED=42} 且退出码 0。
 */
public final class ResourceContractCorrectionChecks {

    private static final int CASE_TOTAL = 42;

    private static int passed = 0;
    private static int failed = 0;
    private static Path tmpDir;

    private ResourceContractCorrectionChecks() {
    }

    public static void main(String[] args) {
        try {
            try {
                tmpDir = Files.createTempDirectory("resource-contract-checks-");
            } catch (Exception e) {
                throw new IllegalStateException("无法创建临时目录", e);
            }
            run("R1-CON-01", "推进到 DISPATCHED 后对完全相同目标再次 request → IDEMPOTENT，快照/排序键/条目数不变",
                    ResourceContractCorrectionChecks::r1Con01);
            run("R1-CON-02", "同申请换目标 → 返回 CONFLICT（不抛异常），快照不变", ResourceContractCorrectionChecks::r1Con02);
            run("R1-CON-03", "application 不存在 → RESERVATION_APPLICATION_NOT_FOUND",
                    ResourceContractCorrectionChecks::r1Con03);
            run("R1-CON-04", "Destination 不存在 → RESOURCE_DESTINATION_NOT_FOUND",
                    ResourceContractCorrectionChecks::r1Con04);
            run("R1-CON-05", "ServicePoint 不存在 → RESOURCE_SERVICE_POINT_NOT_FOUND",
                    ResourceContractCorrectionChecks::r1Con05);
            run("R1-CON-06", "Resource 不存在 → RESOURCE_NOT_FOUND", ResourceContractCorrectionChecks::r1Con06);

            run("R1-DOM-01", "Destination ID=null/blank/非法格式 → 构造立即失败", ResourceContractCorrectionChecks::r1Dom01);
            run("R1-DOM-02", "Destination name 空或 trim 后超过 40 字符 → 构造立即失败",
                    ResourceContractCorrectionChecks::r1Dom02);
            run("R1-DOM-03", "ServicePoint 任一 ID null/blank → 构造立即失败", ResourceContractCorrectionChecks::r1Dom03);
            run("R1-DOM-04", "VirtualResource availability=null → 构造立即失败",
                    ResourceContractCorrectionChecks::r1Dom04);
            run("R1-DOM-05", "Reservation state=null → 构造立即失败", ResourceContractCorrectionChecks::r1Dom05);
            run("R1-DOM-06", "Reservation requestedOffsetMillis<0 → 构造立即失败",
                    ResourceContractCorrectionChecks::r1Dom06);
            run("R1-DOM-07", "Reservation requestedLogicalSequence<1 → 构造立即失败",
                    ResourceContractCorrectionChecks::r1Dom07);
            run("R1-DOM-08", "reservationId 不等于 RSV-+applicationId → 构造立即失败",
                    ResourceContractCorrectionChecks::r1Dom08);

            run("R1-LOAD-01", "Reservation 引用不存在的 Application → load/Registry 构造失败",
                    ResourceContractCorrectionChecks::r1Load01);
            run("R1-LOAD-02", "Reservation 引用不存在的 Destination/ServicePoint/Resource → 失败",
                    ResourceContractCorrectionChecks::r1Load02);
            run("R1-LOAD-03", "同一 application 两条非终态 Reservation → 失败", ResourceContractCorrectionChecks::r1Load03);
            run("R1-LOAD-04", "同一 resource 两条 HELD Reservation → 失败", ResourceContractCorrectionChecks::r1Load04);
            run("R1-LOAD-05", "reservationId 公式错误 → 失败", ResourceContractCorrectionChecks::r1Load05);
            run("R1-LOAD-06", "ServicePoint 引用不存在的 Registry Point → 失败", ResourceContractCorrectionChecks::r1Load06);

            run("R1-ORC-01", "同一 resource 两条 HELD → ORC-RES-SYS-001=FAIL", ResourceContractCorrectionChecks::r1Orc01);
            run("R1-ORC-02", "同一 application 两条非终态 → ORC-RES-SYS-002=FAIL", ResourceContractCorrectionChecks::r1Orc02);
            run("R1-ORC-03", "任一引用链悬空/不匹配 → ORC-RES-SYS-003=FAIL", ResourceContractCorrectionChecks::r1Orc03);
            run("R1-ORC-04", "WAITING 逆序或重复 → ORC-RES-SYS-004=FAIL", ResourceContractCorrectionChecks::r1Orc04);
            run("R1-ORC-05", "RELEASED/CANCELLED 后重新 HELD/WAITING → ORC-RES-SYS-005=FAIL",
                    ResourceContractCorrectionChecks::r1Orc05);
            run("R1-ORC-06", "restart before/after 摘要不一致 → ORC-RES-SYS-006=FAIL", ResourceContractCorrectionChecks::r1Orc06);

            run("R1-DSL-01", "只接受固定 8 Action 及固定参数数", ResourceContractCorrectionChecks::r1Dsl01);
            run("R1-DSL-02", "只接受固定 8 EXPECT 及固定参数数", ResourceContractCorrectionChecks::r1Dsl02);
            run("R1-DSL-03", "SERVICE_POINT_ADD 必须是 3 个参数", ResourceContractCorrectionChecks::r1Dsl03);
            run("R1-DSL-04", "RESOURCE_ADD 必须存在", ResourceContractCorrectionChecks::r1Dsl04);
            run("R1-DSL-05", "WAIT_QUEUE 不允许空 token 或重复 applicationId", ResourceContractCorrectionChecks::r1Dsl05);
            run("R1-DSL-06", "HELD_COUNT 在 parse 阶段拒绝负数、非整数和溢出", ResourceContractCorrectionChecks::r1Dsl06);

            run("R1-STORE-01", "FileStorage 最终原子切换失败后，重启得到完整旧状态",
                    ResourceContractCorrectionChecks::r1Store01);
            run("R1-STORE-02", "staging 写失败后无正式新状态、无可加载 staging", ResourceContractCorrectionChecks::r1Store02);
            run("R1-STORE-03", "addDestination commit 失败无幽灵对象", ResourceContractCorrectionChecks::r1Store03);
            run("R1-STORE-04", "addServicePoint commit 失败无幽灵对象", ResourceContractCorrectionChecks::r1Store04);
            run("R1-STORE-05", "addResource commit 失败无幽灵对象", ResourceContractCorrectionChecks::r1Store05);
            run("R1-STORE-06", "setAvailability commit 失败状态不变", ResourceContractCorrectionChecks::r1Store06);
            run("R1-STORE-07", "request commit 失败无预约和 decision 幽灵状态", ResourceContractCorrectionChecks::r1Store07);
            run("R1-STORE-08", "release commit 失败 holder/queue 不变", ResourceContractCorrectionChecks::r1Store08);
            run("R1-STORE-09", "cancel commit 失败 holder/queue 不变", ResourceContractCorrectionChecks::r1Store09);
            run("R1-STORE-10", "promotion commit 失败 holder/queue 不变", ResourceContractCorrectionChecks::r1Store10);
        } finally {
            deleteRecursively(tmpDir);
        }

        if (passed + failed != CASE_TOTAL) {
            throw new AssertionError("case 数量不符：passed+failed=" + (passed + failed)
                    + " 期望=" + CASE_TOTAL);
        }
        if (failed > 0) {
            System.out.println("RESOURCE_R1_CONTRACT_CASES_FAILED=" + failed);
            System.out.println("RESOURCE_R1_CONTRACT_CASES_PASSED=" + passed);
            System.exit(1);
        }
        System.out.println("RESOURCE_R1_CONTRACT_CASES_PASSED=" + passed);
    }

    // ================= R1-CON-01 =================

    private static void r1Con01() {
        Registry registry = registryWithChain();
        registry.requestReservation("APP-001", "D01", "SP01", "R01", 0, 10);
        int beforeCount = reservations(registry).size();
        registry.dispatchApplication("APP-001");
        require(registry.findApplication("APP-001").orElseThrow().state() == ApplicationState.DISPATCHED,
                "前置：APP-001 应推进到 DISPATCHED");
        String before = canonical(registry);

        ReservationDecision[] decision = new ReservationDecision[1];
        Throwable failure = capture(() -> decision[0] =
                registry.requestReservation("APP-001", "D01", "SP01", "R01", 0, 10));
        require(failure == null, "推进到 DISPATCHED 后重复 request 抛异常 expected=IDEMPOTENT actual=" + failure);
        require(decision[0].type() == ReservationDecision.DecisionType.IDEMPOTENT,
                "type=" + decision[0].type() + " expected=IDEMPOTENT");
        require(canonical(registry).equals(before), "重复 request 后快照变了");
        require(reservations(registry).size() == beforeCount,
                "条目数=" + reservations(registry).size() + " expected=" + beforeCount);
        Reservation kept = reservationOf(registry, "APP-001");
        require(kept.requestedOffsetMillis() == 0L && kept.requestedLogicalSequence() == 10L,
                "排序键变了 offset=" + kept.requestedOffsetMillis() + " seq=" + kept.requestedLogicalSequence() + " expected=0/10");
    }

    // ================= R1-CON-02 =================

    private static void r1Con02() {
        require(hasNestedType(ReservationDecision.class, "DecisionType"),
                "ReservationDecision 缺少 DecisionType 枚举 expected=存在 actual=仅 Outcome");
        require(hasDeclaredMethod(ReservationDecision.class, "detail"),
                "ReservationDecision 缺少 detail 访问器 expected=getDeclaredMethod(detail) actual=无");

        Registry registry = registryWithChain();
        addSecondTarget(registry);
        registry.requestReservation("APP-002", "D01", "SP01", "R01", 0, 10);
        String before = canonical(registry);

        ReservationDecision.DecisionType conflict = requireDecisionType("CONFLICT");

        ReservationDecision[] decision = new ReservationDecision[1];
        Throwable failure = capture(() -> decision[0] =
                registry.requestReservation("APP-002", "D02", "SP02", "R02", 20, 10));
        require(failure == null, "换目标 request 抛异常 expected=无异常(CONFLICT) actual=" + failure);
        require(decision[0].type() == conflict,
                "type=" + decision[0].type() + " expected=CONFLICT");
        require(canonical(registry).equals(before), "换目标后快照变了");
    }

    // ================= R1-CON-03 =================

    private static void r1Con03() {
        Registry registry = registryWithChain();
        expectRegistryFailure(() -> registry.requestReservation("APP-404", "D01", "SP01", "R01", 0, 10),
                "RESERVATION_APPLICATION_NOT_FOUND", "application 不存在");
    }

    // ================= R1-CON-04 =================

    private static void r1Con04() {
        Registry registry = registryWithChain();
        expectRegistryFailure(() -> registry.requestReservation("APP-001", "D99", "SP01", "R01", 0, 10),
                "RESOURCE_DESTINATION_NOT_FOUND", "Destination 不存在");
    }

    // ================= R1-CON-05 =================

    private static void r1Con05() {
        Registry registry = registryWithChain();
        expectRegistryFailure(() -> registry.requestReservation("APP-001", "D01", "SP99", "R01", 0, 10),
                "RESOURCE_SERVICE_POINT_NOT_FOUND", "ServicePoint 不存在");
    }

    // ================= R1-CON-06 =================

    private static void r1Con06() {
        Registry registry = registryWithChain();
        expectRegistryFailure(() -> registry.requestReservation("APP-001", "D01", "SP01", "R99", 0, 10),
                "RESOURCE_NOT_FOUND", "Resource 不存在");
    }

    // ================= R1-DOM-01 =================

    private static void r1Dom01() {
        require(capture(() -> new Destination(null, "一号目的地")) != null,
                "Destination ID=null 未抛异常 expected=构造立即失败 actual=无异常");
        require(capture(() -> new Destination("", "一号目的地")) != null,
                "Destination ID=空串 未抛异常 expected=构造立即失败 actual=无异常");
        require(capture(() -> new Destination("   ", "一号目的地")) != null,
                "Destination ID=空白 未抛异常 expected=构造立即失败 actual=无异常");
        require(capture(() -> new Destination("1bad", "一号目的地")) != null,
                "Destination ID=非法格式 未抛异常 expected=构造立即失败 actual=无异常");
    }

    // ================= R1-DOM-02 =================

    private static void r1Dom02() {
        require(capture(() -> new Destination("D01", "")) != null,
                "Destination name=空 未抛异常 expected=构造立即失败 actual=无异常");
        require(capture(() -> new Destination("D01", "   ")) != null,
                "Destination name=空白(trim 后空) 未抛异常 expected=构造立即失败 actual=无异常");
        require(capture(() -> new Destination("D01", "X".repeat(41))) != null,
                "Destination name=41 字符 未抛异常 expected=构造立即失败 actual=无异常");
    }

    // ================= R1-DOM-03 =================

    private static void r1Dom03() {
        java.lang.reflect.RecordComponent[] comps = ServicePoint.class.getRecordComponents();
        require(comps.length == 3,
                "ServicePoint 字段数=" + comps.length + " expected=3（任一 ID null/blank 构造失败）");
        try {
            var ctor = ServicePoint.class.getConstructor(String.class, String.class, String.class);
            require(captureChecked(() -> ctor.newInstance((String) null, "D01", "P01")) != null,
                    "servicePointId=null 未抛异常 expected=构造立即失败 actual=无异常");
            require(captureChecked(() -> ctor.newInstance("", "D01", "P01")) != null,
                    "servicePointId=空 未抛异常 expected=构造立即失败 actual=无异常");
            require(captureChecked(() -> ctor.newInstance("SP01", null, "P01")) != null,
                    "destinationId=null 未抛异常 expected=构造立即失败 actual=无异常");
            require(captureChecked(() -> ctor.newInstance("SP01", "D01", null)) != null,
                    "pointId=null 未抛异常 expected=构造立即失败 actual=无异常");
            require(captureChecked(() -> ctor.newInstance("SP01", "D01", "")) != null,
                    "pointId=空 未抛异常 expected=构造立即失败 actual=无异常");
        } catch (NoSuchMethodException e) {
            throw new AssertionError("缺少 3 参数 ServicePoint 构造器 expected=存在 actual=" + e);
        }
    }

    // ================= R1-DOM-04 =================

    private static void r1Dom04() {
        require(capture(() -> new VirtualResource("R01", "SP01", (ResourceAvailability) null)) != null,
                "VirtualResource availability=null 未抛异常 expected=构造立即失败 actual=无异常");
    }

    // ================= R1-DOM-05 =================

    private static void r1Dom05() {
        require(capture(() -> new Reservation("RSV-APP-001", "APP-001", "D01", "SP01", "R01",
                null, 0, 10)) != null,
                "Reservation state=null 未抛异常 expected=构造立即失败 actual=无异常");
    }

    // ================= R1-DOM-06 =================

    private static void r1Dom06() {
        require(capture(() -> new Reservation("RSV-APP-001", "APP-001", "D01", "SP01", "R01",
                ReservationState.HELD, -1, 10)) != null,
                "requestedOffsetMillis<0 未抛异常 expected=构造立即失败 actual=无异常");
    }

    // ================= R1-DOM-07 =================

    private static void r1Dom07() {
        require(capture(() -> new Reservation("RSV-APP-001", "APP-001", "D01", "SP01", "R01",
                ReservationState.HELD, 0, 0)) != null,
                "requestedLogicalSequence<1 未抛异常 expected=构造立即失败 actual=无异常");
    }

    // ================= R1-DOM-08 =================

    private static void r1Dom08() {
        require(capture(() -> new Reservation("RSV-WRONG", "APP-001", "D01", "SP01", "R01",
                ReservationState.HELD, 0, 10)) != null,
                "reservationId 不等于 RSV-+applicationId 未抛异常 expected=构造立即失败 actual=无异常");
    }

    // ================= R1-LOAD-01 =================

    private static void r1Load01() {
        ResourceWorldSnapshot world = baselineWorld(List.of(
                reservation("RSV-APP-999", "APP-999", "D01", "SP01", "R01", ReservationState.HELD, 0, 10)));
        RegistryState state = loadState(baselinePoints(), baselineApps(), world);
        expectLoadFailure(state, "Reservation 引用不存在的 Application");
    }

    // ================= R1-LOAD-02 =================

    private static void r1Load02() {
        ResourceWorldSnapshot w1 = baselineWorld(List.of(
                reservation("RSV-APP-001", "APP-001", "D01", "SP01", "R99", ReservationState.HELD, 0, 10)));
        expectLoadFailure(loadState(baselinePoints(), baselineApps(), w1),
                "Reservation 引用不存在的 Resource");

        ResourceWorldSnapshot w2 = baselineWorld(List.of(
                reservation("RSV-APP-001", "APP-001", "D99", "SP01", "R01", ReservationState.HELD, 0, 10)));
        expectLoadFailure(loadState(baselinePoints(), baselineApps(), w2),
                "Reservation 引用不存在的 Destination");

        ResourceWorldSnapshot w3 = baselineWorld(List.of(
                reservation("RSV-APP-001", "APP-001", "D01", "SP99", "R01", ReservationState.HELD, 0, 10)));
        expectLoadFailure(loadState(baselinePoints(), baselineApps(), w3),
                "Reservation 引用不存在的 ServicePoint");
    }

    // ================= R1-LOAD-03 =================

    private static void r1Load03() {
        ResourceWorldSnapshot world = baselineWorld(List.of(
                reservation("RSV-APP-001", "APP-001", "D01", "SP01", "R01", ReservationState.HELD, 0, 10),
                reservation("RSV-APP-001", "APP-001", "D01", "SP01", "R01", ReservationState.WAITING, 20, 10)));
        expectLoadFailure(loadState(baselinePoints(), baselineApps(), world),
                "同一 application 两条非终态 Reservation");
    }

    // ================= R1-LOAD-04 =================

    private static void r1Load04() {
        ResourceWorldSnapshot world = baselineWorld(List.of(
                reservation("RSV-APP-001", "APP-001", "D01", "SP01", "R01", ReservationState.HELD, 0, 10),
                reservation("RSV-APP-002", "APP-002", "D01", "SP01", "R01", ReservationState.HELD, 20, 10)));
        List<TransferApplication> apps = List.of(
                new TransferApplication("APP-001", "P01", "P02"),
                new TransferApplication("APP-002", "P01", "P02"));
        expectLoadFailure(loadState(baselinePoints(), apps, world),
                "同一 resource 两条 HELD Reservation");
    }

    // ================= R1-LOAD-05 =================

    private static void r1Load05() throws Exception {
        // §8.5 之后 Reservation 构造器已拒绝错误公式；夹具先提交一份合法快照，
        // 再只在序列化文本里篡改 reservationId，使失败真正发生在 loadState 阶段，
        // 而不是在测试构造 Reservation 夹具时。
        Path dir = tmpDir.resolve("r1-load-05");
        ResourceWorldSnapshot world = baselineWorld(List.of(
                reservation("APP-001", ReservationState.HELD, 0, 10)));
        new FileStorage(dir).commitState(loadState(baselinePoints(), baselineApps(), world));

        Path snapshot = dir.resolve("registry-state.txt");
        String text = Files.readString(snapshot, StandardCharsets.UTF_8);
        String tampered = text.replace("V|RSV-APP-001|", "V|RSV-WRONG|");
        require(!tampered.equals(text), "夹具：registry-state.txt 中未找到可篡改的预约行");
        Files.writeString(snapshot, tampered, StandardCharsets.UTF_8);

        Throwable failure = capture(() -> new FileStorage(dir).loadState());
        require(failure != null,
                "reservationId 公式错误未导致 load 失败 expected=失败 actual=成功加载");
        require(failure instanceof RegistryException,
                "reservationId 公式错误：异常类型=" + failure.getClass().getSimpleName()
                        + " expected=RegistryException");
        RegistryException exception = (RegistryException) failure;
        require(exception.code().equals("RESOURCE_STATE_INVALID"),
                "reservationId 公式错误：code=" + exception.code()
                        + " expected=RESOURCE_STATE_INVALID");
        require(exception.detail().contains("rule=R11_RESERVATION_ID_FORMULA"),
                "reservationId 公式错误：detail=" + exception.detail());
    }

    // ================= R1-LOAD-06 =================

    private static void r1Load06() {
        ResourceWorldSnapshot world = new ResourceWorldSnapshot(
                List.of(new Destination("D01", "一号目的地")),
                List.of(new ServicePoint("SP01", "D01", "P99")),
                List.of(new VirtualResource("R01", "SP01")),
                List.of());
        expectLoadFailure(loadState(baselinePoints(), baselineApps(), world),
                "ServicePoint 引用不存在的 Registry Point");
    }

    // ================= R1-ORC-01 =================

    private static void r1Orc01() {
        ResourceWorldSnapshot good = orcSnapshot(List.of(
                reservation("APP-001", ReservationState.HELD, 0, 10)));
        require(orcStatus(good, List.of(), List.of(), "ORC-RES-SYS-001") == OracleResult.Status.PASS,
                "正例（单 HELD）ORC-RES-SYS-001 应 PASS");

        ResourceWorldSnapshot bad = orcSnapshot(List.of(
                reservation("APP-001", ReservationState.HELD, 0, 10),
                reservation("APP-002", ReservationState.HELD, 20, 10)));
        OracleResult result = orcResult(bad, List.of(), List.of(), "ORC-RES-SYS-001");
        require(result.status() == OracleResult.Status.FAIL,
                "同一 resource 两条 HELD 应触发 ORC-RES-SYS-001=FAIL actual=" + result.status());
    }

    // ================= R1-ORC-02 =================

    private static void r1Orc02() {
        ResourceWorldSnapshot good = orcSnapshot(List.of(
                reservation("APP-001", ReservationState.HELD, 0, 10)));
        require(orcStatus(good, List.of(), List.of(), "ORC-RES-SYS-002") == OracleResult.Status.PASS,
                "正例（每 application 至多一条非终态）ORC-RES-SYS-002 应 PASS");

        ResourceWorldSnapshot bad = orcSnapshot(List.of(
                reservation("RSV-APP-001", "APP-001", "D01", "SP01", "R01", ReservationState.HELD, 0, 10),
                reservation("RSV-APP-001", "APP-001", "D01", "SP01", "R01", ReservationState.WAITING, 20, 10)));
        OracleResult result = orcResult(bad, List.of(), List.of(), "ORC-RES-SYS-002");
        require(result.status() == OracleResult.Status.FAIL,
                "同一 application 两条非终态应触发 ORC-RES-SYS-002=FAIL actual=" + result.status());
    }

    // ================= R1-ORC-03 =================

    private static void r1Orc03() {
        ResourceWorldSnapshot good = orcSnapshot(List.of(
                reservation("APP-001", ReservationState.HELD, 0, 10)));
        require(orcStatus(good, List.of(), List.of(), "ORC-RES-SYS-003") == OracleResult.Status.PASS,
                "正例（引用链完整）ORC-RES-SYS-003 应 PASS");

        ResourceWorldSnapshot bad = new ResourceWorldSnapshot(
                List.of(new Destination("D01", "一号目的地")),
                List.of(new ServicePoint("SP01", "D01", "P01")),
                List.of(new VirtualResource("R01", "SP01")),
                List.of(reservation("RSV-APP-001", "APP-001", "D01", "SP01", "R99", ReservationState.HELD, 0, 10)));
        OracleResult result = orcResult(bad, List.of(), List.of(), "ORC-RES-SYS-003");
        require(result.status() == OracleResult.Status.FAIL,
                "引用链悬空应触发 ORC-RES-SYS-003=FAIL actual=" + result.status());
    }

    // ================= R1-ORC-04 =================

    private static void r1Orc04() {
        ResourceWorldSnapshot good = orcSnapshot(List.of(
                reservation("APP-001", ReservationState.HELD, 0, 10),
                reservation("APP-002", ReservationState.WAITING, 20, 10),
                reservation("APP-003", ReservationState.WAITING, 30, 10)));
        require(orcStatus(good, List.of(), List.of(), "ORC-RES-SYS-004") == OracleResult.Status.PASS,
                "正例（WAITING 升序无重复）ORC-RES-SYS-004 应 PASS");

        ResourceWorldSnapshot bad = orcSnapshot(List.of(
                reservation("RSV-APP-001", "APP-001", "D01", "SP01", "R01", ReservationState.WAITING, 0, 10),
                reservation("RSV-APP-001", "APP-001", "D01", "SP01", "R01", ReservationState.WAITING, 20, 10)));
        OracleResult result = orcResult(bad, List.of(), List.of(), "ORC-RES-SYS-004");
        require(result.status() == OracleResult.Status.FAIL,
                "WAITING 重复 applicationId 应触发 ORC-RES-SYS-004=FAIL actual=" + result.status());
    }

    // ================= R1-ORC-05 =================

    private static void r1Orc05() {
        ResourceWorldSnapshot good = orcSnapshot(List.of(
                reservation("APP-001", ReservationState.RELEASED, 0, 10)));
        require(orcStatus(good, List.of(), List.of(), "ORC-RES-SYS-005") == OracleResult.Status.PASS,
                "正例（终态不重开）ORC-RES-SYS-005 应 PASS");

        ResourceWorldSnapshot released = orcSnapshot(List.of(
                reservation("APP-001", ReservationState.RELEASED, 0, 10)));
        ResourceWorldSnapshot reopened = orcSnapshot(List.of(
                reservation("APP-001", ReservationState.HELD, 0, 10)));
        ResourceStep s1 = new ResourceStep("EVT-001", ScenarioEvent.Action.RESERVATION_RELEASE, "", "",
                orcSnapshot(List.of(reservation("APP-001", ReservationState.HELD, 0, 10))), released, null);
        ResourceStep s2 = new ResourceStep("EVT-002", ScenarioEvent.Action.RESERVATION_REQUEST, "R01", "",
                released, reopened, null);
        OracleResult result = orcResult(reopened, List.of(s1, s2), List.of(), "ORC-RES-SYS-005");
        require(result.status() == OracleResult.Status.FAIL,
                "RELEASED 后重新 HELD 应触发 ORC-RES-SYS-005=FAIL actual=" + result.status());
    }

    // ================= R1-ORC-06 =================

    private static void r1Orc06() {
        ResourceWorldSnapshot world = orcSnapshot(List.of(
                reservation("APP-001", ReservationState.HELD, 0, 10)));
        RestartPair goodPair = new RestartPair("EVT-001", "a".repeat(64), "a".repeat(64));
        require(orcStatus(world, List.of(), List.of(goodPair), "ORC-RES-SYS-006") == OracleResult.Status.PASS,
                "正例（摘要一致）ORC-RES-SYS-006 应 PASS");

        RestartPair badPair = new RestartPair("EVT-001", "a".repeat(64), "b".repeat(64));
        OracleResult result = orcResult(world, List.of(), List.of(badPair), "ORC-RES-SYS-006");
        require(result.status() == OracleResult.Status.FAIL,
                "restart 摘要不一致应触发 ORC-RES-SYS-006=FAIL actual=" + result.status());
    }

    // ================= R1-DSL-01 =================

    private static void r1Dsl01() {
        String[] actions = {"DESTINATION_ADD", "SERVICE_POINT_ADD", "RESOURCE_ADD", "RESOURCE_SET",
                "RESERVATION_REQUEST", "RESERVATION_RELEASE", "RESERVATION_CANCEL", "WORLD_RESTART"};
        for (String name : actions) {
            require(actionExists(name), "缺少 Action 常量 " + name + "（固定 8 Action）");
        }
        expectErr(write("r1-dsl-01b.scenario", """
                SCENARIO SIM-DSL01B 1
                START_AT 2026-01-01T00:00:00Z
                SEED 1
                AT 0 10 EVT-001 BOGUS_RESOURCE_ACTION X
                END
                """), "SCENARIO_UNKNOWN_ACTION", "BOGUS_RESOURCE_ACTION", "未知 action 应被拒绝");
    }

    // ================= R1-DSL-02 =================

    private static void r1Dsl02() {
        String[] rules = {"RESERVATION_STATE", "RESERVATION_DECISION", "RESOURCE_AVAILABILITY",
                "RESOURCE_HOLDER", "WAIT_QUEUE", "HELD_COUNT", "NO_DOUBLE_HOLD", "NO_ORPHAN_RESERVATION"};
        for (String name : rules) {
            require(ruleTypeExists(name), "缺少 RuleType 常量 " + name + "（固定 8 EXPECT）");
        }
        expectErr(write("r1-dsl-02b.scenario", """
                SCENARIO SIM-DSL02B 1
                START_AT 2026-01-01T00:00:00Z
                SEED 2
                AT 0 10 EVT-001 WORLD_RESTART
                EXPECT ORC-001 BOGUS_ORACLE R01 1
                END
                """), "SCENARIO_UNKNOWN_ORACLE", "BOGUS_ORACLE", "未知 EXPECT 应被拒绝");
    }

    // ================= R1-DSL-03 =================

    private static void r1Dsl03() {
        require(parseOk(write("r1-dsl-03a.scenario", """
                SCENARIO SIM-DSL03 1
                START_AT 2026-01-01T00:00:00Z
                SEED 3
                AT 0 10 EVT-001 SERVICE_POINT_ADD SP01 D01 P01
                END
                """)), "SERVICE_POINT_ADD 3 个参数应被接受 expected=解析成功 actual=SCENARIO_ARGUMENT_COUNT");
        expectErr(write("r1-dsl-03b.scenario", """
                SCENARIO SIM-DSL03B 1
                START_AT 2026-01-01T00:00:00Z
                SEED 3
                AT 0 10 EVT-001 SERVICE_POINT_ADD SP01 D01 P01 一号服务点
                END
                """), "SCENARIO_ARGUMENT_COUNT", "SERVICE_POINT_ADD", "SERVICE_POINT_ADD 4 个参数应被拒绝");
    }

    // ================= R1-DSL-04 =================

    private static void r1Dsl04() {
        require(actionExists("RESOURCE_ADD"), "缺少 Action 常量 RESOURCE_ADD（资源新增动作）");
    }

    // ================= R1-DSL-05 =================

    private static void r1Dsl05() {
        require(ruleTypeExists("WAIT_QUEUE"), "缺少 RuleType 常量 WAIT_QUEUE（等待队列 EXPECT）");
        expectAnyErr(write("r1-dsl-05a.scenario", """
                SCENARIO SIM-DSL05 1
                START_AT 2026-01-01T00:00:00Z
                SEED 5
                AT 0 10 EVT-001 WORLD_RESTART
                EXPECT ORC-001 WAIT_QUEUE R01 APP-001 APP-001
                END
                """), "WAIT_QUEUE 重复 applicationId 应被拒绝");
        expectAnyErr(write("r1-dsl-05b.scenario", """
                SCENARIO SIM-DSL05B 1
                START_AT 2026-01-01T00:00:00Z
                SEED 5
                AT 0 10 EVT-001 WORLD_RESTART
                EXPECT ORC-001 WAIT_QUEUE R01 APP-001  APP-002
                END
                """), "WAIT_QUEUE 空 token 应被拒绝");
    }

    // ================= R1-DSL-06 =================

    private static void r1Dsl06() {
        expectErr(write("r1-dsl-06a.scenario", """
                SCENARIO SIM-DSL06 1
                START_AT 2026-01-01T00:00:00Z
                SEED 6
                AT 0 10 EVT-001 WORLD_RESTART
                EXPECT ORC-001 HELD_COUNT R01 -1
                END
                """), "SCENARIO_ARGUMENT_COUNT", "HELD_COUNT", "HELD_COUNT 负数应被拒绝");
        expectErr(write("r1-dsl-06b.scenario", """
                SCENARIO SIM-DSL06B 1
                START_AT 2026-01-01T00:00:00Z
                SEED 6
                AT 0 10 EVT-001 WORLD_RESTART
                EXPECT ORC-001 HELD_COUNT R01 notanumber
                END
                """), "SCENARIO_ARGUMENT_COUNT", "HELD_COUNT", "HELD_COUNT 非整数应被拒绝");
        expectErr(write("r1-dsl-06c.scenario", """
                SCENARIO SIM-DSL06C 1
                START_AT 2026-01-01T00:00:00Z
                SEED 6
                AT 0 10 EVT-001 WORLD_RESTART
                EXPECT ORC-001 HELD_COUNT R01 3000000000
                END
                """), "SCENARIO_ARGUMENT_COUNT", "HELD_COUNT", "HELD_COUNT 溢出应被拒绝");
    }

    // ================= R1-STORE-01 =================

    private static void r1Store01() throws Exception {
        // 行为级验证（§11.4/§11.6）：注入 atomicMove 抛 IOException，
        // 最终原子切换失败 → 重启必须得到完整旧状态，且旧 registry-state.txt 字节不变。
        Path dir = tmpDir.resolve("r1-store-01");
        Registry seed = new Registry(new FileStorage(dir));
        seedChain(seed);
        String oldCanonical = canonical(seed);
        byte[] oldBytes = Files.readAllBytes(dir.resolve("registry-state.txt"));

        FailingStateFileOps ops = new FailingStateFileOps();
        Registry registry = new Registry(new FileStorage(dir, ops));
        String inMemoryBefore = canonical(registry);
        require(inMemoryBefore.equals(oldCanonical),
                "前置：重启后内存快照应等于旧状态");
        require(heldOf(registry, "R01").isEmpty() && queueOf(registry, "R01").isEmpty(),
                "前置：holder/queue 应为空");

        ops.failNextAtomicMove();
        expectCommitFailure(() -> registry.addDestination("D02", "二号目的地"),
                "R1-STORE-01 最终原子切换失败");

        // operationResult=FAIL；inMemoryCanonicalSnapshot=UNCHANGED；ghostEntity/Decision=NONE。
        require(canonical(registry).equals(inMemoryBefore),
                "最终原子切换失败后内存快照变了（inMemoryCanonicalSnapshot=CHANGED）");
        require(destinations(registry).size() == 1,
                "最终原子切换失败后内存出现幽灵 Destination：" + destinations(registry).size());
        require(heldOf(registry, "R01").isEmpty() && queueOf(registry, "R01").isEmpty(),
                "最终原子切换失败后 holder/queue 变了");
        require(Arrays.equals(Files.readAllBytes(dir.resolve("registry-state.txt")), oldBytes),
                "最终原子切换失败后旧 registry-state.txt 字节变了");
        require(!hasStagingFile(dir),
                "最终原子切换失败后残留可加载的 staging 文件");

        Registry restarted = new Registry(new FileStorage(dir));
        require(canonical(restarted).equals(oldCanonical),
                "最终原子切换失败后重启快照=" + canonical(restarted)
                        + " expected=OLD_STATE=" + oldCanonical);
        require(destinations(restarted).size() == 1,
                "最终原子切换失败后重启出现幽灵 Destination：" + destinations(restarted).size());
        require(reservations(restarted).isEmpty(),
                "最终原子切换失败后重启出现幽灵 Reservation");
    }

    // ================= R1-STORE-02 =================

    private static void r1Store02() throws Exception {
        // 行为级验证（§11/§11.6）：注入 staging 写失败，
        // 无正式新状态、无可加载 staging，重启得到完整旧状态。
        Path dir = tmpDir.resolve("r1-store-02");
        Registry seed = new Registry(new FileStorage(dir));
        seedChain(seed);
        String oldCanonical = canonical(seed);
        byte[] oldBytes = Files.readAllBytes(dir.resolve("registry-state.txt"));

        FailingStateFileOps ops = new FailingStateFileOps();
        Registry registry = new Registry(new FileStorage(dir, ops));
        String inMemoryBefore = canonical(registry);

        ops.failNextWrite();
        expectCommitFailure(() -> registry.addDestination("D02", "二号目的地"),
                "R1-STORE-02 staging 写失败");

        require(canonical(registry).equals(inMemoryBefore),
                "staging 写失败后内存快照变了（inMemoryCanonicalSnapshot=CHANGED）");
        require(destinations(registry).size() == 1,
                "staging 写失败后内存出现幽灵 Destination：" + destinations(registry).size());
        require(heldOf(registry, "R01").isEmpty() && queueOf(registry, "R01").isEmpty(),
                "staging 写失败后 holder/queue 变了");
        require(Arrays.equals(Files.readAllBytes(dir.resolve("registry-state.txt")), oldBytes),
                "staging 写失败后正式 registry-state.txt 字节变了");
        require(!hasStagingFile(dir),
                "staging 写失败后残留可加载的 staging 文件");

        Registry restarted = new Registry(new FileStorage(dir));
        require(canonical(restarted).equals(oldCanonical),
                "staging 写失败后重启快照=" + canonical(restarted)
                        + " expected=OLD_STATE=" + oldCanonical);
        require(destinations(restarted).size() == 1,
                "staging 写失败后重启出现幽灵 Destination：" + destinations(restarted).size());
    }

    // ================= R1-STORE-03 =================

    private static void r1Store03() {
        FailingStorage storage = new FailingStorage(new MemoryStorage());
        Registry registry = new Registry(storage);
        seedChain(registry);
        String before = canonical(registry);
        storage.failNextCommit();
        expectInjectedFailure(() -> registry.addDestination("D02", "二号目的地"), "addDestination 提交失败");
        require(canonical(registry).equals(before), "addDestination 提交失败后快照变了");
        require(destinations(registry).size() == 1,
                "Destination 条目数=" + destinations(registry).size() + " expected=1（无幽灵对象）");
        Registry restarted = new Registry(storage);
        require(canonical(restarted).equals(before),
                "addDestination 提交失败后重启快照变了（restartedCanonicalSnapshot != OLD_STATE）");
        require(destinations(restarted).size() == 1,
                "addDestination 提交失败后重启出现幽灵 Destination：" + destinations(restarted).size());
        require(heldOf(restarted, "R01").isEmpty() && queueOf(restarted, "R01").isEmpty(),
                "addDestination 提交失败后重启 holder/queue 变了");
    }

    // ================= R1-STORE-04 =================

    private static void r1Store04() {
        FailingStorage storage = new FailingStorage(new MemoryStorage());
        Registry registry = new Registry(storage);
        seedChain(registry);
        String before = canonical(registry);
        storage.failNextCommit();
        expectInjectedFailure(() -> registry.addServicePoint("SP02", "D01", "P02"),
                "addServicePoint 提交失败");
        require(canonical(registry).equals(before), "addServicePoint 提交失败后快照变了");
        require(servicePoints(registry).size() == 1,
                "ServicePoint 条目数=" + servicePoints(registry).size() + " expected=1（无幽灵对象）");
        Registry restarted = new Registry(storage);
        require(canonical(restarted).equals(before),
                "addServicePoint 提交失败后重启快照变了（restartedCanonicalSnapshot != OLD_STATE）");
        require(servicePoints(restarted).size() == 1,
                "addServicePoint 提交失败后重启出现幽灵 ServicePoint：" + servicePoints(restarted).size());
        require(heldOf(restarted, "R01").isEmpty() && queueOf(restarted, "R01").isEmpty(),
                "addServicePoint 提交失败后重启 holder/queue 变了");
    }

    // ================= R1-STORE-05 =================

    private static void r1Store05() {
        FailingStorage storage = new FailingStorage(new MemoryStorage());
        Registry registry = new Registry(storage);
        seedChain(registry);
        String before = canonical(registry);
        storage.failNextCommit();
        expectInjectedFailure(() -> registry.addVirtualResource("R02", "SP01"), "addResource 提交失败");
        require(canonical(registry).equals(before), "addResource 提交失败后快照变了");
        require(resources(registry).size() == 1,
                "VirtualResource 条目数=" + resources(registry).size() + " expected=1（无幽灵对象）");
        Registry restarted = new Registry(storage);
        require(canonical(restarted).equals(before),
                "addResource 提交失败后重启快照变了（restartedCanonicalSnapshot != OLD_STATE）");
        require(resources(restarted).size() == 1,
                "addResource 提交失败后重启出现幽灵 VirtualResource：" + resources(restarted).size());
        require(heldOf(restarted, "R01").isEmpty() && queueOf(restarted, "R01").isEmpty(),
                "addResource 提交失败后重启 holder/queue 变了");
    }

    // ================= R1-STORE-06 =================

    private static void r1Store06() {
        FailingStorage storage = new FailingStorage(new MemoryStorage());
        Registry registry = new Registry(storage);
        seedChain(registry);
        String before = canonical(registry);
        storage.failNextCommit();
        expectInjectedFailure(() -> registry.setVirtualResourceAvailability("R01", ResourceAvailability.DISABLED),
                "setAvailability 提交失败");
        require(canonical(registry).equals(before), "setAvailability 提交失败后快照变了");
        require(resource(registry, "R01").availability() == ResourceAvailability.ENABLED,
                "setAvailability 提交失败后可用性=" + resource(registry, "R01").availability()
                        + " expected=ENABLED");
        Registry restarted = new Registry(storage);
        require(canonical(restarted).equals(before),
                "setAvailability 提交失败后重启快照变了（restartedCanonicalSnapshot != OLD_STATE）");
        require(resource(restarted, "R01").availability() == ResourceAvailability.ENABLED,
                "setAvailability 提交失败后重启可用性=" + resource(restarted, "R01").availability()
                        + " expected=ENABLED");
        require(heldOf(restarted, "R01").isEmpty() && queueOf(restarted, "R01").isEmpty(),
                "setAvailability 提交失败后重启 holder/queue 变了");
    }

    // ================= R1-STORE-07 =================

    private static void r1Store07() {
        FailingStorage storage = new FailingStorage(new MemoryStorage());
        Registry registry = new Registry(storage);
        seedChain(registry);
        String before = canonical(registry);
        storage.failNextCommit();
        expectInjectedFailure(() -> registry.requestReservation("APP-001", "D01", "SP01", "R01", 0, 10),
                "request 提交失败");
        require(canonical(registry).equals(before), "request 提交失败后快照变了");
        require(reservations(registry).isEmpty(),
                "request 提交失败却出现预约幽灵状态：" + reservations(registry).size());
        Registry restarted = new Registry(storage);
        require(canonical(restarted).equals(before),
                "request 提交失败后重启快照变了（restartedCanonicalSnapshot != OLD_STATE）");
        require(reservations(restarted).isEmpty(),
                "request 提交失败后重启出现预约幽灵状态（ghostDecision/ghostEntity != NONE）");
        require(heldOf(restarted, "R01").isEmpty() && queueOf(restarted, "R01").isEmpty(),
                "request 提交失败后重启 holder/queue 变了");
    }

    // ================= R1-STORE-08 =================

    private static void r1Store08() {
        FailingStorage storage = new FailingStorage(new MemoryStorage());
        Registry registry = new Registry(storage);
        seedChain(registry);
        registry.requestReservation("APP-009", "D01", "SP01", "R01", 0, 10);
        registry.requestReservation("APP-001", "D01", "SP01", "R01", 10, 10);
        String before = canonical(registry);
        storage.failNextCommit();
        expectInjectedFailure(() -> registry.releaseReservation("APP-009"), "release 提交失败");
        require(canonical(registry).equals(before), "release 提交失败后快照变了");
        require(stateOf(registry, "APP-009") == ReservationState.HELD,
                "release 提交失败后 holder 状态=" + stateOf(registry, "APP-009") + " expected=HELD");
        require(heldOf(registry, "R01").equals(List.of("APP-009")),
                "release 提交失败后 HELD=" + heldOf(registry, "R01") + " expected=[APP-009]");
        require(queueOf(registry, "R01").equals(List.of("APP-001")),
                "release 提交失败后队列=" + queueOf(registry, "R01") + " expected=[APP-001]");
        Registry restarted = new Registry(storage);
        require(canonical(restarted).equals(before),
                "release 提交失败后重启快照变了（restartedCanonicalSnapshot != OLD_STATE）");
        require(stateOf(restarted, "APP-009") == ReservationState.HELD,
                "release 提交失败后重启 holder=" + stateOf(restarted, "APP-009") + " expected=HELD");
        require(heldOf(restarted, "R01").equals(List.of("APP-009"))
                        && queueOf(restarted, "R01").equals(List.of("APP-001")),
                "release 提交失败后重启 holder/queue 变了");
    }

    // ================= R1-STORE-09 =================

    private static void r1Store09() {
        FailingStorage storage = new FailingStorage(new MemoryStorage());
        Registry registry = new Registry(storage);
        seedChain(registry);
        registry.requestReservation("APP-009", "D01", "SP01", "R01", 0, 10);
        registry.requestReservation("APP-001", "D01", "SP01", "R01", 10, 10);
        String before = canonical(registry);
        storage.failNextCommit();
        expectInjectedFailure(() -> registry.cancelReservation("APP-001"), "cancel 提交失败");
        require(canonical(registry).equals(before), "cancel 提交失败后快照变了");
        require(stateOf(registry, "APP-001") == ReservationState.WAITING,
                "cancel 提交失败后状态=" + stateOf(registry, "APP-001") + " expected=WAITING");
        require(heldOf(registry, "R01").equals(List.of("APP-009")),
                "cancel 提交失败后 HELD=" + heldOf(registry, "R01") + " expected=[APP-009]");
        require(queueOf(registry, "R01").equals(List.of("APP-001")),
                "cancel 提交失败后队列=" + queueOf(registry, "R01") + " expected=[APP-001]");
        Registry restarted = new Registry(storage);
        require(canonical(restarted).equals(before),
                "cancel 提交失败后重启快照变了（restartedCanonicalSnapshot != OLD_STATE）");
        require(stateOf(restarted, "APP-001") == ReservationState.WAITING,
                "cancel 提交失败后重启状态=" + stateOf(restarted, "APP-001") + " expected=WAITING");
        require(heldOf(restarted, "R01").equals(List.of("APP-009"))
                        && queueOf(restarted, "R01").equals(List.of("APP-001")),
                "cancel 提交失败后重启 holder/queue 变了");
    }

    // ================= R1-STORE-10 =================

    private static void r1Store10() {
        FailingStorage storage = new FailingStorage(new MemoryStorage());
        Registry registry = new Registry(storage);
        seedChain(registry);
        registry.requestReservation("APP-009", "D01", "SP01", "R01", 0, 10);
        registry.releaseReservation("APP-009");
        registry.setVirtualResourceAvailability("R01", ResourceAvailability.DISABLED);
        registry.requestReservation("APP-001", "D01", "SP01", "R01", 50, 10);
        require(resource(registry, "R01").availability() == ResourceAvailability.DISABLED,
                "前置：R01 应 DISABLED");
        require(stateOf(registry, "APP-001") == ReservationState.WAITING, "前置：APP-001 应 WAITING");
        String before = canonical(registry);
        storage.failNextCommit();
        expectInjectedFailure(() -> registry.setVirtualResourceAvailability("R01", ResourceAvailability.ENABLED),
                "promotion 提交失败");
        require(canonical(registry).equals(before), "promotion 提交失败后快照变了");
        require(resource(registry, "R01").availability() == ResourceAvailability.DISABLED,
                "promotion 提交失败后可用性=" + resource(registry, "R01").availability() + " expected=DISABLED");
        require(stateOf(registry, "APP-001") == ReservationState.WAITING,
                "promotion 提交失败后 APP-001=" + stateOf(registry, "APP-001") + " expected=WAITING");
        Registry restarted = new Registry(storage);
        require(canonical(restarted).equals(before),
                "promotion 提交失败后重启快照变了（restartedCanonicalSnapshot != OLD_STATE）");
        require(resource(restarted, "R01").availability() == ResourceAvailability.DISABLED,
                "promotion 提交失败后重启可用性=" + resource(restarted, "R01").availability()
                        + " expected=DISABLED");
        require(stateOf(restarted, "APP-001") == ReservationState.WAITING,
                "promotion 提交失败后重启 APP-001=" + stateOf(restarted, "APP-001")
                        + " expected=WAITING");
        require(heldOf(restarted, "R01").isEmpty()
                        && queueOf(restarted, "R01").equals(List.of("APP-001")),
                "promotion 提交失败后重启 holder/queue 变了");
    }

    // ================= 夹具：Registry =================

    private static Registry registryWithChain() {
        Registry registry = new Registry(new MemoryStorage());
        seedChain(registry);
        return registry;
    }

    private static void seedChain(Registry registry) {
        registry.registerPoint("P01", "缓冲点");
        registry.registerPoint("P02", "接收点");
        for (int i = 1; i <= 9; i++) {
            registry.registerApplication(String.format("APP-%03d", i), "P01", "P02");
        }
        registry.addDestination("D01", "一号目的地");
        registry.addServicePoint("SP01", "D01", "P01");
        registry.addVirtualResource("R01", "SP01");
    }

    private static void addSecondTarget(Registry registry) {
        registry.addDestination("D02", "二号目的地");
        registry.addServicePoint("SP02", "D02", "P02");
        registry.addVirtualResource("R02", "SP02");
    }

    // ================= 快照读取 =================

    private static String canonical(Registry registry) {
        return registry.resourceWorldSnapshot().canonicalText();
    }

    private static List<Destination> destinations(Registry registry) {
        return registry.resourceWorldSnapshot().destinations();
    }

    private static List<ServicePoint> servicePoints(Registry registry) {
        return registry.resourceWorldSnapshot().servicePoints();
    }

    private static List<VirtualResource> resources(Registry registry) {
        return registry.resourceWorldSnapshot().resources();
    }

    private static List<Reservation> reservations(Registry registry) {
        return registry.resourceWorldSnapshot().reservations();
    }

    private static VirtualResource resource(Registry registry, String resourceId) {
        for (VirtualResource resource : resources(registry)) {
            if (resource.resourceId().equals(resourceId)) {
                return resource;
            }
        }
        throw new AssertionError("资源不存在：" + resourceId);
    }

    private static Reservation reservationOf(Registry registry, String applicationId) {
        for (Reservation reservation : reservations(registry)) {
            if (reservation.applicationId().equals(applicationId)) {
                return reservation;
            }
        }
        return null;
    }

    private static ReservationState stateOf(Registry registry, String applicationId) {
        Reservation reservation = reservationOf(registry, applicationId);
        return reservation == null ? null : reservation.state();
    }

    private static List<String> heldOf(Registry registry, String resourceId) {
        List<String> held = new ArrayList<>();
        for (Reservation reservation : reservations(registry)) {
            if (reservation.resourceId().equals(resourceId)
                    && reservation.state() == ReservationState.HELD) {
                held.add(reservation.applicationId());
            }
        }
        return List.copyOf(held);
    }

    private static List<String> queueOf(Registry registry, String resourceId) {
        List<String> waiting = new ArrayList<>();
        for (Reservation reservation : reservations(registry)) {
            if (reservation.resourceId().equals(resourceId)
                    && reservation.state() == ReservationState.WAITING) {
                waiting.add(reservation.applicationId());
            }
        }
        return List.copyOf(waiting);
    }

    // ================= 夹具：快照与 Reservation =================

    private static ResourceWorldSnapshot baselineWorld(List<Reservation> reservations) {
        return new ResourceWorldSnapshot(
                List.of(new Destination("D01", "一号目的地")),
                List.of(new ServicePoint("SP01", "D01", "P01")),
                List.of(new VirtualResource("R01", "SP01")),
                reservations);
    }

    private static ResourceWorldSnapshot orcSnapshot(List<Reservation> reservations) {
        return baselineWorld(reservations);
    }

    private static Reservation reservation(String appId, ReservationState state, long offset, long seq) {
        return new Reservation("RSV-" + appId, appId, "D01", "SP01", "R01", state, offset, seq);
    }

    private static Reservation reservation(String reservationId, String appId, String destinationId,
                                           String servicePointId, String resourceId, ReservationState state,
                                           long offset, long seq) {
        return new Reservation(reservationId, appId, destinationId, servicePointId, resourceId, state, offset, seq);
    }

    // ================= 夹具：load =================

    private static List<Point> baselinePoints() {
        return List.of(new Point("P01", "缓冲点"));
    }

    private static List<TransferApplication> baselineApps() {
        return List.of(new TransferApplication("APP-001", "P01", "P02"));
    }

    private static RegistryState loadState(List<Point> points, List<TransferApplication> apps,
                                           ResourceWorldSnapshot world) {
        return new RegistryState(points, apps, List.of(), List.of(), List.of(), world);
    }

    /**
     * 注入非法快照：Storage.loadState() 原样返回该状态（绕过 commit 校验），
     * 让失败发生在 Registry 构造 / load 校验阶段，而不是测试夹具的 commit 阶段。
     */
    private static Throwable loadFailure(RegistryState state) {
        Storage stub = new Storage() {
            @Override
            public RegistryState loadState() {
                return state;
            }

            @Override
            public void commitState(RegistryState next) {
                throw new AssertionError("非法快照不应进入 commitState");
            }
        };
        return capture(() -> new Registry(stub));
    }

    private static void expectLoadFailure(RegistryState state, String label) {
        Throwable failure = loadFailure(state);
        require(failure != null,
                label + " 未导致 load/Registry 构造失败 expected=失败 actual=成功加载");
        require(failure instanceof RegistryException,
                label + "：异常类型=" + failure.getClass().getSimpleName()
                        + " expected=RegistryException");
        RegistryException exception = (RegistryException) failure;
        require(exception.code().equals("RESOURCE_STATE_INVALID"),
                label + "：code=" + exception.code() + " expected=RESOURCE_STATE_INVALID");
        require(exception.detail().contains("rule=")
                        && exception.detail().contains(";entity=")
                        && exception.detail().contains(";field=")
                        && exception.detail().contains(";actual="),
                label + "：detail 形态=" + exception.detail());
    }

    // ================= 夹具：Oracle =================

    private static OracleResult orcResult(ResourceWorldSnapshot world, List<ResourceStep> steps,
                                          List<RestartPair> pairs, String oracleId) {
        for (OracleResult result : ResourceInvariantOracle.check(world, steps, pairs)) {
            if (result.oracleId().equals(oracleId)) {
                return result;
            }
        }
        throw new AssertionError("缺少 Oracle 结果：" + oracleId);
    }

    private static OracleResult.Status orcStatus(ResourceWorldSnapshot world, List<ResourceStep> steps,
                                                 List<RestartPair> pairs, String oracleId) {
        return orcResult(world, steps, pairs, oracleId).status();
    }

    // ================= 反射辅助 =================

    private static ReservationDecision.DecisionType requireDecisionType(String name) {
        try {
            return ReservationDecision.DecisionType.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new AssertionError("缺少 DecisionType 常量 " + name);
        }
    }

    private static boolean actionExists(String name) {
        try {
            ScenarioEvent.Action.valueOf(name);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean ruleTypeExists(String name) {
        try {
            OracleRule.RuleType.valueOf(name);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean declaresOpsMethods(Class<?> type) {
        boolean writeAndSync = false;
        boolean atomicMove = false;
        for (java.lang.reflect.Method method : type.getMethods()) {
            if (method.getName().equals("writeAndSync")) {
                writeAndSync = true;
            }
            if (method.getName().equals("atomicMove")) {
                atomicMove = true;
            }
        }
        return writeAndSync && atomicMove;
    }

    private static boolean hasNestedType(Class<?> clazz, String simpleName) {
        for (Class<?> nested : clazz.getDeclaredClasses()) {
            if (nested.getSimpleName().equals(simpleName)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasDeclaredMethod(Class<?> clazz, String name) {
        try {
            clazz.getDeclaredMethod(name);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    // ================= DSL 解析辅助 =================

    private static Path write(String fileName, String content) {
        try {
            Path file = tmpDir.resolve(fileName);
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return file;
        } catch (Exception e) {
            throw new IllegalStateException("无法写临时场景 " + fileName, e);
        }
    }

    private static boolean parseOk(Path file) {
        try {
            ScenarioParser.parse(file);
            return true;
        } catch (ScenarioValidationException e) {
            return false;
        }
    }

    private static void expectErr(Path file, String code, String detail, String label) {
        try {
            ScenarioParser.parse(file);
        } catch (ScenarioValidationException e) {
            require(e.code().equals(code), label + "：code=" + e.code() + " expected=" + code);
            require(e.detail().equals(detail), label + "：detail=" + e.detail() + " expected=" + detail);
            return;
        }
        throw new AssertionError(label + "：未抛异常 expected=" + code + " actual=解析成功");
    }

    private static void expectAnyErr(Path file, String label) {
        try {
            ScenarioParser.parse(file);
        } catch (ScenarioValidationException e) {
            return;
        }
        throw new AssertionError(label + "：未抛异常 expected=ScenarioValidationException actual=解析成功");
    }

    // ================= 断言基础设施 =================

    private interface CaseBody {
        void run() throws Exception;
    }

    private interface Action {
        void run();
    }

    private interface CheckedAction {
        void run() throws Exception;
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
        String message = failure.getMessage();
        return failure.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static Throwable capture(Action action) {
        try {
            action.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private static Throwable captureChecked(CheckedAction action) {
        try {
            action.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private static void expectRegistryFailure(Action action, String code, String label) {
        Throwable failure = capture(action);
        require(failure != null, label + "：未抛异常 expected=" + code + " actual=无异常");
        require(failure instanceof RegistryException,
                label + "：异常类型=" + failure.getClass().getSimpleName() + " expected=RegistryException");
        RegistryException exception = (RegistryException) failure;
        require(exception.code().equals(code), label + "：code=" + exception.code() + " expected=" + code);
    }

    private static void expectInjectedFailure(Action action, String label) {
        Throwable failure = capture(action);
        require(failure != null, label + "：未抛异常 expected=INJECTED_COMMIT_FAILURE actual=无异常");
        require("INJECTED_COMMIT_FAILURE".equals(failure.getMessage()),
                label + "：异常=" + failure + " expected=INJECTED_COMMIT_FAILURE");
    }

    /** 注入文件操作失败后，提交必须抛出异常（operationResult=FAIL）。 */
    private static void expectCommitFailure(Action action, String label) {
        Throwable failure = capture(action);
        require(failure != null, label + "：未抛异常 expected=FAIL actual=无异常");
        require(failure.getMessage() != null && failure.getMessage().contains("cannot commit state"),
                label + "：异常=" + failure + " expected=cannot commit state");
    }

    private static boolean hasStagingFile(Path dir) throws IOException {
        try (var stream = Files.list(dir)) {
            return stream.anyMatch(path -> path.getFileName().toString().endsWith(".staging"));
        }
    }

    /**
     * 可注入文件操作（§11.4）：下一次 write 或 atomicMove 抛 IOException。
     * 生产语义（写全量 + force、ATOMIC_MOVE + REPLACE_EXISTING）在测试内重新实现，
     * 真实文件权限不参与故障注入。
     */
    private static final class FailingStateFileOps implements StateFileOps {

        private boolean failNextWrite;
        private boolean failNextAtomicMove;

        void failNextWrite() {
            this.failNextWrite = true;
        }

        void failNextAtomicMove() {
            this.failNextAtomicMove = true;
        }

        @Override
        public void writeAndSync(Path staging, byte[] bytes) throws IOException {
            if (failNextWrite) {
                failNextWrite = false;
                throw new IOException("INJECTED_WRITE_FAILURE");
            }
            try (FileChannel channel = FileChannel.open(staging,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
        }

        @Override
        public void atomicMove(Path staging, Path target) throws IOException {
            if (failNextAtomicMove) {
                failNextAtomicMove = false;
                throw new IOException("INJECTED_ATOMIC_MOVE_FAILURE");
            }
            Files.move(staging, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 包装 Storage，按需让下一次 commitState 失败。 */
    private static final class FailingStorage implements Storage {

        private final Storage delegate;
        private boolean failNext;

        FailingStorage(Storage delegate) {
            this.delegate = delegate;
        }

        void failNextCommit() {
            this.failNext = true;
        }

        @Override
        public RegistryState loadState() {
            return delegate.loadState();
        }

        @Override
        public void commitState(RegistryState next) {
            if (failNext) {
                failNext = false;
                throw new RuntimeException("INJECTED_COMMIT_FAILURE");
            }
            delegate.commitState(next);
        }
    }

    private static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // 清理失败不影响结果
                }
            });
        } catch (Exception ignored) {
            // 清理失败不影响结果
        }
    }
}
