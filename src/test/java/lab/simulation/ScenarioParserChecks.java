package lab.simulation;

import java.nio.file.Path;
import java.util.List;

/**
 * ScenarioParser 的自动检查。
 *
 * <p>覆盖 SCN-01 到 SCN-16 的主要情形。
 */
public class ScenarioParserChecks {

    private static int passed = 0;

    public static void main(String[] args) {
        Path valid = Path.of("scenarios/sim-001-happy.scenario");
        Path invalidBackward = Path.of("scenarios/invalid/invalid-backward-time.scenario");
        Path invalidDuplicate = Path.of("scenarios/invalid/invalid-duplicate-event.scenario");

        // SCN-01：合法场景字段正确
        ScenarioPackage pkg = ScenarioParser.parse(valid);
        check(pkg.scenarioId().equals("SIM-001"), "SCN-01 scenarioId");
        check(pkg.version() == 1, "SCN-01 version");
        check(pkg.events().size() == 6, "SCN-01 eventCount");
        check(pkg.externalScripts().size() == 1, "SCN-01 externalScripts");
        check(pkg.oracles().size() == 5, "SCN-01 oracles");

        // SCN-02：digest 格式 64 位小写十六进制
        check(pkg.digest() != null && pkg.digest().matches("[0-9a-f]{64}"), "SCN-02 digest format");

        // SCN-03：List 不可修改
        expectUnsupported(() -> pkg.events().add(null), "SCN-03 events immutable");
        expectUnsupported(() -> pkg.externalScripts().add(null), "SCN-03 scripts immutable");
        expectUnsupported(() -> pkg.oracles().add(null), "SCN-03 oracles immutable");

        // SCN-04：中文名称正确保留
        ScenarioEvent evt1 = pkg.events().get(0);
        check(evt1.arguments().get(1).equals("原料暂存点"), "SCN-04 chinese name");

        // SCN-11：时间倒退
        expectError(() -> ScenarioParser.parse(invalidBackward),
                "SCENARIO_EVENT_ORDER", "EVT-002", "SCN-11 backward time");

        // SCN-10：重复 eventId
        expectError(() -> ScenarioParser.parse(invalidDuplicate),
                "SCENARIO_DUPLICATE_EVENT", "EVT-001", "SCN-10 duplicate event");

        // SCN-12：相同 offset + sequence
        Path tmp = null;
        try {
            tmp = java.nio.file.Files.createTempFile("scn12-", ".scenario");
            java.nio.file.Files.writeString(tmp, """
                SCENARIO SIM-SCN-12 1
                START_AT 2026-01-01T00:00:00Z
                SEED 9001
                AT 0 10 EVT-001 POINT_ADD P01 原料暂存点
                AT 0 10 EVT-002 POINT_ADD P02 装配线接收点
                END
                """);
            Path finalTmp = tmp;
            expectError(() -> ScenarioParser.parse(finalTmp),
                    "SCENARIO_EVENT_ORDER", "0/10", "SCN-12 same offset+sequence");
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (tmp != null) {
                try { java.nio.file.Files.deleteIfExists(tmp); } catch (Exception ignored) {}
            }
        }

        // SCN-15：参数数量错误
        try {
            tmp = java.nio.file.Files.createTempFile("scn15-", ".scenario");
            java.nio.file.Files.writeString(tmp, """
                SCENARIO SIM-SCN-15 1
                START_AT 2026-01-01T00:00:00Z
                SEED 9001
                AT 0 10 EVT-001 POINT_ADD P01
                END
                """);
            Path finalTmp = tmp;
            expectError(() -> ScenarioParser.parse(finalTmp),
                    "SCENARIO_ARGUMENT_COUNT", "POINT_ADD", "SCN-15 action args");
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (tmp != null) {
                try { java.nio.file.Files.deleteIfExists(tmp); } catch (Exception ignored) {}
            }
        }

        // SCN-16：END 缺失
        try {
            tmp = java.nio.file.Files.createTempFile("scn16-", ".scenario");
            java.nio.file.Files.writeString(tmp, """
                SCENARIO SIM-SCN-16 1
                START_AT 2026-01-01T00:00:00Z
                SEED 9001
                AT 0 10 EVT-001 POINT_ADD P01 原料暂存点
                """);
            Path finalTmp = tmp;
            expectError(() -> ScenarioParser.parse(finalTmp),
                    "SCENARIO_END_MISSING", "", "SCN-16 no END");
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (tmp != null) {
                try { java.nio.file.Files.deleteIfExists(tmp); } catch (Exception ignored) {}
            }
        }

        System.out.println("SCENARIO_PARSER_CHECKS_PASSED=" + passed);
    }

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
        } catch (ScenarioValidationException e) {
            if (!e.code().equals(code)) {
                throw new AssertionError("FAILED: " + name + " code=" + e.code() + " expected=" + code);
            }
            if (!detail.isEmpty() && !e.detail().equals(detail)) {
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
