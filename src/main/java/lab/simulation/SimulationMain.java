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
 */
public class SimulationMain {
    /**
     * 仿真入口。
     *
     * <p>支持三个子命令：
     * <ul>
     *   <li>validate &lt;scenarioFile&gt;</li>
     *   <li>run &lt;scenarioFile&gt; &lt;evidenceDir&gt;</li>
     *   <li>compare &lt;dirA&gt; &lt;dirB&gt;</li>
     * </ul>
     *
     * <p>用法错误或场景解析失败时以退出码 2 结束。
     */
    public static void main(String[] args) {
        PrintWriter out = new PrintWriter(System.out, true, StandardCharsets.UTF_8);

        if (args.length < 2) {
            out.println("ERR SIMULATION_USAGE");
            System.exit(2);
            return;
        }

        String command = args[0];

        switch (command) {
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
        try {
            ScenarioPackage pkg = ScenarioParser.parse(file);
            out.println("SCENARIO_VALID "
                    + pkg.scenarioId()
                    + " version=" + pkg.version()
                    + " events=" + pkg.events().size()
                    + " externalScripts=" + pkg.externalScripts().size()
                    + " oracles=" + pkg.oracles().size());
        } catch (ScenarioValidationException e) {
            out.println("ERR " + e.code()
                    + (e.detail().isEmpty() ? "" : " " + e.detail()));
            System.exit(2);
        }
    }

    private static void run(PrintWriter out, Path file, Path evidenceDir) {
        ScenarioPackage pkg;
        try {
            pkg = ScenarioParser.parse(file);
        } catch (ScenarioValidationException e) {
            out.println("ERR " + e.code()
                    + (e.detail().isEmpty() ? "" : " " + e.detail()));
            System.exit(2);
            return;
        }

        SimulationRuntime runtime = new SimulationRuntime(pkg);
        SimulationRunResult result = runtime.run();

        // 写证据
        try {
            EvidenceBundleWriter writer = new EvidenceBundleWriter(
                    file, pkg, result, runtime.timeline(), result.oracleResults());
            writer.write(evidenceDir);
        } catch (IOException e) {
            out.println("ERR EVIDENCE_WRITE_FAILED " + e.getMessage());
            System.exit(1);
            return;
        }

        out.println("RUN_" + result.runStatus()
                + " " + result.scenarioId()
                + " version=" + result.version()
                + " events=" + result.eventCount()
                + " oraclePass=" + result.oraclePassCount()
                + " oracleFail=" + result.oracleFailCount()
                + " creates=" + result.createCallCount()
                + " queries=" + result.queryCallCount()
                + " externalOrders=" + result.validExternalOrderCount());
        out.println("EVIDENCE_WRITTEN " + evidenceDir);
    }

    private static void compare(PrintWriter out, Path dirA, Path dirB) {
        ReplayComparator comparator = new ReplayComparator();
        String result = comparator.compare(dirA, dirB);
        out.println(result);
        if (!result.startsWith("REPLAY_MATCH")) {
            System.exit(1);
        }
    }
}
