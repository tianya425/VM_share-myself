package lab;

import java.util.List;
import java.util.Optional;

public class RegistryChecks {

    private static int passed = 0;

    public static void main(String[] args) throws Exception {
        r01();
        r02();
        r03();
        r04();
        r05();
        r06();
        r07();
        r08();
        r09();
        r10();
        r11();
        r12();
        r13();
        r14();
        r15();
        r16();
        r17();
        r18();
        r19();
        r20();
        r21();
        r22();
        r23();
        r24();
        r25();
        r26();
        r27();
        r33();
        r34();
        r35();
        r36();
        r37();
        r38();
        r39();
        r40();
        r41();
        r42();
        r43();
        r44();
        r45();
        r46();
        r47();
        r48();
        r49();
        r50();
        r51();
        r52();
        r53();
        r54();
        r55();
        r56();
        r57();
        r58();
        r59();
        r60();
        r61();
        r62();
        r63();
        r64();
        r65();
        r66();
        r67();
        r68();
        r69();
        r70();
        r71();
        r72();
        r73();
        r74();
        r75();
        r76();
        r77();
        r78();
        r79();
        r80();
        r81();
        r82();
        r83();
        r84();
        r85();

        System.out.println("CHECKS_PASSED=" + passed);
    }

    private static void r01() {
        Registry r = new Registry(new MemoryStorage());
        check(r.listPoints().isEmpty(), "R01 empty points");
        check(r.listApplications().isEmpty(), "R01 empty applications");
    }

    private static void r02() {
        Registry r = new Registry(new MemoryStorage());
        r.registerPoint("P02", "装配线接收点");
        r.registerPoint("P01", "原料暂存点");

        Point p = r.findPoint("P01").orElseThrow();
        check(p.pointId().equals("P01"), "R02 pointId");
        check(p.name().equals("原料暂存点"), "R02 name");

        List<Point> all = r.listPoints();
        check(all.size() == 2, "R02 size");
        check(all.get(0).pointId().equals("P01"), "R02 first P01");
        check(all.get(1).pointId().equals("P02"), "R02 second P02");
    }

    private static void r03() {
        Registry r = new Registry(new MemoryStorage());
        r.registerPoint("P01", "原料暂存点");

        expectError(() -> r.registerPoint("P01", "原料暂存点"), "POINT_EXISTS", "P01", "R03 same name");
        expectError(() -> r.registerPoint("P01", "别的名字"), "POINT_EXISTS", "P01", "R03 diff name");

        Point p = r.findPoint("P01").orElseThrow();
        check(p.name().equals("原料暂存点"), "R03 original unchanged");
        check(r.listPoints().size() == 1, "R03 total 1");
    }

    private static void r04() {
        Registry r = new Registry(new MemoryStorage());
        r.registerPoint("P01", "原料暂存点");
        r.registerPoint("P02", "装配线接收点");

        RegistrationResult res = r.registerApplication("APP-001", "P01", "P02");
        check(!res.replay(), "R04 not replay");
        check(res.application().applicationId().equals("APP-001"), "R04 applicationId");
        check(res.application().fromPointId().equals("P01"), "R04 fromPointId");
        check(res.application().toPointId().equals("P02"), "R04 toPointId");

        Optional<TransferApplication> found = r.findApplication("APP-001");
        check(found.isPresent(), "R04 find present");
    }

    private static void r05() {
        Registry r = new Registry(new MemoryStorage());
        r.registerPoint("P01", "原料暂存点");
        r.registerPoint("P02", "装配线接收点");
        r.registerApplication("APP-001", "P01", "P02");

        RegistrationResult res = r.registerApplication("APP-001", "P01", "P02");
        check(res.replay(), "R05 replay true");
        check(r.listApplications().size() == 1, "R05 total 1");
        check(res.application().fromPointId().equals("P01"), "R05 original from");
        check(res.application().toPointId().equals("P02"), "R05 original to");
    }

    private static void r06() {
        Registry r = new Registry(new MemoryStorage());
        r.registerPoint("P01", "原料暂存点");
        r.registerPoint("P02", "装配线接收点");
        r.registerPoint("P03", "成品暂存点");
        r.registerApplication("APP-001", "P01", "P02");

        expectError(() -> r.registerApplication("APP-001", "P03", "P02"),
                "APPLICATION_CONFLICT", "APP-001", "R06 from changed");
        expectError(() -> r.registerApplication("APP-001", "P01", "P03"),
                "APPLICATION_CONFLICT", "APP-001", "R06 to changed");
        expectError(() -> r.registerApplication("APP-001", "P02", "P01"),
                "APPLICATION_CONFLICT", "APP-001", "R06 reversed");

        TransferApplication a = r.findApplication("APP-001").orElseThrow();
        check(a.fromPointId().equals("P01"), "R06 original from");
        check(a.toPointId().equals("P02"), "R06 original to");
        check(r.listApplications().size() == 1, "R06 total 1");
    }

    private static void r07() {
        Registry r = new Registry(new MemoryStorage());
        r.registerPoint("P01", "原料暂存点");
        r.registerPoint("P02", "装配线接收点");

        RegistrationResult a1 = r.registerApplication("APP-001", "P01", "P02");
        RegistrationResult a2 = r.registerApplication("APP-002", "P01", "P02");

        check(!a1.replay(), "R07 first not replay");
        check(!a2.replay(), "R07 second not replay");
        check(r.listApplications().size() == 2, "R07 total 2");
    }

    private static void r08() {
        Registry r = new Registry(new MemoryStorage());
        r.registerPoint("P01", "原料暂存点");
        r.registerPoint("P02", "装配线接收点");

        expectError(() -> r.registerApplication("APP-001", "P99", "P02"),
                "UNKNOWN_POINT", "P99", "R08 unknown from");
        expectError(() -> r.registerApplication("APP-002", "P01", "P99"),
                "UNKNOWN_POINT", "P99", "R08 unknown to");
        expectError(() -> r.registerApplication("APP-003", "P98", "P99"),
                "UNKNOWN_POINT", "P98", "R08 both unknown checks from first");

        check(r.listApplications().isEmpty(), "R08 no applications added");
    }

    private static void r09() {
        Registry r = new Registry(new MemoryStorage());
        r.registerPoint("P01", "原料暂存点");

        expectError(() -> r.registerApplication("APP-001", "P01", "P01"),
                "SAME_POINT", "P01", "R09 same registered point");
        expectError(() -> r.registerApplication("APP-002", "P99", "P99"),
                "UNKNOWN_POINT", "P99", "R09 unknown same point");
    }

    private static void r10() {
        Registry r = new Registry(new MemoryStorage());
        r.registerPoint("P01", "原料暂存点");

        check(r.findPoint("P99").isEmpty(), "R10 find unknown point empty");
        check(r.findApplication("APP-404").isEmpty(), "R10 find unknown application empty");
        check(r.listPoints().size() == 1, "R10 points unchanged");
        check(r.listApplications().isEmpty(), "R10 applications unchanged");
    }

    private static void r11() {
        Registry r = new Registry(new MemoryStorage());
        r.registerPoint("P01", "原料暂存点");
        r.registerPoint("P02", "装配线接收点");

        expectError(() -> r.registerPoint("p03", "小写"), "INVALID_ID", "p03", "R11 lowercase");
        expectError(() -> r.registerPoint("1P03", "数字开头"), "INVALID_ID", "1P03", "R11 digit first");
        expectError(() -> r.registerPoint("P/03", "斜杠"), "INVALID_ID", "P/03", "R11 slash");

        String tooLong = "P" + "A".repeat(32);
        expectError(() -> r.registerPoint(tooLong, "太长"), "INVALID_ID", tooLong, "R11 length 33");

        expectError(() -> r.findPoint("p01"), "INVALID_ID", "p01", "R11 find lowercase");

        expectError(() -> r.registerApplication("app-001", "P01", "P02"),
                "INVALID_ID", "app-001", "R11 application id lowercase");
        expectError(() -> r.registerApplication("APP-001", "p01", "P02"),
                "INVALID_ID", "p01", "R11 application from lowercase");
        expectError(() -> r.registerApplication("APP-001", "P01", "p02"),
                "INVALID_ID", "p02", "R11 application to lowercase");
        expectError(() -> r.findApplication("app-001"),
                "INVALID_ID", "app-001", "R11 find application lowercase");

        check(r.listPoints().size() == 2, "R11 points unchanged");
        check(r.listApplications().isEmpty(), "R11 applications unchanged");
    }

    private static void r12() {
        Registry r = new Registry(new MemoryStorage());

        String one = "A";
        r.registerPoint(one, "单字符");
        check(r.findPoint(one).isPresent(), "R12 length 1 allowed");

        String max = "A".repeat(32);
        r.registerPoint(max, "32字符");
        check(r.findPoint(max).isPresent(), "R12 length 32 allowed");

        check(r.listPoints().size() == 2, "R12 total 2");
    }

    private static void r13() {
        Registry r = new Registry(new MemoryStorage());

        String name40 = "名".repeat(40);
        r.registerPoint("P01", name40);
        check(r.findPoint("P01").orElseThrow().name().length() == 40, "R13 length 40 allowed");

        expectError(() -> r.registerPoint("P02", ""), "INVALID_NAME", "", "R13 empty name");

        String name41 = "名".repeat(41);
        expectError(() -> r.registerPoint("P03", name41), "INVALID_NAME", "", "R13 length 41");

        expectError(() -> r.registerPoint("P04", "含 空白"), "INVALID_NAME", "", "R13 whitespace");
    }

    private static void r14() {
        Registry r = new Registry(new MemoryStorage());
        r.registerPoint("P01", "原料暂存点");
        r.registerPoint("P02", "装配线接收点");
        r.registerPoint("P03", "成品暂存点");
        r.registerApplication("APP-001", "P01", "P02");

        expectError(() -> r.registerApplication("APP-001", "P01", "p99"),
                "INVALID_ID", "p99", "R14 invalid id before conflict");
        expectError(() -> r.registerApplication("APP-001", "P01", "P03"),
                "APPLICATION_CONFLICT", "APP-001", "R14 valid but different to");

        TransferApplication a = r.findApplication("APP-001").orElseThrow();
        check(a.fromPointId().equals("P01"), "R14 original from");
        check(a.toPointId().equals("P02"), "R14 original to");
    }

    private static void r15() {
        Registry r = new Registry(new MemoryStorage());
        r.registerPoint("P01", "原料暂存点");
        r.registerPoint("P02", "装配线接收点");

        r.registerApplication("APP-002", "P01", "P02");
        r.registerApplication("APP-001", "P01", "P02");

        List<TransferApplication> all = r.listApplications();
        check(all.size() == 2, "R15 size 2");
        check(all.get(0).applicationId().equals("APP-001"), "R15 first APP-001");
        check(all.get(1).applicationId().equals("APP-002"), "R15 second APP-002");
    }

    private static void r16() {
        Registry r = new Registry(new MemoryStorage());
        r.registerPoint("P01", "原料暂存点");
        r.registerPoint("P02", "装配线接收点");
        r.registerApplication("APP-001", "P01", "P02");

        List<Point> pointsSnapshot = r.listPoints();
        List<TransferApplication> appSnapshot = r.listApplications();

        r.registerPoint("P03", "成品暂存点");
        r.registerApplication("APP-002", "P01", "P02");

        check(pointsSnapshot.size() == 2, "R16 points snapshot unchanged");
        check(appSnapshot.size() == 1, "R16 applications snapshot unchanged");
        check(r.listPoints().size() == 3, "R16 registry points now 3");
        check(r.listApplications().size() == 2, "R16 registry applications now 2");

        expectUnsupported(() -> pointsSnapshot.add(new Point("P99", "x")), "R16 points snapshot immutable");
        expectUnsupported(() -> appSnapshot.add(new TransferApplication("APP-999", "P01", "P02")), "R16 applications snapshot immutable");
    }
    private static void r17() {
        Registry r = new Registry(new MemoryStorage());
        r.registerPoint("P01", "原料暂存点");
        r.registerPoint("P02", "装配线接收点");
        r.registerApplication("APP-001", "P01", "P02");

        TransferApplication a = r.findApplication("APP-001").orElseThrow();
        check(a.state() == ApplicationState.CREATED, "R17 initial state CREATED");
    }

    private static void r18() {
        Registry r = new Registry(new MemoryStorage());
        r.registerPoint("P01", "原料暂存点");
        r.registerPoint("P02", "装配线接收点");
        r.registerApplication("APP-001", "P01", "P02");

        TransferApplication a = r.dispatchApplication("APP-001");
        check(a.state() == ApplicationState.DISPATCHED, "R18 CREATED -> DISPATCHED");
    }

    private static void r19() {
        Registry r = new Registry(new MemoryStorage());
        r.registerPoint("P01", "原料暂存点");
        r.registerPoint("P02", "装配线接收点");
        r.registerApplication("APP-001", "P01", "P02");

        expectError(() -> r.executeApplication("APP-001"),
                "INVALID_STATE_TRANSITION", "APP-001", "R19 CREATED -> EXECUTING rejected");

        TransferApplication a = r.findApplication("APP-001").orElseThrow();
        check(a.state() == ApplicationState.CREATED, "R19 state unchanged");
    }

    private static void r20() {
        Registry r = new Registry(new MemoryStorage());
        r.registerPoint("P01", "原料暂存点");
        r.registerPoint("P02", "装配线接收点");
        r.registerApplication("APP-001", "P01", "P02");

        expectError(() -> r.completeApplication("APP-001"),
                "INVALID_STATE_TRANSITION", "APP-001", "R20 CREATED -> COMPLETED rejected");
    }

    private static void r21() {
        Registry r = new Registry(new MemoryStorage());
        r.registerPoint("P01", "原料暂存点");
        r.registerPoint("P02", "装配线接收点");
        r.registerApplication("APP-001", "P01", "P02");

        r.dispatchApplication("APP-001");
        TransferApplication a = r.executeApplication("APP-001");
        check(a.state() == ApplicationState.EXECUTING, "R21 DISPATCHED -> EXECUTING");
    }

    private static void r22() {
        Registry r = new Registry(new MemoryStorage());
        r.registerPoint("P01", "原料暂存点");
        r.registerPoint("P02", "装配线接收点");
        r.registerApplication("APP-001", "P01", "P02");

        r.dispatchApplication("APP-001");
        expectError(() -> r.completeApplication("APP-001"),
                "INVALID_STATE_TRANSITION", "APP-001", "R22 DISPATCHED -> COMPLETED rejected");
    }

    private static void r23() {
        Registry r = new Registry(new MemoryStorage());
        r.registerPoint("P01", "原料暂存点");
        r.registerPoint("P02", "装配线接收点");
        r.registerApplication("APP-001", "P01", "P02");

        r.dispatchApplication("APP-001");
        r.executeApplication("APP-001");
        TransferApplication a = r.completeApplication("APP-001");
        check(a.state() == ApplicationState.COMPLETED, "R23 EXECUTING -> COMPLETED");
    }

    private static void r24() {
        Registry r = new Registry(new MemoryStorage());
        r.registerPoint("P01", "原料暂存点");
        r.registerPoint("P02", "装配线接收点");
        r.registerApplication("APP-001", "P01", "P02");

        r.dispatchApplication("APP-001");
        r.executeApplication("APP-001");
        r.completeApplication("APP-001");

        expectError(() -> r.dispatchApplication("APP-001"),
                "INVALID_STATE_TRANSITION", "APP-001", "R24 COMPLETED -> DISPATCHED rejected");
        expectError(() -> r.executeApplication("APP-001"),
                "INVALID_STATE_TRANSITION", "APP-001", "R24 COMPLETED -> EXECUTING rejected");
        expectError(() -> r.completeApplication("APP-001"),
                "INVALID_STATE_TRANSITION", "APP-001", "R24 COMPLETED -> COMPLETED rejected");
        expectError(() -> r.cancelApplication("APP-001"),
                "INVALID_STATE_TRANSITION", "APP-001", "R24 COMPLETED -> CANCELLED rejected");
    }

    private static void r25() {
        Registry r = new Registry(new MemoryStorage());
        r.registerPoint("P01", "原料暂存点");
        r.registerPoint("P02", "装配线接收点");
        r.registerApplication("APP-001", "P01", "P02");

        r.cancelApplication("APP-001");

        expectError(() -> r.dispatchApplication("APP-001"),
                "INVALID_STATE_TRANSITION", "APP-001", "R25 CANCELLED -> DISPATCHED rejected");
        expectError(() -> r.executeApplication("APP-001"),
                "INVALID_STATE_TRANSITION", "APP-001", "R25 CANCELLED -> EXECUTING rejected");
        expectError(() -> r.completeApplication("APP-001"),
                "INVALID_STATE_TRANSITION", "APP-001", "R25 CANCELLED -> COMPLETED rejected");
        expectError(() -> r.cancelApplication("APP-001"),
                "INVALID_STATE_TRANSITION", "APP-001", "R25 CANCELLED -> CANCELLED rejected");
    }

    private static void r26() {
        Registry r = new Registry(new MemoryStorage());
        r.registerPoint("P01", "原料暂存点");
        r.registerPoint("P02", "装配线接收点");
        r.registerApplication("APP-001", "P01", "P02");

        TransferApplication a = r.cancelApplication("APP-001");
        check(a.state() == ApplicationState.CANCELLED, "R26 CREATED -> CANCELLED");
    }

    private static void r27() {
        Registry r = new Registry(new MemoryStorage());
        r.registerPoint("P01", "原料暂存点");
        r.registerPoint("P02", "装配线接收点");

        expectError(() -> r.dispatchApplication("APP-999"),
                "UNKNOWN_APPLICATION", "APP-999", "R27 dispatch unknown");
        expectError(() -> r.executeApplication("APP-999"),
                "UNKNOWN_APPLICATION", "APP-999", "R27 execute unknown");
        expectError(() -> r.completeApplication("APP-999"),
                "UNKNOWN_APPLICATION", "APP-999", "R27 complete unknown");
        expectError(() -> r.cancelApplication("APP-999"),
                "UNKNOWN_APPLICATION", "APP-999", "R27 cancel unknown");
    }
    private static void r33() {
        Registry r = new Registry(new MemoryStorage(), new FakeRcsAdapter(FakeRcsAdapter.Mode.ACCEPT));
        r.registerPoint("P01", "原料暂存点");
        r.registerPoint("P02", "装配线接收点");
        r.registerApplication("APP-001", "P01", "P02");

        TransferApplication a = r.dispatchApplication("APP-001");
        check(a.state() == ApplicationState.DISPATCHED, "R33 ACCEPT -> DISPATCHED");
        check(a.externalOrderId() != null, "R33 externalOrderId recorded");
        check(a.externalOrderId().equals("EXT-APP-001"), "R33 externalOrderId value");
    }

    private static void r34() {
        Registry r = new Registry(new MemoryStorage(), new FakeRcsAdapter(FakeRcsAdapter.Mode.REJECT));
        r.registerPoint("P01", "原料暂存点");
        r.registerPoint("P02", "装配线接收点");
        r.registerApplication("APP-001", "P01", "P02");

        expectError(() -> r.dispatchApplication("APP-001"),
                "RCS_REJECTED", "APP-001", "R34 REJECT raises");

        TransferApplication a = r.findApplication("APP-001").orElseThrow();
        check(a.state() == ApplicationState.CREATED, "R34 state unchanged");
        check(a.externalOrderId() == null, "R34 no externalOrderId");
    }

    private static void r35() {
        Registry r = new Registry(new MemoryStorage(), new FakeRcsAdapter(FakeRcsAdapter.Mode.TIMEOUT_BUT_CREATED));
        r.registerPoint("P01", "原料暂存点");
        r.registerPoint("P02", "装配线接收点");
        r.registerApplication("APP-001", "P01", "P02");

        TransferApplication a = r.dispatchApplication("APP-001");
        check(a.state() == ApplicationState.UNKNOWN, "R35 timeout -> UNKNOWN");
        check(a.externalOrderId() == null, "R35 no externalOrderId yet");

        TransferApplication afterQuery = r.queryApplication("APP-001");
        check(afterQuery.state() == ApplicationState.DISPATCHED, "R35 query -> DISPATCHED");
        check("EXT-APP-001".equals(afterQuery.externalOrderId()), "R35 externalOrderId recovered");
    }

    private static void r36() {
        Registry r = new Registry(new MemoryStorage(), new FakeRcsAdapter(FakeRcsAdapter.Mode.TIMEOUT_NOT_CREATED));
        r.registerPoint("P01", "原料暂存点");
        r.registerPoint("P02", "装配线接收点");
        r.registerApplication("APP-001", "P01", "P02");

        TransferApplication a = r.dispatchApplication("APP-001");
        check(a.state() == ApplicationState.UNKNOWN, "R36 timeout -> UNKNOWN");

        expectError(() -> r.queryApplication("APP-001"),
                "EXTERNAL_ORDER_NOT_FOUND", "APP-001", "R36 query not found");

        TransferApplication after = r.findApplication("APP-001").orElseThrow();
        check(after.state() == ApplicationState.UNKNOWN, "R36 still UNKNOWN");
    }

    private static void r37() {
        Registry r = new Registry(new MemoryStorage(), new FakeRcsAdapter(FakeRcsAdapter.Mode.ACCEPT));
        r.registerPoint("P01", "原料暂存点");
        r.registerPoint("P02", "装配线接收点");
        r.registerApplication("APP-001", "P01", "P02");

        r.dispatchApplication("APP-001");
        expectError(() -> r.dispatchApplication("APP-001"),
                "INVALID_STATE_TRANSITION", "APP-001", "R37 dispatch twice rejected");

        TransferApplication a = r.findApplication("APP-001").orElseThrow();
        check(a.state() == ApplicationState.DISPATCHED, "R37 state unchanged");
    }

    private static void r38() {
        Registry r = new Registry(new MemoryStorage(), new FakeRcsAdapter(FakeRcsAdapter.Mode.ACCEPT));
        r.registerPoint("P01", "原料暂存点");
        r.registerPoint("P02", "装配线接收点");
        r.registerApplication("APP-001", "P01", "P02");

        expectError(() -> r.queryApplication("APP-001"),
                "INVALID_STATE_TRANSITION", "APP-001", "R38 query on CREATED rejected");
    }

    private static void r39() {
        Registry r = new Registry(new MemoryStorage(), new FakeRcsAdapter(FakeRcsAdapter.Mode.TIMEOUT_BUT_CREATED));
        r.registerPoint("P01", "原料暂存点");
        r.registerPoint("P02", "装配线接收点");
        r.registerApplication("APP-001", "P01", "P02");

        r.dispatchApplication("APP-001");
        check(r.findApplication("APP-001").orElseThrow().state() == ApplicationState.UNKNOWN,
                "R39 UNKNOWN before cancel");

        TransferApplication a = r.cancelApplication("APP-001");
        check(a.state() == ApplicationState.CANCELLED, "R39 UNKNOWN -> CANCELLED");
    }

    private static void r40() {
        Registry r = new Registry(new MemoryStorage(), new FakeRcsAdapter(FakeRcsAdapter.Mode.ACCEPT));
        r.registerPoint("P01", "原料暂存点");
        r.registerPoint("P02", "装配线接收点");

        expectError(() -> r.dispatchApplication("APP-999"),
                "UNKNOWN_APPLICATION", "APP-999", "R40 dispatch unknown");
        expectError(() -> r.queryApplication("APP-999"),
                "UNKNOWN_APPLICATION", "APP-999", "R40 query unknown");
    }
    private static void r41() {
        Registry r = new Registry(new MemoryStorage(), new FakeRcsAdapter(FakeRcsAdapter.Mode.ACCEPT));
        r.registerPoint("P01", "原料暂存点");
        r.registerPoint("P02", "装配线接收点");
        r.registerApplication("APP-001", "P01", "P02");

        r.dispatchApplication("APP-001");

        List<OutboxEntry> all = r.listOutbox();
        check(all.size() == 1, "R41 outbox size 1");
        OutboxEntry e = all.get(0);
        check(e.applicationId().equals("APP-001"), "R41 applicationId");
        check(e.status() == OutboxStatus.SENT, "R41 status SENT");
        check("EXT-APP-001".equals(e.externalOrderId()), "R41 externalOrderId");
        check(e.attempts() == 0, "R41 attempts 0");
    }

    private static void r42() {
        Registry r = new Registry(new MemoryStorage(), new FakeRcsAdapter(FakeRcsAdapter.Mode.TIMEOUT_BUT_CREATED));
        r.registerPoint("P01", "原料暂存点");
        r.registerPoint("P02", "装配线接收点");
        r.registerApplication("APP-001", "P01", "P02");

        r.dispatchApplication("APP-001");

        List<OutboxEntry> all = r.listOutbox();
        check(all.size() == 1, "R42 outbox size 1");
        OutboxEntry e = all.get(0);
        check(e.status() == OutboxStatus.UNCERTAIN, "R42 status UNCERTAIN");
        check(e.externalOrderId() == null, "R42 externalOrderId null");
        check(e.attempts() == 1, "R42 attempts 1");
    }

    private static void r43() {
        Registry r = new Registry(new MemoryStorage(), new FakeRcsAdapter(FakeRcsAdapter.Mode.REJECT));
        r.registerPoint("P01", "原料暂存点");
        r.registerPoint("P02", "装配线接收点");
        r.registerApplication("APP-001", "P01", "P02");

        expectError(() -> r.dispatchApplication("APP-001"),
                "RCS_REJECTED", "APP-001", "R43 rejected");

        List<OutboxEntry> all = r.listOutbox();
        check(all.size() == 1, "R43 outbox size 1");
        OutboxEntry e = all.get(0);
        check(e.status() == OutboxStatus.FAILED, "R43 status FAILED");
        check(e.externalOrderId() == null, "R43 externalOrderId null");

        TransferApplication a = r.findApplication("APP-001").orElseThrow();
        check(a.state() == ApplicationState.CREATED, "R43 application still CREATED");
    }

    private static void r44() {
        Registry r = new Registry(new MemoryStorage(), new FakeRcsAdapter(FakeRcsAdapter.Mode.TIMEOUT_BUT_CREATED));
        r.registerPoint("P01", "原料暂存点");
        r.registerPoint("P02", "装配线接收点");
        r.registerApplication("APP-001", "P01", "P02");

        r.dispatchApplication("APP-001");
        // REL-01：TIMEOUT 后 Outbox 应为 UNCERTAIN
        check(r.listOutbox().get(0).status() == OutboxStatus.UNCERTAIN,
                "R44 UNCERTAIN before process");
        check(r.findApplication("APP-001").orElseThrow().state() == ApplicationState.UNKNOWN,
                "R44 application UNKNOWN before process");

        // processOutbox 对 UNCERTAIN 只 query
        int processed = r.processOutbox();
        check(processed == 1, "R44 process returns 1 (query recovered)");

        OutboxEntry e = r.listOutbox().get(0);
        check(e.status() == OutboxStatus.SENT, "R44 status SENT after query");
        check(e.externalOrderId() != null, "R44 externalOrderId recorded after query");

        TransferApplication a = r.findApplication("APP-001").orElseThrow();
        check(a.state() == ApplicationState.DISPATCHED, "R44 application DISPATCHED after query");
    }
    private static void r45() {
        MemoryStorage storage = new MemoryStorage();

        // 外部系统是同一个，进程重启后仍然存在
        FakeRcsAdapter fake = new FakeRcsAdapter(FakeRcsAdapter.Mode.TIMEOUT_BUT_CREATED);

        Registry r1 = new Registry(storage, fake);
        r1.registerPoint("P01", "原料暂存点");
        r1.registerPoint("P02", "装配线接收点");
        r1.registerApplication("APP-001", "P01", "P02");
        r1.dispatchApplication("APP-001");

        check(r1.findApplication("APP-001").orElseThrow().state() == ApplicationState.UNKNOWN,
                "R45 first registry UNKNOWN");
        check(r1.listOutbox().get(0).status() == OutboxStatus.UNCERTAIN,
                "R45 first outbox UNCERTAIN");

        // 模拟进程重启：新建 Registry，但 Fake 是同一个
        Registry r2 = new Registry(storage, fake);
        check(r2.findApplication("APP-001").orElseThrow().state() == ApplicationState.UNKNOWN,
                "R45 second registry loads UNKNOWN");
        check(r2.listOutbox().size() == 1, "R45 second outbox loaded");
        check(r2.listOutbox().get(0).status() == OutboxStatus.UNCERTAIN,
                "R45 second outbox UNCERTAIN");

        int processed = r2.processOutbox();
        check(processed == 1, "R45 processed 1");

        check(r2.findApplication("APP-001").orElseThrow().state() == ApplicationState.DISPATCHED,
                "R45 application recovered to DISPATCHED");
        check("EXT-APP-001".equals(r2.findApplication("APP-001").orElseThrow().externalOrderId()),
                "R45 externalOrderId recorded");
        check(r2.listOutbox().get(0).status() == OutboxStatus.SENT,
                "R45 outbox SENT");
    }

    private static void r46() {
        Registry r = new Registry(new MemoryStorage(), new FakeRcsAdapter(FakeRcsAdapter.Mode.ACCEPT));
        r.registerPoint("P01", "原料暂存点");
        r.registerPoint("P02", "装配线接收点");
        r.registerApplication("APP-001", "P01", "P02");
        r.dispatchApplication("APP-001");

        InboxEntry entry = r.receiveCallback("APP-001", "STARTED");
        check(entry.status() == InboxStatus.PROCESSED, "R46 STARTED processed");
        check("".equals(entry.reason()), "R46 reason empty");

        TransferApplication a = r.findApplication("APP-001").orElseThrow();
        check(a.state() == ApplicationState.EXECUTING, "R46 state EXECUTING");
    }

    private static void r47() {
        Registry r = new Registry(new MemoryStorage(), new FakeRcsAdapter(FakeRcsAdapter.Mode.ACCEPT));
        r.registerPoint("P01", "原料暂存点");
        r.registerPoint("P02", "装配线接收点");
        r.registerApplication("APP-001", "P01", "P02");
        r.dispatchApplication("APP-001");
        r.receiveCallback("APP-001", "STARTED");

        InboxEntry entry = r.receiveCallback("APP-001", "COMPLETED");
        check(entry.status() == InboxStatus.PROCESSED, "R47 COMPLETED processed");

        TransferApplication a = r.findApplication("APP-001").orElseThrow();
        check(a.state() == ApplicationState.COMPLETED, "R47 state COMPLETED");
    }

    private static void r48() {
        Registry r = new Registry(new MemoryStorage(), new FakeRcsAdapter(FakeRcsAdapter.Mode.ACCEPT));
        r.registerPoint("P01", "原料暂存点");
        r.registerPoint("P02", "装配线接收点");
        r.registerApplication("APP-001", "P01", "P02");
        r.dispatchApplication("APP-001");

        r.receiveCallback("APP-001", "STARTED");
        InboxEntry dup = r.receiveCallback("APP-001", "STARTED");
        check(dup.status() == InboxStatus.PROCESSED, "R48 duplicate still PROCESSED");

        check(r.listInbox().size() == 1, "R48 inbox total 1");

        TransferApplication a = r.findApplication("APP-001").orElseThrow();
        check(a.state() == ApplicationState.EXECUTING, "R48 state unchanged");
    }

    private static void r49() {
        Registry r = new Registry(new MemoryStorage(), new FakeRcsAdapter(FakeRcsAdapter.Mode.ACCEPT));
        r.registerPoint("P01", "原料暂存点");
        r.registerPoint("P02", "装配线接收点");
        r.registerApplication("APP-001", "P01", "P02");
        r.dispatchApplication("APP-001");

        InboxEntry entry = r.receiveCallback("APP-001", "COMPLETED");
        check(entry.status() == InboxStatus.REJECTED, "R49 COMPLETED rejected");
        check("INVALID_STATE_TRANSITION".equals(entry.reason()), "R49 reason");

        TransferApplication a = r.findApplication("APP-001").orElseThrow();
        check(a.state() == ApplicationState.DISPATCHED, "R49 state unchanged");
    }

    private static void r50() {
        Registry r = new Registry(new MemoryStorage(), new FakeRcsAdapter(FakeRcsAdapter.Mode.ACCEPT));

        InboxEntry entry = r.receiveCallback("APP-999", "STARTED");
        check(entry.status() == InboxStatus.REJECTED, "R50 unknown rejected");
        check("UNKNOWN_APPLICATION".equals(entry.reason()), "R50 reason");
    }

    private static void r51() {
        Registry r = new Registry(new MemoryStorage(), new FakeRcsAdapter(FakeRcsAdapter.Mode.ACCEPT));
        r.registerPoint("P01", "原料暂存点");
        r.registerPoint("P02", "装配线接收点");
        r.registerApplication("APP-001", "P01", "P02");
        r.dispatchApplication("APP-001");

        expectError(() -> r.receiveCallback("APP-001", "BOGUS"),
                "INVALID_EVENT_TYPE", "BOGUS", "R51 invalid event type");
    }

    private static void r52() {
        Registry r = new Registry(new MemoryStorage(), new FakeRcsAdapter(FakeRcsAdapter.Mode.ACCEPT));
        r.registerPoint("P01", "原料暂存点");
        r.registerPoint("P02", "装配线接收点");
        r.registerApplication("APP-001", "P01", "P02");
        r.dispatchApplication("APP-001");

        r.receiveCallback("APP-001", "FAILED");

        TransferApplication a = r.findApplication("APP-001").orElseThrow();
        check(a.state() == ApplicationState.CANCELLED, "R52 FAILED -> CANCELLED");
    }
    private static void r53() {
        Registry r = new Registry(new MemoryStorage(), new FakeRcsAdapter(FakeRcsAdapter.Mode.ACCEPT));
        r.registerPoint("P01", "原料暂存点");
        r.registerPoint("P02", "装配线接收点");
        r.registerApplication("APP-001", "P01", "P02");

        WorkflowInstance wf = r.startWorkflow("APP-001");
        check(wf.state() == WorkflowState.RUNNING, "R53 RUNNING");
        check(wf.currentStepIndex() == 0, "R53 index 0");
        check(wf.steps().size() == 3, "R53 steps 3");
        check(wf.steps().get(0).name().equals("DISPATCH"), "R53 step0 DISPATCH");
        check(wf.steps().get(1).name().equals("EXECUTE"), "R53 step1 EXECUTE");
        check(wf.steps().get(2).name().equals("COMPLETE"), "R53 step2 COMPLETE");
    }

    private static void r54() {
        Registry r = new Registry(new MemoryStorage(), new FakeRcsAdapter(FakeRcsAdapter.Mode.ACCEPT));
        r.registerPoint("P01", "原料暂存点");
        r.registerPoint("P02", "装配线接收点");
        r.registerApplication("APP-001", "P01", "P02");

        expectError(() -> r.startWorkflow("APP-999"),
                "UNKNOWN_APPLICATION", "APP-999", "R54 start unknown");
    }

    private static void r55() {
        Registry r = new Registry(new MemoryStorage(), new FakeRcsAdapter(FakeRcsAdapter.Mode.ACCEPT));
        r.registerPoint("P01", "原料暂存点");
        r.registerPoint("P02", "装配线接收点");
        r.registerApplication("APP-001", "P01", "P02");

        r.startWorkflow("APP-001");
        expectError(() -> r.startWorkflow("APP-001"),
                "WORKFLOW_EXISTS", "APP-001", "R55 start twice");
    }

    private static void r56() {
        Registry r = new Registry(new MemoryStorage(), new FakeRcsAdapter(FakeRcsAdapter.Mode.ACCEPT));
        r.registerPoint("P01", "原料暂存点");
        r.registerPoint("P02", "装配线接收点");
        r.registerApplication("APP-001", "P01", "P02");

        expectError(() -> r.advanceWorkflow("APP-001"),
                "WORKFLOW_NOT_FOUND", "APP-001", "R56 advance without start");
    }

    private static void r57() {
        Registry r = new Registry(new MemoryStorage(), new FakeRcsAdapter(FakeRcsAdapter.Mode.ACCEPT));
        r.registerPoint("P01", "原料暂存点");
        r.registerPoint("P02", "装配线接收点");
        r.registerApplication("APP-001", "P01", "P02");

        r.startWorkflow("APP-001");
        WorkflowInstance wf = r.advanceWorkflow("APP-001");
        check(wf.state() == WorkflowState.RUNNING, "R57 still RUNNING");
        check(wf.currentStepIndex() == 1, "R57 index 1");
        check(wf.steps().get(0).state() == WorkflowStepState.COMPLETED, "R57 step0 done");
        check(wf.steps().get(1).state() == WorkflowStepState.PENDING, "R57 step1 pending");
    }

    private static void r58() {
        Registry r = new Registry(new MemoryStorage(), new FakeRcsAdapter(FakeRcsAdapter.Mode.ACCEPT));
        r.registerPoint("P01", "原料暂存点");
        r.registerPoint("P02", "装配线接收点");
        r.registerApplication("APP-001", "P01", "P02");

        r.startWorkflow("APP-001");
        r.advanceWorkflow("APP-001");
        r.advanceWorkflow("APP-001");
        WorkflowInstance wf = r.advanceWorkflow("APP-001");

        check(wf.state() == WorkflowState.COMPLETED, "R58 COMPLETED");
        check(wf.currentStepIndex() == 3, "R58 index 3");
        check(wf.steps().get(0).state() == WorkflowStepState.COMPLETED, "R58 step0 done");
        check(wf.steps().get(1).state() == WorkflowStepState.COMPLETED, "R58 step1 done");
        check(wf.steps().get(2).state() == WorkflowStepState.COMPLETED, "R58 step2 done");
    }

    private static void r59() {
        Registry r = new Registry(new MemoryStorage(), new FakeRcsAdapter(FakeRcsAdapter.Mode.ACCEPT));
        r.registerPoint("P01", "原料暂存点");
        r.registerPoint("P02", "装配线接收点");
        r.registerApplication("APP-001", "P01", "P02");

        r.startWorkflow("APP-001");
        r.advanceWorkflow("APP-001");
        r.advanceWorkflow("APP-001");
        r.advanceWorkflow("APP-001");

        expectError(() -> r.advanceWorkflow("APP-001"),
                "WORKFLOW_NOT_RUNNING", "APP-001", "R59 advance after completed");
    }

    private static void r60() {
        Registry r = new Registry(new MemoryStorage(), new FakeRcsAdapter(FakeRcsAdapter.Mode.ACCEPT));
        r.registerPoint("P01", "原料暂存点");
        r.registerPoint("P02", "装配线接收点");
        r.registerApplication("APP-001", "P01", "P02");

        r.startWorkflow("APP-001");
        check(r.findWorkflow("APP-001").isPresent(), "R60 find present");
        check(r.findWorkflow("APP-999").isEmpty(), "R60 find unknown empty");
        check(r.listWorkflows().size() == 1, "R60 list size 1");
    }
    private static void r61() throws Exception {
        java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("ids-sqlite-r61");
        try {
            SqliteStorage storage = new SqliteStorage(tmp.resolve("db.sqlite"));

            Registry r1 = new Registry(storage, new FakeRcsAdapter(FakeRcsAdapter.Mode.ACCEPT));
            r1.registerPoint("P01", "原料暂存点");
            r1.registerPoint("P02", "装配线接收点");
            r1.registerApplication("APP-001", "P01", "P02");

            Registry r2 = new Registry(storage, new FakeRcsAdapter(FakeRcsAdapter.Mode.ACCEPT));
            check(r2.listPoints().size() == 2, "R61 points persisted");
            check(r2.listApplications().size() == 1, "R61 applications persisted");
        } finally {
            deleteRecursively(tmp);
        }
    }

    private static void r62() throws Exception {
        java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("ids-sqlite-r62");
        try {
            SqliteStorage storage = new SqliteStorage(tmp.resolve("db.sqlite"));

            Registry r1 = new Registry(storage, new FakeRcsAdapter(FakeRcsAdapter.Mode.ACCEPT));
            r1.registerPoint("P01", "原料暂存点");
            r1.registerPoint("P02", "装配线接收点");
            r1.registerApplication("APP-001", "P01", "P02");
            r1.dispatchApplication("APP-001");

            Registry r2 = new Registry(storage, new FakeRcsAdapter(FakeRcsAdapter.Mode.ACCEPT));
            TransferApplication a = r2.findApplication("APP-001").orElseThrow();
            check(a.state() == ApplicationState.DISPATCHED, "R62 state persisted");
            check("EXT-APP-001".equals(a.externalOrderId()), "R62 externalOrderId persisted");
        } finally {
            deleteRecursively(tmp);
        }
    }

    private static void r63() throws Exception {
        java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("ids-sqlite-r63");
        try {
            SqliteStorage storage = new SqliteStorage(tmp.resolve("db.sqlite"));

            // 外部系统是同一个，进程重启后仍然存在
            FakeRcsAdapter fake = new FakeRcsAdapter(FakeRcsAdapter.Mode.TIMEOUT_BUT_CREATED);

            Registry r1 = new Registry(storage, fake);
            r1.registerPoint("P01", "原料暂存点");
            r1.registerPoint("P02", "装配线接收点");
            r1.registerApplication("APP-001", "P01", "P02");
            r1.dispatchApplication("APP-001");

            // 模拟进程重启：新建 Registry，但 Fake 是同一个
            Registry r2 = new Registry(storage, fake);
            check(r2.findApplication("APP-001").orElseThrow().state() == ApplicationState.UNKNOWN,
                    "R63 UNKNOWN persisted");
            check(r2.listOutbox().size() == 1, "R63 outbox persisted");
            check(r2.listOutbox().get(0).status() == OutboxStatus.UNCERTAIN,
                    "R63 outbox UNCERTAIN");

            int processed = r2.processOutbox();
            check(processed == 1, "R63 process outbox");
            check(r2.findApplication("APP-001").orElseThrow().state() == ApplicationState.DISPATCHED,
                    "R63 recovered to DISPATCHED");
        } finally {
            deleteRecursively(tmp);
        }
    }

    private static void r64() throws Exception {
        java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("ids-sqlite-r64");
        try {
            SqliteStorage storage = new SqliteStorage(tmp.resolve("db.sqlite"));

            Registry r1 = new Registry(storage, new FakeRcsAdapter(FakeRcsAdapter.Mode.ACCEPT));
            r1.registerPoint("P01", "原料暂存点");
            r1.registerPoint("P02", "装配线接收点");
            r1.registerApplication("APP-001", "P01", "P02");
            r1.dispatchApplication("APP-001");
            r1.receiveCallback("APP-001", "STARTED");

            Registry r2 = new Registry(storage, new FakeRcsAdapter(FakeRcsAdapter.Mode.ACCEPT));
            check(r2.listInbox().size() == 1, "R64 inbox persisted");
            check(r2.listInbox().get(0).status() == InboxStatus.PROCESSED, "R64 inbox PROCESSED");
        } finally {
            deleteRecursively(tmp);
        }
    }

    private static void r65() throws Exception {
        java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("ids-sqlite-r65");
        try {
            SqliteStorage storage = new SqliteStorage(tmp.resolve("db.sqlite"));

            Registry r1 = new Registry(storage, new FakeRcsAdapter(FakeRcsAdapter.Mode.ACCEPT));
            r1.registerPoint("P01", "原料暂存点");
            r1.registerPoint("P02", "装配线接收点");
            r1.registerApplication("APP-001", "P01", "P02");
            r1.startWorkflow("APP-001");
            r1.advanceWorkflow("APP-001");

            Registry r2 = new Registry(storage, new FakeRcsAdapter(FakeRcsAdapter.Mode.ACCEPT));
            check(r2.listWorkflows().size() == 1, "R65 workflow persisted");
            WorkflowInstance wf = r2.findWorkflow("APP-001").orElseThrow();
            check(wf.state() == WorkflowState.RUNNING, "R65 RUNNING");
            check(wf.currentStepIndex() == 1, "R65 index 1");
            check(wf.steps().get(0).state() == WorkflowStepState.COMPLETED, "R65 step0 done");
        } finally {
            deleteRecursively(tmp);
        }
    }
    private static void r66() {
        Registry r = new Registry(new MemoryStorage(), new FakeRcsAdapter(FakeRcsAdapter.Mode.ACCEPT));
        Stats s = r.stats();
        check(s.points() == 0, "R66 empty points");
        check(s.applications() == 0, "R66 empty applications");
        check(s.outbox() == 0, "R66 empty outbox");
        check(s.inbox() == 0, "R66 empty inbox");
        check(s.workflows() == 0, "R66 empty workflows");
    }

    private static void r67() {
        Registry r = new Registry(new MemoryStorage(), new FakeRcsAdapter(FakeRcsAdapter.Mode.ACCEPT));
        r.registerPoint("P01", "原料暂存点");
        r.registerPoint("P02", "装配线接收点");
        r.registerApplication("APP-001", "P01", "P02");
        r.registerApplication("APP-002", "P01", "P02");

        Stats s = r.stats();
        check(s.points() == 2, "R67 points 2");
        check(s.applications() == 2, "R67 applications 2");
        check(s.applicationsByState().get("CREATED") == 2, "R67 CREATED 2");
    }

    private static void r68() {
        Registry r = new Registry(new MemoryStorage(), new FakeRcsAdapter(FakeRcsAdapter.Mode.ACCEPT));
        r.registerPoint("P01", "原料暂存点");
        r.registerPoint("P02", "装配线接收点");
        r.registerApplication("APP-001", "P01", "P02");
        r.registerApplication("APP-002", "P01", "P02");
        r.registerApplication("APP-003", "P01", "P02");
        r.registerApplication("APP-004", "P01", "P02");
        r.registerApplication("APP-005", "P01", "P02");

        r.dispatchApplication("APP-001");
        r.dispatchApplication("APP-002");
        r.executeApplication("APP-002");
        r.completeApplication("APP-002");
        r.cancelApplication("APP-003");

        Stats s = r.stats();
        check(s.applications() == 5, "R68 applications 5");
        check(s.applicationsByState().get("CANCELLED") == 1, "R68 CANCELLED 1");
        check(s.applicationsByState().get("COMPLETED") == 1, "R68 COMPLETED 1");
        check(s.applicationsByState().get("CREATED") == 2, "R68 CREATED 2");
        check(s.applicationsByState().get("DISPATCHED") == 1, "R68 DISPATCHED 1");
        check(s.applicationsByState().get("EXECUTING") == 0, "R68 EXECUTING 0");
        check(s.applicationsByState().get("UNKNOWN") == 0, "R68 UNKNOWN 0");
        check(s.outbox() == 2, "R68 outbox 2");
        check(s.outboxByStatus().get("SENT") == 2, "R68 outbox SENT 2");
    }

    private static void r69() {
        Registry r = new Registry(new MemoryStorage(), new FakeRcsAdapter(FakeRcsAdapter.Mode.TIMEOUT_BUT_CREATED));
        r.registerPoint("P01", "原料暂存点");
        r.registerPoint("P02", "装配线接收点");
        r.registerApplication("APP-001", "P01", "P02");
        r.dispatchApplication("APP-001");

        Stats s = r.stats();
        check(s.applicationsByState().get("UNKNOWN") == 1, "R69 UNKNOWN 1");
        check(s.outbox() == 1, "R69 outbox 1");
        check(s.outboxByStatus().get("UNCERTAIN") == 1, "R69 outbox UNCERTAIN 1");
    }

    private static void r70() {
        Registry r = new Registry(new MemoryStorage(), new FakeRcsAdapter(FakeRcsAdapter.Mode.ACCEPT));
        r.registerPoint("P01", "原料暂存点");
        r.registerPoint("P02", "装配线接收点");
        r.registerApplication("APP-001", "P01", "P02");
        r.dispatchApplication("APP-001");
        r.receiveCallback("APP-001", "STARTED");
        r.startWorkflow("APP-001");
        r.advanceWorkflow("APP-001");

        Stats s = r.stats();
        check(s.inbox() == 1, "R70 inbox 1");
        check(s.inboxByStatus().get("PROCESSED") == 1, "R70 inbox PROCESSED 1");
        check(s.workflows() == 1, "R70 workflows 1");
        check(s.workflowsByState().get("RUNNING") == 1, "R70 workflow RUNNING 1");
    }
    private static void r71() {
        Registry r = new Registry(new MemoryStorage(), new FakeRcsAdapter(FakeRcsAdapter.Mode.ACCEPT));
        HealthReport h = r.health();
        check("OK".equals(h.storageStatus()), "R71 storage OK");
        check(h.points() == 0, "R71 empty points");
        check(h.applications() == 0, "R71 empty applications");
        check(h.unknownApplications() == 0, "R71 unknown 0");
        check(h.outboxTotal() == 0, "R71 outbox 0");
        check(h.inboxTotal() == 0, "R71 inbox 0");
        check(h.workflowsTotal() == 0, "R71 workflows 0");
        check("OK".equals(h.overall()), "R71 overall OK");
    }

    private static void r72() {
        Registry r = new Registry(new MemoryStorage(), new FakeRcsAdapter(FakeRcsAdapter.Mode.ACCEPT));
        r.registerPoint("P01", "原料暂存点");
        r.registerPoint("P02", "装配线接收点");
        r.registerApplication("APP-001", "P01", "P02");
        r.dispatchApplication("APP-001");

        HealthReport h = r.health();
        check(h.points() == 2, "R72 points 2");
        check(h.applications() == 1, "R72 applications 1");
        check(h.unknownApplications() == 0, "R72 unknown 0");
        check(h.outboxTotal() == 1, "R72 outbox total 1");
        check(h.outboxPending() == 0, "R72 outbox pending 0");
        check(h.outboxFailed() == 0, "R72 outbox failed 0");
        check("OK".equals(h.overall()), "R72 overall OK");
    }

    private static void r73() {
        Registry r = new Registry(new MemoryStorage(), new FakeRcsAdapter(FakeRcsAdapter.Mode.TIMEOUT_BUT_CREATED));
        r.registerPoint("P01", "原料暂存点");
        r.registerPoint("P02", "装配线接收点");
        r.registerApplication("APP-001", "P01", "P02");
        r.dispatchApplication("APP-001");

        HealthReport h = r.health();
        check(h.applications() == 1, "R73 applications 1");
        check(h.unknownApplications() == 1, "R73 unknown 1");
        check(h.outboxTotal() == 1, "R73 outbox total 1");
check(h.outboxPending() == 0, "R73 outbox pending 0");
check(h.outboxUncertain() == 1, "R73 outbox uncertain 1");
        check("WARN".equals(h.overall()), "R73 overall WARN");
    }

    private static void r74() {
        Registry r = new Registry(new MemoryStorage(), new FakeRcsAdapter(FakeRcsAdapter.Mode.REJECT));
        r.registerPoint("P01", "原料暂存点");
        r.registerPoint("P02", "装配线接收点");
        r.registerApplication("APP-001", "P01", "P02");
        try {
            r.dispatchApplication("APP-001");
        } catch (RegistryException ignored) {
        }

        HealthReport h = r.health();
        check(h.outboxTotal() == 1, "R74 outbox total 1");
        check(h.outboxFailed() == 1, "R74 outbox failed 1");
        check(h.outboxPending() == 0, "R74 outbox pending 0");
        check("WARN".equals(h.overall()), "R74 overall WARN");
    }

    private static void r75() {
        Registry r = new Registry(new MemoryStorage(), new FakeRcsAdapter(FakeRcsAdapter.Mode.ACCEPT));
        r.registerPoint("P01", "原料暂存点");
        r.registerPoint("P02", "装配线接收点");
        r.registerApplication("APP-001", "P01", "P02");
        r.dispatchApplication("APP-001");
        r.receiveCallback("APP-001", "STARTED");
        r.startWorkflow("APP-001");
        r.advanceWorkflow("APP-001");

        HealthReport h = r.health();
        check(h.inboxTotal() == 1, "R75 inbox total 1");
        check(h.inboxPending() == 0, "R75 inbox pending 0");
        check(h.workflowsTotal() == 1, "R75 workflows total 1");
        check(h.workflowsRunning() == 1, "R75 workflows running 1");
        check("OK".equals(h.overall()), "R75 overall OK");
    }
    private static void r76() {
        Registry r = new Registry(new MemoryStorage(),
                new FakeRcsAdapter(FakeRcsAdapter.Mode.ACCEPT), true);

        expectError(() -> r.registerPoint("P01", "原料暂存点"),
                "READ_ONLY", "", "R76 registerPoint rejected");

        check(r.listPoints().isEmpty(), "R76 points empty");
    }

    private static void r77() {
        Registry r = new Registry(new MemoryStorage(),
                new FakeRcsAdapter(FakeRcsAdapter.Mode.ACCEPT), true);

        expectError(() -> r.registerApplication("APP-001", "P01", "P02"),
                "READ_ONLY", "", "R77 registerApplication rejected");

        expectError(() -> r.dispatchApplication("APP-001"),
                "READ_ONLY", "", "R77 dispatch rejected");

        expectError(() -> r.receiveCallback("APP-001", "STARTED"),
                "READ_ONLY", "", "R77 callback rejected");

        check(r.listApplications().isEmpty(), "R77 applications empty");
    }

    private static void r78() {
        Registry r = new Registry(new MemoryStorage(),
                new FakeRcsAdapter(FakeRcsAdapter.Mode.ACCEPT), true);

        expectError(() -> r.processOutbox(),
                "READ_ONLY", "", "R78 processOutbox rejected");

        expectError(() -> r.startWorkflow("APP-001"),
                "READ_ONLY", "", "R78 startWorkflow rejected");

        expectError(() -> r.advanceWorkflow("APP-001"),
                "READ_ONLY", "", "R78 advanceWorkflow rejected");

        check(r.listOutbox().isEmpty(), "R78 outbox empty");
        check(r.listWorkflows().isEmpty(), "R78 workflows empty");
    }

    private static void r79() {
        Registry r = new Registry(new MemoryStorage(),
                new FakeRcsAdapter(FakeRcsAdapter.Mode.ACCEPT), true);

        check(r.listPoints().isEmpty(), "R79 listPoints works");
        check(r.listApplications().isEmpty(), "R79 listApplications works");
        check(r.listOutbox().isEmpty(), "R79 listOutbox works");
        check(r.listInbox().isEmpty(), "R79 listInbox works");
        check(r.listWorkflows().isEmpty(), "R79 listWorkflows works");

        Stats s = r.stats();
        check(s.points() == 0, "R79 stats works");

        HealthReport h = r.health();
        check("READ_ONLY".equals(h.mode()), "R79 health mode READ_ONLY");
        check("OK".equals(h.overall()), "R79 health overall OK");
    }

    private static void r80() {
        Registry r = new Registry(new MemoryStorage(),
                new FakeRcsAdapter(FakeRcsAdapter.Mode.ACCEPT), false);

        HealthReport h = r.health();
        check("READ_WRITE".equals(h.mode()), "R80 health mode READ_WRITE");

        r.registerPoint("P01", "原料暂存点");
        check(r.listPoints().size() == 1, "R80 write allowed in READ_WRITE");
    }
    private static void r81() throws Exception {
        java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("ids-migration-r81");
        try {
            SqliteStorage s = new SqliteStorage(tmp.resolve("db.sqlite"));
            check(s.currentVersion() == 2, "R81 fresh db version 2");
        } finally {
            deleteRecursively(tmp);
        }
    }

    private static void r82() throws Exception {
        java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("ids-migration-r82");
        try {
            java.nio.file.Path db = tmp.resolve("db.sqlite");

            SqliteStorage s1 = new SqliteStorage(db);
            check(s1.currentVersion() == 2, "R82 first open version 2");

            SqliteStorage s2 = new SqliteStorage(db);
            check(s2.currentVersion() == 2, "R82 second open still version 2");
        } finally {
            deleteRecursively(tmp);
        }
    }

    private static void r83() throws Exception {
        java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("ids-migration-r83");
        try {
            java.nio.file.Path db = tmp.resolve("db.sqlite");

            SqliteStorage s1 = new SqliteStorage(db);
            Registry r1 = new Registry(s1, new FakeRcsAdapter(FakeRcsAdapter.Mode.ACCEPT));
            r1.registerPoint("P01", "原料暂存点");

            SqliteStorage s2 = new SqliteStorage(db);
            Registry r2 = new Registry(s2, new FakeRcsAdapter(FakeRcsAdapter.Mode.ACCEPT));
            check(r2.listPoints().size() == 1, "R83 data preserved after reopen");
            check(s2.currentVersion() == 2, "R83 version still 2");
        } finally {
            deleteRecursively(tmp);
        }
    }

    private static void r84() {
        Registry r = new Registry(new MemoryStorage(),
                new FakeRcsAdapter(FakeRcsAdapter.Mode.ACCEPT));
        check(r.migrationVersion() == -1, "R84 memory storage unsupported");

        java.util.List<Migration> list = r.migrationList();
        check(list.size() == 2, "R84 migration list size 2");
        check(list.get(0).version() == 1, "R84 first version 1");
        check(list.get(1).version() == 2, "R84 second version 2");
    }

    private static void r85() throws Exception {
        java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("ids-migration-r85");
        try {
            java.nio.file.Path db = tmp.resolve("db.sqlite");
            SqliteStorage s = new SqliteStorage(db);
            check(s.currentVersion() == 2, "R85 version 2");

            Registry r = new Registry(s, new FakeRcsAdapter(FakeRcsAdapter.Mode.ACCEPT));
            r.registerPoint("P01", "原料暂存点");
            r.registerPoint("P02", "装配线接收点");
            r.registerApplication("APP-001", "P01", "P02");

            SqliteStorage s2 = new SqliteStorage(db);
            Registry r2 = new Registry(s2, new FakeRcsAdapter(FakeRcsAdapter.Mode.ACCEPT));
            check(r2.listPoints().size() == 2, "R85 points preserved");
            check(r2.listApplications().size() == 1, "R85 applications preserved");
        } finally {
            deleteRecursively(tmp);
        }
    }

    private static void deleteRecursively(java.nio.file.Path path) throws Exception {
        if (!java.nio.file.Files.exists(path)) {
            return;
        }
        java.nio.file.Files.walk(path)
                .sorted((a, b) -> b.compareTo(a))
                .forEach(p -> {
                    try {
                        java.nio.file.Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                    }
                });
    }
    // ---------- helpers ----------

    private static void check(boolean condition, String name) {
        if (!condition) {
            throw new AssertionError("FAILED: " + name);
        }
        passed++;
    }

    private static void expectError(Runnable action, String code, String detail, String name) {
        try {
            action.run();
            throw new AssertionError("FAILED: " + name + " (no exception)");
        } catch (RegistryException e) {
            if (!e.code().equals(code)) {
                throw new AssertionError("FAILED: " + name + " code=" + e.code() + " expected=" + code);
            }
            if (detail != null && !e.detail().equals(detail)) {
                throw new AssertionError("FAILED: " + name + " detail=" + e.detail() + " expected=" + detail);
            }
        }
        passed++;
    }

    private static void expectUnsupported(Runnable action, String name) {
        try {
            action.run();
            throw new AssertionError("FAILED: " + name + " (no exception)");
        } catch (UnsupportedOperationException e) {
            // expected
        }
        passed++;
    }
}
