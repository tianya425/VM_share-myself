package lab.simulation.resource;

import lab.FileStorage;
import lab.MemoryStorage;
import lab.Registry;
import lab.RegistryException;
import lab.RegistryState;
import lab.Storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 资源领域（Part B / PR4）的 24 项具名检查：RW-01 — RW-24。
 *
 * <p>每个编号一个独立方法 {@code rw01()} … {@code rw24()}；只有该编号的全部断言都通过，
 * 才打印 {@code RW-XX PASS} 并让 passed 加一。失败时打印 {@code RW-XX FAIL} 与 INTENT，
 * 全部跑完后只要有失败就以退出码 1 结束；24 项全过时最后一行是
 * {@code RESOURCE_WORLD_CASES_PASSED=24} 且退出码为 0。
 *
 * <p>本类只使用 {@link Registry} 的资源公开方法与 {@link ResourceWorldSnapshot}
 * 的公开访问器，不读取任何私有状态，也不直接改快照。
 */
public final class ResourceWorldChecks {

    private static final int CASE_TOTAL = 24;

    private static int passed = 0;
    private static int failed = 0;
    private static Path tmpDir;

    private static final String INTENT_01 =
            "Destination 同 ID 同 name 重复新增幂等：不报错、条目数不增、名称不变";
    private static final String INTENT_02 =
            "Destination 同 ID 不同 name 必须 RESOURCE_DUPLICATE_CONFLICT，且原条目与快照不变";
    private static final String INTENT_03 =
            "ServicePoint 正常新增：引用已存在的 Destination 与 Registry Point，规范快照可读";
    private static final String INTENT_04 =
            "ServicePoint 缺 Destination → RESOURCE_DESTINATION_NOT_FOUND，且无幽灵条目";
    private static final String INTENT_05 =
            "ServicePoint 缺 Registry Point → UNKNOWN_POINT，且无幽灵条目";
    private static final String INTENT_06 =
            "VirtualResource 默认 ENABLED：新增即启用，可立刻被请求 HELD";
    private static final String INTENT_07 =
            "VirtualResource 缺 ServicePoint → RESOURCE_SERVICE_POINT_NOT_FOUND；同 ID 不同引用 → RESOURCE_DUPLICATE_CONFLICT";
    private static final String INTENT_08 =
            "request 的 application 必须存在 → RESERVATION_APPLICATION_NOT_FOUND";
    private static final String INTENT_09 =
            "request 的 application 必须是 CREATED → RESERVATION_APPLICATION_STATE";
    private static final String INTENT_10 =
            "Destination→ServicePoint→Resource 引用链必须一致，否则 RESOURCE_TARGET_MISMATCH";
    private static final String INTENT_11 =
            "空闲且 ENABLED 的资源：请求立刻 HELD，决策 type=HELD";
    private static final String INTENT_12 =
            "已被占用的资源：第二个请求进 WAITING，决策 type=WAITING";
    private static final String INTENT_13 =
            "队列按 requestedOffsetMillis → requestedLogicalSequence → applicationId 稳定排序（与请求先后无关）";
    private static final String INTENT_14 =
            "完全相同重复 request → IDEMPOTENT，条目不增、原排序键不变";
    private static final String INTENT_15 =
            "同申请不同目标 → CONFLICT 决策（不抛异常），状态与快照不变";
    private static final String INTENT_16 =
            "release 后晋升队首：释放者 RELEASED，队列最小键变 HELD";
    private static final String INTENT_17 =
            "cancel WAITING 移出队列：变 CANCELLED，holder 不受影响";
    private static final String INTENT_18 =
            "cancel HELD 后晋升：变 CANCELLED，队首补位 HELD";
    private static final String INTENT_19 =
            "DISABLED 资源的请求进 WAITING（不占容量）";
    private static final String INTENT_20 =
            "disable 不驱逐 holder：已 HELD 的保持不变";
    private static final String INTENT_21 =
            "enable 且无 holder 时晋升队首 WAITING→HELD";
    private static final String INTENT_22 =
            "RELEASED / CANCELLED 不可重开：request/release/cancel 一律 RESERVATION_TERMINAL";
    private static final String INTENT_23 =
            "WORLD_RESTART 前后规范快照一致，后续晋升结果一致（FileStorage 真正落盘重读）";
    private static final String INTENT_24 =
            "request / release / cancel / promotion 提交失败均无幽灵状态（内存与已提交一致）";

    private ResourceWorldChecks() {
    }

    public static void main(String[] args) {
        try {
            try {
                tmpDir = Files.createTempDirectory("resource-world-checks-");
            } catch (Exception e) {
                throw new IllegalStateException("无法创建临时目录", e);
            }
            run("RW-01", INTENT_01, ResourceWorldChecks::rw01);
            run("RW-02", INTENT_02, ResourceWorldChecks::rw02);
            run("RW-03", INTENT_03, ResourceWorldChecks::rw03);
            run("RW-04", INTENT_04, ResourceWorldChecks::rw04);
            run("RW-05", INTENT_05, ResourceWorldChecks::rw05);
            run("RW-06", INTENT_06, ResourceWorldChecks::rw06);
            run("RW-07", INTENT_07, ResourceWorldChecks::rw07);
            run("RW-08", INTENT_08, ResourceWorldChecks::rw08);
            run("RW-09", INTENT_09, ResourceWorldChecks::rw09);
            run("RW-10", INTENT_10, ResourceWorldChecks::rw10);
            run("RW-11", INTENT_11, ResourceWorldChecks::rw11);
            run("RW-12", INTENT_12, ResourceWorldChecks::rw12);
            run("RW-13", INTENT_13, ResourceWorldChecks::rw13);
            run("RW-14", INTENT_14, ResourceWorldChecks::rw14);
            run("RW-15", INTENT_15, ResourceWorldChecks::rw15);
            run("RW-16", INTENT_16, ResourceWorldChecks::rw16);
            run("RW-17", INTENT_17, ResourceWorldChecks::rw17);
            run("RW-18", INTENT_18, ResourceWorldChecks::rw18);
            run("RW-19", INTENT_19, ResourceWorldChecks::rw19);
            run("RW-20", INTENT_20, ResourceWorldChecks::rw20);
            run("RW-21", INTENT_21, ResourceWorldChecks::rw21);
            run("RW-22", INTENT_22, ResourceWorldChecks::rw22);
            run("RW-23", INTENT_23, ResourceWorldChecks::rw23);
            run("RW-24", INTENT_24, ResourceWorldChecks::rw24);
        } finally {
            deleteRecursively(tmpDir);
        }

        if (passed + failed != CASE_TOTAL) {
            throw new AssertionError("case 数量不符：passed+failed=" + (passed + failed)
                    + " 期望=" + CASE_TOTAL);
        }
        if (failed > 0) {
            System.out.println("RESOURCE_WORLD_CASES_FAILED=" + failed);
            System.out.println("RESOURCE_WORLD_CASES_PASSED=" + passed);
            System.exit(1);
        }
        System.out.println("RESOURCE_WORLD_CASES_PASSED=" + passed);
    }

    // ================= RW-01 =================

    /** Destination 新增幂等。 */
    private static void rw01() {
        Registry registry = registryWithChain();

        Destination first = registry.addDestination("D02", "二号目的地");
        Destination second = registry.addDestination("D02", "二号目的地");

        require(first.equals(second), "同 ID 同 name 第二次新增返回的 Destination 不一致："
                + first + " vs " + second);
        require(destinations(registry).size() == 2,
                "Destination 条目数=" + destinations(registry).size() + " 期望=2");
        require(destinations(registry).get(1).name().equals("二号目的地"),
                "D02 名称=" + destinations(registry).get(1).name());
    }

    // ================= RW-02 =================

    /** 同 ID 不同 name 冲突。 */
    private static void rw02() {
        Registry registry = registryWithChain();
        String before = canonical(registry);

        expectRegistryFailure(() -> registry.addDestination("D01", "改过的名字"),
                "RESOURCE_DUPLICATE_CONFLICT", "D01", "同 ID 不同 name");

        require(canonical(registry).equals(before),
                "冲突后规范快照变了：\nbefore=" + before + "\nafter=" + canonical(registry));
        require(destinations(registry).size() == 1,
                "Destination 条目数=" + destinations(registry).size() + " 期望=1");
        require(destinations(registry).get(0).name().equals("一号目的地"),
                "D01 名称被改成了 " + destinations(registry).get(0).name());
    }

    // ================= RW-03 =================

    /** ServicePoint 正常新增。 */
    private static void rw03() {
        Registry registry = registryWithChain();

        ServicePoint added = registry.addServicePoint("SP02", "D01", "P02");

        require(added.servicePointId().equals("SP02"), "servicePointId=" + added.servicePointId());
        require(added.destinationId().equals("D01"), "destinationId=" + added.destinationId());
        require(added.pointId().equals("P02"), "pointId=" + added.pointId());
        require(servicePoints(registry).size() == 2,
                "ServicePoint 条目数=" + servicePoints(registry).size() + " 期望=2");
        require(canonical(registry).contains("S|SP02|D01|P02"),
                "规范快照缺少 SP02 行：\n" + canonical(registry));
    }

    // ================= RW-04 =================

    /** 缺 Destination 被拒。 */
    private static void rw04() {
        Registry registry = registryWithChain();
        String before = canonical(registry);

        expectRegistryFailure(() -> registry.addServicePoint("SP09", "D99", "P01"),
                "RESOURCE_DESTINATION_NOT_FOUND", "D99", "缺 Destination");

        require(canonical(registry).equals(before), "拒绝后出现了幽灵 ServicePoint：\n" + canonical(registry));
        require(servicePoints(registry).size() == 1,
                "ServicePoint 条目数=" + servicePoints(registry).size() + " 期望=1");
    }

    // ================= RW-05 =================

    /** 缺 Registry Point 被拒。 */
    private static void rw05() {
        Registry registry = registryWithChain();
        String before = canonical(registry);

        expectRegistryFailure(() -> registry.addServicePoint("SP09", "D01", "P99"),
                "UNKNOWN_POINT", "P99", "缺 Registry Point");

        require(canonical(registry).equals(before), "拒绝后出现了幽灵 ServicePoint：\n" + canonical(registry));
        require(servicePoints(registry).size() == 1,
                "ServicePoint 条目数=" + servicePoints(registry).size() + " 期望=1");
    }

    // ================= RW-06 =================

    /** VirtualResource 默认 ENABLED。 */
    private static void rw06() {
        Registry registry = registryWithChain();

        VirtualResource added = registry.addVirtualResource("R02", "SP01");

        require(added.availability() == ResourceAvailability.ENABLED,
                "新增资源可用性=" + added.availability() + " 期望=ENABLED");
        require(resource(registry, "R02").availability() == ResourceAvailability.ENABLED,
                "快照中 R02 可用性=" + resource(registry, "R02").availability());
        ReservationDecision decision = registry.requestReservation("APP-001", "D01", "SP01", "R02", 0, 10);
        require(decision.type() == ReservationDecision.DecisionType.HELD,
                "对默认 ENABLED 资源的请求 type=" + decision.type() + " 期望=HELD");
    }

    // ================= RW-07 =================

    /** 缺 ServicePoint 或重复冲突。 */
    private static void rw07() {
        Registry registry = registryWithChain();
        registry.addDestination("D02", "二号目的地");
        registry.addServicePoint("SP02", "D02", "P02");
        String before = canonical(registry);

        expectRegistryFailure(() -> registry.addVirtualResource("R09", "SP99"),
                "RESOURCE_SERVICE_POINT_NOT_FOUND", "SP99", "缺 ServicePoint");

        expectRegistryFailure(() -> registry.addVirtualResource("R01", "SP02"),
                "RESOURCE_DUPLICATE_CONFLICT", "R01", "同 ID 不同 ServicePoint");

        require(canonical(registry).equals(before), "拒绝后出现了幽灵资源：\n" + canonical(registry));
        require(virtualResources(registry).size() == 1,
                "VirtualResource 条目数=" + virtualResources(registry).size() + " 期望=1");
    }

    // ================= RW-08 =================

    /** application 必须存在。 */
    private static void rw08() {
        Registry registry = registryWithChain();
        String before = canonical(registry);

        expectRegistryFailure(
                () -> registry.requestReservation("APP-404", "D01", "SP01", "R01", 0, 10),
                "RESERVATION_APPLICATION_NOT_FOUND", "APP-404", "不存在的申请");

        require(canonical(registry).equals(before), "失败请求留下了幽灵预约：\n" + canonical(registry));
        require(reservations(registry).isEmpty(),
                "预约条数=" + reservations(registry).size() + " 期望=0");
    }

    // ================= RW-09 =================

    /** application 必须是 CREATED。 */
    private static void rw09() {
        Registry registry = registryWithChain();
        registry.dispatchApplication("APP-001");
        require(registry.findApplication("APP-001").orElseThrow().state() == lab.ApplicationState.DISPATCHED,
                "前置：APP-001 状态应为 DISPATCHED");
        String before = canonical(registry);

        expectRegistryFailure(
                () -> registry.requestReservation("APP-001", "D01", "SP01", "R01", 0, 10),
                "RESERVATION_APPLICATION_STATE", "APP-001", "非 CREATED 申请");

        require(canonical(registry).equals(before), "失败请求留下了幽灵预约：\n" + canonical(registry));
        require(reservations(registry).isEmpty(),
                "预约条数=" + reservations(registry).size() + " 期望=0");
    }

    // ================= RW-10 =================

    /** 引用链一致。 */
    private static void rw10() {
        Registry registry = registryWithChain();
        registry.addDestination("D02", "二号目的地");
        registry.addServicePoint("SP02", "D02", "P02");
        registry.addVirtualResource("R02", "SP02");
        String before = canonical(registry);

        expectRegistryFailure(
                () -> registry.requestReservation("APP-001", "D01", "SP01", "R02", 0, 10),
                "RESOURCE_TARGET_MISMATCH", "APP-001", "资源挂在 SP02 却按 SP01 请求");
        expectRegistryFailure(
                () -> registry.requestReservation("APP-001", "D02", "SP01", "R01", 0, 10),
                "RESOURCE_TARGET_MISMATCH", "APP-001", "SP01 属于 D01 却按 D02 请求");
        expectRegistryFailure(
                () -> registry.requestReservation("APP-001", "D01", "SP01", "R99", 0, 10),
                "RESOURCE_NOT_FOUND", "R99", "不存在的资源");

        require(canonical(registry).equals(before), "失败请求留下了幽灵预约：\n" + canonical(registry));
    }

    // ================= RW-11 =================

    /** 空闲 ENABLED → HELD。 */
    private static void rw11() {
        Registry registry = registryWithChain();

        ReservationDecision decision = registry.requestReservation("APP-001", "D01", "SP01", "R01", 0, 10);

        require(decision.type() == ReservationDecision.DecisionType.HELD,
                "type=" + decision.type() + " 期望=HELD");
        require(decision.reservation().state() == ReservationState.HELD,
                "决策中的预约状态=" + decision.reservation().state() + " 期望=HELD");
        require(decision.reservation().reservationId().equals("RSV-APP-001"),
                "reservationId=" + decision.reservation().reservationId() + " 期望=RSV-APP-001");
        require(stateOf(registry, "APP-001") == ReservationState.HELD,
                "快照中状态=" + stateOf(registry, "APP-001") + " 期望=HELD");
        require(heldOf(registry, "R01").equals(List.of("APP-001")),
                "HELD 列表=" + heldOf(registry, "R01") + " 期望=[APP-001]");
        require(queueOf(registry, "R01").isEmpty(),
                "WAITING 队列=" + queueOf(registry, "R01") + " 期望=[]");
    }

    // ================= RW-12 =================

    /** 已占用 → WAITING。 */
    private static void rw12() {
        Registry registry = registryWithChain();
        registry.requestReservation("APP-001", "D01", "SP01", "R01", 0, 10);

        ReservationDecision decision = registry.requestReservation("APP-002", "D01", "SP01", "R01", 10, 10);

        require(decision.type() == ReservationDecision.DecisionType.WAITING,
                "type=" + decision.type() + " 期望=WAITING");
        require(stateOf(registry, "APP-002") == ReservationState.WAITING,
                "快照中状态=" + stateOf(registry, "APP-002") + " 期望=WAITING");
        require(heldOf(registry, "R01").equals(List.of("APP-001")),
                "HELD 列表=" + heldOf(registry, "R01") + " 期望=[APP-001]");
        require(queueOf(registry, "R01").equals(List.of("APP-002")),
                "WAITING 队列=" + queueOf(registry, "R01") + " 期望=[APP-002]");
    }

    // ================= RW-13 =================

    /** 队列稳定排序。 */
    private static void rw13() {
        Registry registry = registryWithChain();
        registry.requestReservation("APP-009", "D01", "SP01", "R01", 500, 10);

        registry.requestReservation("APP-003", "D01", "SP01", "R01", 500, 30);
        registry.requestReservation("APP-002", "D01", "SP01", "R01", 500, 20);
        registry.requestReservation("APP-001", "D01", "SP01", "R01", 500, 30);
        registry.requestReservation("APP-004", "D01", "SP01", "R01", 400, 99);

        require(queueOf(registry, "R01").equals(List.of("APP-004", "APP-002", "APP-001", "APP-003")),
                "WAITING 队列=" + queueOf(registry, "R01")
                        + " 期望=[APP-004, APP-002, APP-001, APP-003]"
                        + "（offset 优先，其次 logicalSequence，最后 applicationId）");
        require(heldOf(registry, "R01").equals(List.of("APP-009")),
                "HELD 列表=" + heldOf(registry, "R01") + " 期望=[APP-009]");
    }

    // ================= RW-14 =================

    /** 重复 request 幂等，排序键不变。 */
    private static void rw14() {
        Registry registry = registryWithChain();
        registry.requestReservation("APP-009", "D01", "SP01", "R01", 0, 10);
        registry.requestReservation("APP-001", "D01", "SP01", "R01", 100, 20);
        int before = reservations(registry).size();

        ReservationDecision exact = registry.requestReservation("APP-001", "D01", "SP01", "R01", 100, 20);
        require(exact.type() == ReservationDecision.DecisionType.IDEMPOTENT,
                "完全相同重复 request type=" + exact.type() + " 期望=IDEMPOTENT");

        ReservationDecision later = registry.requestReservation("APP-001", "D01", "SP01", "R01", 200, 30);
        require(later.type() == ReservationDecision.DecisionType.IDEMPOTENT,
                "同目标后到 request type=" + later.type() + " 期望=IDEMPOTENT");

        require(reservations(registry).size() == before,
                "预约条数=" + reservations(registry).size() + " 期望=" + before);
        Reservation kept = reservationOf(registry, "APP-001");
        require(kept.requestedOffsetMillis() == 100L && kept.requestedLogicalSequence() == 20L,
                "排序键被改了：offset=" + kept.requestedOffsetMillis() + " seq=" + kept.requestedLogicalSequence()
                        + " 期望=100/20");
        require(kept.state() == ReservationState.WAITING, "状态=" + kept.state() + " 期望=WAITING");
        require(queueOf(registry, "R01").equals(List.of("APP-001")),
                "WAITING 队列=" + queueOf(registry, "R01") + " 期望=[APP-001]");
    }

    // ================= RW-15 =================

    /** 同申请不同目标 → CONFLICT 决策（不抛异常），快照与状态不变。 */
    private static void rw15() {
        Registry registry = registryWithChain();
        registry.addDestination("D02", "二号目的地");
        registry.addServicePoint("SP02", "D02", "P02");
        registry.addVirtualResource("R02", "SP02");
        registry.requestReservation("APP-009", "D01", "SP01", "R01", 0, 10);
        registry.requestReservation("APP-001", "D01", "SP01", "R01", 10, 10);
        String before = canonical(registry);

        ReservationDecision[] decision = new ReservationDecision[1];
        Throwable failure = capture(
                () -> decision[0] = registry.requestReservation("APP-001", "D02", "SP02", "R02", 20, 10));
        require(failure == null, "同申请换目标抛异常 expected=无异常(CONFLICT) actual=" + failure);
        require(decision[0].type() == ReservationDecision.DecisionType.CONFLICT,
                "type=" + decision[0].type() + " 期望=CONFLICT");
        require(decision[0].reservation().applicationId().equals("APP-001"),
                "CONFLICT 决策应携带原有预约 applicationId=" + decision[0].reservation().applicationId());
        require("reason=DIFFERENT_ACTIVE_TARGET".equals(decision[0].detail()),
                "CONFLICT detail=" + decision[0].detail() + " 期望=reason=DIFFERENT_ACTIVE_TARGET");

        require(canonical(registry).equals(before), "冲突后快照变了：\nbefore=" + before
                + "\nafter=" + canonical(registry));
        Reservation kept = reservationOf(registry, "APP-001");
        require(kept.state() == ReservationState.WAITING, "状态=" + kept.state() + " 期望=WAITING");
        require(kept.resourceId().equals("R01"), "resourceId=" + kept.resourceId() + " 期望=R01");
    }

    // ================= RW-16 =================

    /** release 后晋升队首。 */
    private static void rw16() {
        Registry registry = registryWithChain();
        registry.requestReservation("APP-009", "D01", "SP01", "R01", 0, 10);
        registry.requestReservation("APP-002", "D01", "SP01", "R01", 10, 10);
        registry.requestReservation("APP-001", "D01", "SP01", "R01", 20, 10);

        Reservation released = registry.releaseReservation("APP-009");

        require(released.state() == ReservationState.RELEASED,
                "释放结果状态=" + released.state() + " 期望=RELEASED");
        require(stateOf(registry, "APP-009") == ReservationState.RELEASED,
                "快照中 APP-009 状态=" + stateOf(registry, "APP-009"));
        require(heldOf(registry, "R01").equals(List.of("APP-002")),
                "HELD 列表=" + heldOf(registry, "R01") + " 期望=[APP-002]（队首晋升）");
        require(queueOf(registry, "R01").equals(List.of("APP-001")),
                "WAITING 队列=" + queueOf(registry, "R01") + " 期望=[APP-001]");
    }

    // ================= RW-17 =================

    /** cancel WAITING 移出队列。 */
    private static void rw17() {
        Registry registry = registryWithChain();
        registry.requestReservation("APP-009", "D01", "SP01", "R01", 0, 10);
        registry.requestReservation("APP-001", "D01", "SP01", "R01", 10, 10);
        registry.requestReservation("APP-002", "D01", "SP01", "R01", 20, 10);

        Reservation cancelled = registry.cancelReservation("APP-001");

        require(cancelled.state() == ReservationState.CANCELLED,
                "取消结果状态=" + cancelled.state() + " 期望=CANCELLED");
        require(stateOf(registry, "APP-001") == ReservationState.CANCELLED,
                "快照中 APP-001 状态=" + stateOf(registry, "APP-001"));
        require(queueOf(registry, "R01").equals(List.of("APP-002")),
                "WAITING 队列=" + queueOf(registry, "R01") + " 期望=[APP-002]（APP-001 已移出）");
        require(heldOf(registry, "R01").equals(List.of("APP-009")),
                "HELD 列表=" + heldOf(registry, "R01") + " 期望=[APP-009]（holder 不受影响）");
    }

    // ================= RW-18 =================

    /** cancel HELD 后晋升。 */
    private static void rw18() {
        Registry registry = registryWithChain();
        registry.requestReservation("APP-009", "D01", "SP01", "R01", 0, 10);
        registry.requestReservation("APP-001", "D01", "SP01", "R01", 10, 10);
        registry.requestReservation("APP-002", "D01", "SP01", "R01", 20, 10);

        Reservation cancelled = registry.cancelReservation("APP-009");

        require(cancelled.state() == ReservationState.CANCELLED,
                "取消结果状态=" + cancelled.state() + " 期望=CANCELLED");
        require(heldOf(registry, "R01").equals(List.of("APP-001")),
                "HELD 列表=" + heldOf(registry, "R01") + " 期望=[APP-001]（队首补位）");
        require(queueOf(registry, "R01").equals(List.of("APP-002")),
                "WAITING 队列=" + queueOf(registry, "R01") + " 期望=[APP-002]");
    }

    // ================= RW-19 =================

    /** DISABLED 请求进 WAITING。 */
    private static void rw19() {
        Registry registry = registryWithChain();
        registry.setVirtualResourceAvailability("R01", ResourceAvailability.DISABLED);

        ReservationDecision decision = registry.requestReservation("APP-001", "D01", "SP01", "R01", 0, 10);

        require(decision.type() == ReservationDecision.DecisionType.WAITING,
                "type=" + decision.type() + " 期望=WAITING（资源 DISABLED）");
        require(stateOf(registry, "APP-001") == ReservationState.WAITING,
                "状态=" + stateOf(registry, "APP-001") + " 期望=WAITING");
        require(heldOf(registry, "R01").isEmpty(), "HELD 列表=" + heldOf(registry, "R01") + " 期望=[]");
        require(queueOf(registry, "R01").equals(List.of("APP-001")),
                "WAITING 队列=" + queueOf(registry, "R01") + " 期望=[APP-001]");
    }

    // ================= RW-20 =================

    /** disable 不驱逐 holder。 */
    private static void rw20() {
        Registry registry = registryWithChain();
        registry.requestReservation("APP-009", "D01", "SP01", "R01", 0, 10);

        VirtualResource disabled = registry.setVirtualResourceAvailability("R01", ResourceAvailability.DISABLED);

        require(disabled.availability() == ResourceAvailability.DISABLED,
                "资源可用性=" + disabled.availability() + " 期望=DISABLED");
        require(heldOf(registry, "R01").equals(List.of("APP-009")),
                "disable 后 HELD 列表=" + heldOf(registry, "R01") + " 期望=[APP-009]（不得驱逐）");

        ReservationDecision decision = registry.requestReservation("APP-001", "D01", "SP01", "R01", 10, 10);
        require(decision.type() == ReservationDecision.DecisionType.WAITING,
                "disable 后新请求 type=" + decision.type() + " 期望=WAITING");
    }

    // ================= RW-21 =================

    /** enable 且无 holder 时晋升队首。 */
    private static void rw21() {
        Registry registry = registryWithChain();
        registry.setVirtualResourceAvailability("R01", ResourceAvailability.DISABLED);
        registry.requestReservation("APP-001", "D01", "SP01", "R01", 0, 10);
        registry.requestReservation("APP-002", "D01", "SP01", "R01", 10, 10);

        VirtualResource enabled = registry.setVirtualResourceAvailability("R01", ResourceAvailability.ENABLED);

        require(enabled.availability() == ResourceAvailability.ENABLED,
                "资源可用性=" + enabled.availability() + " 期望=ENABLED");
        require(heldOf(registry, "R01").equals(List.of("APP-001")),
                "enable 后 HELD 列表=" + heldOf(registry, "R01") + " 期望=[APP-001]（队首晋升）");
        require(queueOf(registry, "R01").equals(List.of("APP-002")),
                "WAITING 队列=" + queueOf(registry, "R01") + " 期望=[APP-002]");
    }

    // ================= RW-22 =================

    /** 终态不可重开。 */
    private static void rw22() {
        Registry registry = registryWithChain();
        registry.requestReservation("APP-009", "D01", "SP01", "R01", 0, 10);
        registry.requestReservation("APP-001", "D01", "SP01", "R01", 10, 10);

        registry.releaseReservation("APP-009");
        require(stateOf(registry, "APP-009") == ReservationState.RELEASED, "前置：APP-009 应已 RELEASED");
        String afterRelease = canonical(registry);

        expectRegistryFailure(
                () -> registry.requestReservation("APP-009", "D01", "SP01", "R01", 20, 10),
                "RESERVATION_TERMINAL", "APP-009", "RELEASED 后重开");
        expectRegistryFailure(() -> registry.releaseReservation("APP-009"),
                "RESERVATION_TERMINAL", "APP-009", "RELEASED 后再 release");
        require(canonical(registry).equals(afterRelease), "终止后失败操作改了快照：\n" + canonical(registry));

        registry.cancelReservation("APP-001");
        require(stateOf(registry, "APP-001") == ReservationState.CANCELLED, "前置：APP-001 应已 CANCELLED");
        String afterCancel = canonical(registry);

        expectRegistryFailure(
                () -> registry.requestReservation("APP-001", "D01", "SP01", "R01", 30, 10),
                "RESERVATION_TERMINAL", "APP-001", "CANCELLED 后重开");
        expectRegistryFailure(() -> registry.cancelReservation("APP-001"),
                "RESERVATION_TERMINAL", "APP-001", "CANCELLED 后再 cancel");
        require(canonical(registry).equals(afterCancel), "终止后失败操作改了快照：\n" + canonical(registry));
    }

    // ================= RW-23 =================

    /** WORLD_RESTART 前后规范快照一致，后续晋升一致。 */
    private static void rw23() {
        Path dir = tmpDir.resolve("rw23-file");
        Registry first = new Registry(new FileStorage(dir));
        seedAll(first);
        first.requestReservation("APP-009", "D01", "SP01", "R01", 0, 10);
        first.requestReservation("APP-001", "D01", "SP01", "R01", 10, 10);
        first.requestReservation("APP-002", "D01", "SP01", "R01", 20, 10);
        String before = canonical(first);

        Registry restarted = new Registry(new FileStorage(dir));
        String after = canonical(restarted);
        require(before.equals(after), "WORLD_RESTART 前后规范快照不一致：\nbefore=" + before
                + "\nafter=" + after);
        require(before.contains("V|RSV-APP-009|APP-009|D01|SP01|R01|HELD|0|10"),
                "规范快照缺少 holder 行：\n" + before);

        Reservation released = restarted.releaseReservation("APP-009");
        require(released.state() == ReservationState.RELEASED,
                "重启后 release 状态=" + released.state() + " 期望=RELEASED");
        require(heldOf(restarted, "R01").equals(List.of("APP-001")),
                "重启后晋升 HELD 列表=" + heldOf(restarted, "R01") + " 期望=[APP-001]");

        Registry third = new Registry(new FileStorage(dir));
        require(canonical(third).equals(canonical(restarted)),
                "第二次重启后的规范快照与晋升后不一致：\n" + canonical(third));
        require(heldOf(third, "R01").equals(List.of("APP-001")),
                "第二次重启后 HELD 列表=" + heldOf(third, "R01") + " 期望=[APP-001]");
    }

    // ================= RW-24 =================

    /** 提交失败均无幽灵状态。 */
    private static void rw24() {
        FailingStorage storage = new FailingStorage(new MemoryStorage());
        Registry registry = new Registry(storage);
        seedAll(registry);
        registry.requestReservation("APP-009", "D01", "SP01", "R01", 0, 10);
        registry.requestReservation("APP-002", "D01", "SP01", "R01", 10, 10);
        String baseline = canonical(registry);
        String committedBaseline = canonicalOfCommitted(storage.loadState());

        // ① request 提交失败
        storage.failNextCommit();
        expectInjectedFailure(() -> registry.requestReservation(
                "APP-003", "D01", "SP01", "R01", 20, 10), "request 提交失败");
        require(canonical(registry).equals(baseline),
                "request 提交失败后内存快照变了：\n" + canonical(registry));
        require(canonicalOfCommitted(storage.loadState()).equals(committedBaseline),
                "request 提交失败后已提交快照变了");
        require(reservationOf(registry, "APP-003") == null, "request 提交失败却出现了 APP-003 预约");

        // ② release 提交失败
        storage.failNextCommit();
        expectInjectedFailure(() -> registry.releaseReservation("APP-009"), "release 提交失败");
        require(stateOf(registry, "APP-009") == ReservationState.HELD,
                "release 提交失败后 holder 状态=" + stateOf(registry, "APP-009") + " 期望=HELD");
        require(canonical(registry).equals(baseline), "release 提交失败后内存快照变了");

        // ③ cancel WAITING 提交失败
        storage.failNextCommit();
        expectInjectedFailure(() -> registry.cancelReservation("APP-002"), "cancel 提交失败");
        require(stateOf(registry, "APP-002") == ReservationState.WAITING,
                "cancel 提交失败后状态=" + stateOf(registry, "APP-002") + " 期望=WAITING");
        require(queueOf(registry, "R01").equals(List.of("APP-002")),
                "cancel 提交失败后队列=" + queueOf(registry, "R01") + " 期望=[APP-002]");

        // ④ promotion（enable）提交失败
        registry.releaseReservation("APP-009");
        registry.cancelReservation("APP-002");
        registry.setVirtualResourceAvailability("R01", ResourceAvailability.DISABLED);
        registry.requestReservation("APP-004", "D01", "SP01", "R01", 50, 10);
        String beforePromotion = canonical(registry);
        require(resource(registry, "R01").availability() == ResourceAvailability.DISABLED,
                "前置：R01 应为 DISABLED");
        require(stateOf(registry, "APP-004") == ReservationState.WAITING,
                "前置：APP-004 应为 WAITING");

        storage.failNextCommit();
        expectInjectedFailure(
                () -> registry.setVirtualResourceAvailability("R01", ResourceAvailability.ENABLED),
                "promotion(enable) 提交失败");
        require(canonical(registry).equals(beforePromotion),
                "promotion 提交失败后快照变了：\n" + canonical(registry));
        require(resource(registry, "R01").availability() == ResourceAvailability.DISABLED,
                "promotion 提交失败后可用性=" + resource(registry, "R01").availability() + " 期望=DISABLED");
        require(stateOf(registry, "APP-004") == ReservationState.WAITING,
                "promotion 提交失败后 APP-004 状态=" + stateOf(registry, "APP-004") + " 期望=WAITING");

        require(canonicalOfCommitted(storage.loadState()).equals(beforePromotion),
                "promotion 提交失败后已提交快照变了");
    }

    // ================= 夹具 =================

    /** P01/P02 + APP-000..APP-009 + D01/SP01/R01。 */
    private static Registry registryWithChain() {
        Registry registry = new Registry(new MemoryStorage());
        seedAll(registry);
        return registry;
    }

    private static void seedAll(Registry registry) {
        registry.registerPoint("P01", "缓冲点");
        registry.registerPoint("P02", "接收点");
        for (int i = 0; i <= 9; i++) {
            registry.registerApplication(String.format("APP-%03d", i), "P01", "P02");
        }
        registry.addDestination("D01", "一号目的地");
        registry.addServicePoint("SP01", "D01", "P01");
        registry.addVirtualResource("R01", "SP01");
    }

    // ================= 快照读取 =================

    private static String canonical(Registry registry) {
        return registry.resourceWorldSnapshot().canonicalText();
    }

    private static String canonicalOfCommitted(RegistryState state) {
        return state.resourceWorld().canonicalText();
    }

    private static List<Destination> destinations(Registry registry) {
        return registry.resourceWorldSnapshot().destinations();
    }

    private static List<ServicePoint> servicePoints(Registry registry) {
        return registry.resourceWorldSnapshot().servicePoints();
    }

    private static List<VirtualResource> virtualResources(Registry registry) {
        return registry.resourceWorldSnapshot().resources();
    }

    private static List<Reservation> reservations(Registry registry) {
        return registry.resourceWorldSnapshot().reservations();
    }

    private static VirtualResource resource(Registry registry, String resourceId) {
        for (VirtualResource resource : virtualResources(registry)) {
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

    // ================= 断言基础设施 =================

    private interface CaseBody {
        void run() throws Exception;
    }

    private interface Action {
        void run();
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

    private static void expectRegistryFailure(Action action, String code, String detail, String label) {
        Throwable failure = capture(action);
        require(failure != null, label + "：未抛异常（期望 " + code + "）");
        require(failure instanceof RegistryException,
                label + "：异常类型=" + failure.getClass().getSimpleName() + "（期望 RegistryException）");
        RegistryException exception = (RegistryException) failure;
        require(exception.code().equals(code),
                label + "：code=" + exception.code() + " 期望=" + code);
        if (detail != null) {
            require(exception.detail().equals(detail),
                    label + "：detail=" + exception.detail() + " 期望=" + detail);
        }
    }

    private static void expectInjectedFailure(Action action, String label) {
        Throwable failure = capture(action);
        require(failure != null, label + "：未抛异常");
        require(failure.getClass() == RuntimeException.class
                        && "INJECTED_COMMIT_FAILURE".equals(failure.getMessage()),
                label + "：异常=" + failure.getClass().getSimpleName()
                        + ":" + failure.getMessage() + " 期望注入的提交失败");
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
