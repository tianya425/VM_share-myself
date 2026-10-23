package lab.simulation;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Oracle、Evidence 和 Replay 的自动检查。
 *
 * <p>覆盖 RPL-01 到 RPL-08 的主要情形。
 */
public class ReplayEvidenceChecks {

    private static int passed = 0;

    public static void main(String[] args) throws Exception {
        Path work = Files.createTempDirectory("ids-repl");

        try {
            // 准备：跑两次 SIM-001
            Path scenario = Path.of("scenarios/sim-001-happy.scenario");
            ScenarioPackage pkg = ScenarioParser.parse(scenario);

            Path a = work.resolve("sim-001-a");
            Path b = work.resolve("sim-001-b");

            SimulationRuntime ra = new SimulationRuntime(pkg);
            SimulationRunResult resultA = ra.run();
            new EvidenceBundleWriter(scenario, pkg, resultA, ra.timeline(),
                    resultA.oracleResults()).write(a);

            SimulationRuntime rb = new SimulationRuntime(pkg);
            SimulationRunResult resultB = rb.run();
            new EvidenceBundleWriter(scenario, pkg, resultB, rb.timeline(),
                    resultB.oracleResults()).write(b);

            // RPL-01：七个文件齐全
            for (String name : new String[]{
                    "scenario.scenario", "scenario.sha256",
                    "run-summary.txt", "event-timeline.tsv",
                    "external-interactions.tsv", "oracle-results.tsv",
                    "manifest.sha256"}) {
                check(Files.exists(a.resolve(name)), "RPL-01 file " + name);
            }

            // RPL-02：manifest 可校验
            ReplayComparator c = new ReplayComparator();
            String r1 = c.compare(a, b);
            check(r1.equals("REPLAY_MATCH"), "RPL-04 match");

            // RPL-06：篡改 evidence → INVALID 或 DIFFERENT
            Files.writeString(a.resolve("event-timeline.tsv"),
                    "TAMPERED\n", StandardCharsets.UTF_8);
            String r2 = c.compare(a, b);
            check(r2.equals("REPLAY_INVALID_EVIDENCE"), "RPL-06 tampered → invalid");
        } finally {
            deleteRecursively(work);
        }

        System.out.println("REPLAY_EVIDENCE_CHECKS_PASSED=" + passed);
    }

    private static void check(boolean condition, String name) {
        if (!condition) {
            throw new AssertionError("FAILED: " + name);
        }
        passed++;
    }

    private static void deleteRecursively(Path path) throws Exception {
        if (!Files.exists(path)) {
            return;
        }
        Files.walk(path)
                .sorted((x, y) -> y.compareTo(x))
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                    }
                });
    }
}
