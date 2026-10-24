package lab.simulation;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * 仿真入口。
 *
 * <p>支持三个子命令：
 * <ul>
 *   <li>validate &lt;scenarioFile&gt;             只解析和校验</li>
 *   <li>run &lt;scenarioFile&gt; &lt;evidenceDir&gt;     执行场景并写入证据</li>
 *   <li>compare &lt;dirA&gt; &lt;dirB&gt;                 比较两次运行</li>
 * </ul>
 *
 * <p>统一退出码：
 * <ul>
 *   <li>0 — validate 成功 / run 通过</li>
 *   <li>1 — run 失败（事件 / Oracle / 未消费脚本）</li>
 *   <li>2 — validate 非法或用法错误</li>
 *   <li>3 — 证据无法发布或内部错误</li>
 * </ul>
 *
 * <p>stdout 只放稳定结果，禁止输出堆栈。
 */
public class SimulationMain {

    /**
     * 仿真入口。
     */
    public static void main(String[] args) {
        PrintWriter out = new PrintWriter(System.out, true, StandardCharsets.UTF_8);

        if (args.length < 2) {
            out.println("ERR SIMULATION_USAGE");
            System.exit(2);
            return;
        }

        switch (args[0]) {
            case "validate" -> {
                if (args.length != 2) {
                    out.println("ERR SIMULATION_USAGE");
                    System.exit(2);
                    return;
                }
                validate(out, Path.of(args[1]));
            }
            case "run" -> {
                if (args.length != 3) {
                    out.println("ERR SIMULATION_USAGE");
                    System.exit(2);
                    return;
                }
                run(out, Path.of(args[1]), Path.of(args[2]));
            }
            case "compare" -> {
                if (args.length != 3) {
                    out.println("ERR SIMULATION_USAGE");
                    System.exit(2);
                    return;
                }
                compare(out, Path.of(args[1]), Path.of(args[2]));
            }
            default -> {
                out.println("ERR SIMULATION_USAGE");
                System.exit(2);
            }
        }
    }

    private static void validate(PrintWriter out, Path file) {
        ScenarioPackage pkg;
        try {
            pkg = ScenarioParser.parse(file);
        } catch (ScenarioValidationException e) {
            out.println("ERR " + e.code() + (e.detail().isEmpty() ? "" : " " + e.detail()));
            System.exit(2);
            return;
        }
        out.println("SCENARIO_VALID "
                + pkg.scenarioId()
                + " version=" + pkg.version()
                + " events=" + pkg.events().size()
                + " externalScripts=" + pkg.externalScripts().size()
                + " oracles=" + pkg.oracles().size());
    }

    private static void run(PrintWriter out, Path file, Path evidenceDir) {
        ScenarioPackage pkg;
        try {
            pkg = ScenarioParser.parse(file);
        } catch (ScenarioValidationException e) {
            out.println("ERR " + e.code() + (e.detail().isEmpty() ? "" : " " + e.detail()));
            System.exit(2);
            return;
        }

        SimulationRuntime runtime = new SimulationRuntime(pkg);
        SimulationRunResult result;
        try {
            result = runtime.run();
        } catch (RuntimeException e) {
            out.println("RUN_INCONCLUSIVE " + pkg.scenarioId() + " INTERNAL_ERROR");
            System.exit(3);
            return;
        }

        try {
            new EvidenceBundleWriter(file, pkg, result, runtime.timeline(), result.oracleResults())
                    .write(evidenceDir);
        } catch (IOException e) {
            out.println("RUN_INCONCLUSIVE " + result.scenarioId() + " EVIDENCE_WRITE_FAILED");
            System.exit(3);
            return;
        } catch (EvidenceValidationException e) {
            out.println("RUN_INCONCLUSIVE " + result.scenarioId() + " " + e.code());
            System.exit(3);
            return;
        }

        if (SimulationRunResult.COMPLETED.equals(result.runStatus())) {
            out.println("RUN_COMPLETED " + result.scenarioId()
                    + " version=" + result.version()
                    + " planned=" + result.plannedCount()
                    + " pass=" + result.passCount()
                    + " fail=" + result.failCount()
                    + " skipped=" + result.skippedCount()
                    + " oraclePass=" + result.oraclePassCount()
                    + " oracleFail=" + result.oracleFailCount()
                    + " creates=" + result.createCallCount()
                    + " queries=" + result.queryCallCount()
                    + " externalOrders=" + result.validExternalOrderCount());
            out.println("EVIDENCE_WRITTEN " + evidenceDir);
            System.exit(0);
        } else if (SimulationRunResult.FAILED.equals(result.runStatus())) {
            out.println("RUN_FAILED " + result.scenarioId() + " " + result.firstFailureCode());
            out.println("EVIDENCE_WRITTEN " + evidenceDir);
            System.exit(1);
        } else {
            out.println("RUN_INCONCLUSIVE " + result.scenarioId() + " " + result.firstFailureCode());
            System.exit(3);
        }
    }

    private static void compare(PrintWriter out, Path dirA, Path dirB) {
        ReplayComparator comparator = new ReplayComparator();
        ReplayResult result = comparator.compare(dirA, dirB);
        out.println(result.toLine());
        System.exit(result.exitCode());
    }
}
