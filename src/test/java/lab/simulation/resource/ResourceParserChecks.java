package lab.simulation.resource;

import lab.simulation.OracleRule;
import lab.simulation.ScenarioEvent;
import lab.simulation.ScenarioPackage;
import lab.simulation.ScenarioParser;
import lab.simulation.ScenarioValidationException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * PR4 / T4：Parser 对 8 个资源 Action 与 8 个资源 EXPECT 的 parse 期校验：
 * RPT-01 — RPT-07。
 *
 * <p>输出 {@code RESOURCE_PARSER_CASES_PASSED=7}。只调用
 * {@link ScenarioParser#parse(Path)}，不依赖运行时（运行时由 PR5/PR6 接线）。
 */
public final class ResourceParserChecks {

    private static final int CASE_TOTAL = 7;

    private static int passed = 0;
    private static int failed = 0;
    private static Path tmpDir;

    private ResourceParserChecks() {
    }

    public static void main(String[] args) {
        try {
            try {
                tmpDir = Files.createTempDirectory("resource-parser-checks-");
            } catch (Exception e) {
                throw new IllegalStateException("无法创建临时目录", e);
            }
            run("RPT-01", "8 个资源 Action 全部可解析，参数原样保留", ResourceParserChecks::rpt01);
            run("RPT-02", "8 个资源 EXPECT 全部可解析，参数数量与枚举取值合法", ResourceParserChecks::rpt02);
            run("RPT-03", "Action 参数数量错误 → SCENARIO_ARGUMENT_COUNT", ResourceParserChecks::rpt03);
            run("RPT-04", "RESOURCE_SET 枚举非法 → SCENARIO_ARGUMENT_VALUE", ResourceParserChecks::rpt04);
            run("RPT-05", "RESERVATION_STATE 枚举非法 → SCENARIO_ARGUMENT_VALUE", ResourceParserChecks::rpt05);
            run("RPT-06", "HELD_COUNT / WAITING_COUNT 必须是非负整数 → SCENARIO_ARGUMENT_COUNT", ResourceParserChecks::rpt06);
            run("RPT-07", "QUEUE_ORDER 至少 2 个参数；未知 oracle 仍 SCENARIO_UNKNOWN_ORACLE", ResourceParserChecks::rpt07);
        } finally {
            deleteRecursively(tmpDir);
        }

        if (passed + failed != CASE_TOTAL) {
            throw new AssertionError("case 数量不符：passed+failed=" + (passed + failed)
                    + " 期望=" + CASE_TOTAL);
        }
        if (failed > 0) {
            System.out.println("RESOURCE_PARSER_CASES_FAILED=" + failed);
            System.out.println("RESOURCE_PARSER_CASES_PASSED=" + passed);
            System.exit(1);
        }
        System.out.println("RESOURCE_PARSER_CASES_PASSED=" + passed);
    }

    // ================= RPT-01 =================

    private static void rpt01() {
        ScenarioPackage pkg = ScenarioParser.parse(write("rpt01.scenario", """
                SCENARIO SIM-B01 1
                START_AT 2026-01-01T00:00:00Z
                SEED 1
                AT 0 10 EVT-001 DESTINATION_ADD D01 一号目的地
                AT 0 11 EVT-002 SERVICE_POINT_ADD SP01 D01 P01 一号服务点
                AT 0 12 EVT-003 VIRTUAL_RESOURCE_ADD R01 SP01
                AT 0 13 EVT-004 RESOURCE_SET R01 DISABLED
                AT 0 14 EVT-005 RESERVATION_REQUEST APP-001 D01 SP01 R01
                AT 0 15 EVT-006 RESERVATION_RELEASE APP-001
                AT 0 16 EVT-007 RESERVATION_CANCEL APP-001
                AT 0 17 EVT-008 WORLD_RESTART
                END
                """));
        require(pkg.events().size() == 8, "events=" + pkg.events().size() + " 期望=8");
        List<ScenarioEvent.Action> expected = List.of(
                ScenarioEvent.Action.DESTINATION_ADD,
                ScenarioEvent.Action.SERVICE_POINT_ADD,
                ScenarioEvent.Action.VIRTUAL_RESOURCE_ADD,
                ScenarioEvent.Action.RESOURCE_SET,
                ScenarioEvent.Action.RESERVATION_REQUEST,
                ScenarioEvent.Action.RESERVATION_RELEASE,
                ScenarioEvent.Action.RESERVATION_CANCEL,
                ScenarioEvent.Action.WORLD_RESTART);
        for (int i = 0; i < expected.size(); i++) {
            require(pkg.events().get(i).action() == expected.get(i),
                    "第 " + (i + 1) + " 个 action=" + pkg.events().get(i).action()
                            + " 期望=" + expected.get(i));
        }
        require(pkg.events().get(1).arguments().equals(List.of("SP01", "D01", "P01", "一号服务点")),
                "SERVICE_POINT_ADD 参数=" + pkg.events().get(1).arguments());
        require(pkg.events().get(4).arguments().equals(List.of("APP-001", "D01", "SP01", "R01")),
                "RESERVATION_REQUEST 参数=" + pkg.events().get(4).arguments());
    }

    // ================= RPT-02 =================

    private static void rpt02() {
        ScenarioPackage pkg = ScenarioParser.parse(write("rpt02.scenario", """
                SCENARIO SIM-B02 1
                START_AT 2026-01-01T00:00:00Z
                SEED 2
                AT 0 10 EVT-001 WORLD_RESTART
                EXPECT ORC-001 RESERVATION_STATE APP-001 HELD
                EXPECT ORC-002 HELD_COUNT R01 1
                EXPECT ORC-003 WAITING_COUNT R01 0
                EXPECT ORC-004 RESOURCE_AVAILABILITY R01 DISABLED
                EXPECT ORC-005 QUEUE_ORDER R01 APP-001 APP-002
                EXPECT ORC-006 QUEUE_EMPTY R01
                EXPECT ORC-007 WORLD_RESTART_CONSISTENT
                EXPECT ORC-008 SINGLE_CAPACITY R01
                END
                """));
        require(pkg.oracles().size() == 8, "oracles=" + pkg.oracles().size() + " 期望=8");
        List<OracleRule.RuleType> expected = List.of(
                OracleRule.RuleType.RESERVATION_STATE,
                OracleRule.RuleType.HELD_COUNT,
                OracleRule.RuleType.WAITING_COUNT,
                OracleRule.RuleType.RESOURCE_AVAILABILITY,
                OracleRule.RuleType.QUEUE_ORDER,
                OracleRule.RuleType.QUEUE_EMPTY,
                OracleRule.RuleType.WORLD_RESTART_CONSISTENT,
                OracleRule.RuleType.SINGLE_CAPACITY);
        for (int i = 0; i < expected.size(); i++) {
            require(pkg.oracles().get(i).ruleType() == expected.get(i),
                    "第 " + (i + 1) + " 个 ruleType=" + pkg.oracles().get(i).ruleType()
                            + " 期望=" + expected.get(i));
        }
        require(pkg.oracles().get(4).arguments().equals(List.of("R01", "APP-001", "APP-002")),
                "QUEUE_ORDER 参数=" + pkg.oracles().get(4).arguments());
        require(pkg.oracles().get(6).arguments().isEmpty(),
                "WORLD_RESTART_CONSISTENT 参数应为空：" + pkg.oracles().get(6).arguments());
    }

    // ================= RPT-03 =================

    private static void rpt03() {
        expectErr(write("rpt03.scenario", """
                SCENARIO SIM-B03 1
                START_AT 2026-01-01T00:00:00Z
                SEED 3
                AT 0 10 EVT-001 RESERVATION_REQUEST APP-001 D01 SP01
                END
                """), "SCENARIO_ARGUMENT_COUNT", "RESERVATION_REQUEST", "RESERVATION_REQUEST 少一个参数");
        expectErr(write("rpt03b.scenario", """
                SCENARIO SIM-B03 1
                START_AT 2026-01-01T00:00:00Z
                SEED 3
                AT 0 10 EVT-001 WORLD_RESTART EXTRA
                END
                """), "SCENARIO_ARGUMENT_COUNT", "WORLD_RESTART", "WORLD_RESTART 不接受参数");
    }

    // ================= RPT-04 =================

    private static void rpt04() {
        expectErr(write("rpt04.scenario", """
                SCENARIO SIM-B04 1
                START_AT 2026-01-01T00:00:00Z
                SEED 4
                AT 0 10 EVT-001 RESOURCE_SET R01 BROKEN
                END
                """), "SCENARIO_ARGUMENT_VALUE", "RESOURCE_SET=BROKEN", "RESOURCE_SET 枚举非法");
    }

    // ================= RPT-05 =================

    private static void rpt05() {
        expectErr(write("rpt05.scenario", """
                SCENARIO SIM-B05 1
                START_AT 2026-01-01T00:00:00Z
                SEED 5
                AT 0 10 EVT-001 WORLD_RESTART
                EXPECT ORC-001 RESERVATION_STATE APP-001 DONE
                END
                """), "SCENARIO_ARGUMENT_VALUE", "RESERVATION_STATE=DONE", "RESERVATION_STATE 枚举非法");
    }

    // ================= RPT-06 =================

    private static void rpt06() {
        expectErr(write("rpt06.scenario", """
                SCENARIO SIM-B06 1
                START_AT 2026-01-01T00:00:00Z
                SEED 6
                AT 0 10 EVT-001 WORLD_RESTART
                EXPECT ORC-001 HELD_COUNT R01 -1
                END
                """), "SCENARIO_ARGUMENT_COUNT", "HELD_COUNT", "HELD_COUNT 为负");
        expectErr(write("rpt06b.scenario", """
                SCENARIO SIM-B06 1
                START_AT 2026-01-01T00:00:00Z
                SEED 6
                AT 0 10 EVT-001 WORLD_RESTART
                EXPECT ORC-001 WAITING_COUNT R01 x
                END
                """), "SCENARIO_ARGUMENT_COUNT", "WAITING_COUNT", "WAITING_COUNT 非整数");
    }

    // ================= RPT-07 =================

    private static void rpt07() {
        expectErr(write("rpt07.scenario", """
                SCENARIO SIM-B07 1
                START_AT 2026-01-01T00:00:00Z
                SEED 7
                AT 0 10 EVT-001 WORLD_RESTART
                EXPECT ORC-001 QUEUE_ORDER R01
                END
                """), "SCENARIO_ARGUMENT_COUNT", "QUEUE_ORDER", "QUEUE_ORDER 只有一个参数");
        expectErr(write("rpt07b.scenario", """
                SCENARIO SIM-B07 1
                START_AT 2026-01-01T00:00:00Z
                SEED 7
                AT 0 10 EVT-001 WORLD_RESTART
                EXPECT ORC-001 SOMETHING R01 1
                END
                """), "SCENARIO_UNKNOWN_ORACLE", "SOMETHING", "未知 ruleType");
    }

    // ================= 基础设施 =================

    private static Path write(String fileName, String content) {
        try {
            Path file = tmpDir.resolve(fileName);
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return file;
        } catch (Exception e) {
            throw new IllegalStateException("无法写临时场景 " + fileName, e);
        }
    }

    private static void expectErr(Path file, String code, String detail, String label) {
        try {
            ScenarioParser.parse(file);
        } catch (ScenarioValidationException e) {
            require(e.code().equals(code), label + "：code=" + e.code() + " 期望=" + code);
            require(e.detail().equals(detail), label + "：detail=" + e.detail() + " 期望=" + detail);
            return;
        }
        throw new AssertionError(label + "：未抛出异常（期望 " + code + "）");
    }

    private interface CaseBody {
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
