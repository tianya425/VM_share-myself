package lab.simulation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 把一次仿真运行的证据写入目录。
 *
 * <p>生成 7 个文件：
 * <ul>
 *   <li>scenario.scenario       — 实际执行的场景原字节副本</li>
 *   <li>scenario.sha256         — 场景摘要</li>
 *   <li>run-summary.txt         — 结果汇总</li>
 *   <li>event-timeline.tsv      — 事件时间线</li>
 *   <li>external-interactions.tsv — 外部交互记录</li>
 *   <li>oracle-results.tsv      — Oracle 结果</li>
 *   <li>manifest.sha256         — 前述六个文件的 SHA-256 清单</li>
 * </ul>
 *
 * <p>所有文件使用 UTF-8、LF 换行，字段之间使用制表符。
 * manifest 不包含自身，条目按相对文件名自然排序。
 */

    /**
     * 创建证据写入器。
     *
     * @param scenarioFile  实际执行的场景文件路径（用于复制原始字节）
     * @param scenario      场景描述
     * @param result        一次运行的结果
     * @param timeline      事件时间线
     * @param oracleResults Oracle 检查结果
     */
public class EvidenceBundleWriter {

    private final Path scenarioFile;
    private final ScenarioPackage scenario;
    private final SimulationRunResult result;
    private final List<SimulationRuntime.EventRecord> timeline;
    private final List<OracleResult> oracleResults;

    public EvidenceBundleWriter(Path scenarioFile,
                                ScenarioPackage scenario,
                                SimulationRunResult result,
                                List<SimulationRuntime.EventRecord> timeline,
                                List<OracleResult> oracleResults) {
        this.scenarioFile = scenarioFile;
        this.scenario = scenario;
        this.result = result;
        this.timeline = List.copyOf(timeline);
        this.oracleResults = List.copyOf(oracleResults);
    }

    /**
     * 写入证据目录。
     */
    public void write(Path dir) throws IOException {
        Files.createDirectories(dir);

        // 1. scenario.scenario（原始字节副本）
        byte[] raw = Files.readAllBytes(scenarioFile);
        Files.write(dir.resolve("scenario.scenario"), raw);

        // 2. scenario.sha256
        Files.writeString(dir.resolve("scenario.sha256"),
                scenario.digest() + "  scenario.scenario\n",
                StandardCharsets.UTF_8);

        // 3. run-summary.txt
        Files.writeString(dir.resolve("run-summary.txt"),
                buildRunSummary(),
                StandardCharsets.UTF_8);

        // 4. event-timeline.tsv
        Files.writeString(dir.resolve("event-timeline.tsv"),
                buildEventTimeline(),
                StandardCharsets.UTF_8);

        // 5. external-interactions.tsv
        Files.writeString(dir.resolve("external-interactions.tsv"),
                buildExternalInteractions(),
                StandardCharsets.UTF_8);

        // 6. oracle-results.tsv
        Files.writeString(dir.resolve("oracle-results.tsv"),
                buildOracleResults(),
                StandardCharsets.UTF_8);

        // 7. manifest.sha256（不含自身）
        List<String> names = new ArrayList<>(List.of(
                "event-timeline.tsv",
                "external-interactions.tsv",
                "oracle-results.tsv",
                "run-summary.txt",
                "scenario.scenario",
                "scenario.sha256"));
        java.util.Collections.sort(names);

        StringBuilder manifest = new StringBuilder();
        for (String name : names) {
            byte[] bytes = Files.readAllBytes(dir.resolve(name));
            manifest.append(sha256(bytes)).append("  ").append(name).append("\n");
        }
        Files.writeString(dir.resolve("manifest.sha256"), manifest.toString(),
                StandardCharsets.UTF_8);
    }

    private String buildRunSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("scenarioId=").append(scenario.scenarioId()).append("\n");
        sb.append("scenarioVersion=").append(scenario.version()).append("\n");
        sb.append("scenarioDigest=").append(scenario.digest()).append("\n");
        sb.append("rootSeed=").append(scenario.rootSeed()).append("\n");
        sb.append("virtualStartAt=").append(scenario.startAt()).append("\n");
        sb.append("runStatus=").append(result.runStatus()).append("\n");
        sb.append("eventCount=").append(result.eventCount()).append("\n");
        sb.append("eventPassCount=").append(result.eventPassCount()).append("\n");
        sb.append("eventFailCount=").append(result.eventFailCount()).append("\n");
        sb.append("oraclePassCount=").append(countOracle("PASS")).append("\n");
        sb.append("oracleFailCount=").append(countOracle("FAIL")).append("\n");
        sb.append("createCallCount=").append(result.createCallCount()).append("\n");
        sb.append("queryCallCount=").append(result.queryCallCount()).append("\n");
        sb.append("validExternalOrderCount=").append(result.validExternalOrderCount()).append("\n");
        sb.append("evidenceLevel=LOCAL_SIMULATION\n");
        sb.append("realExternalWriteCount=0\n");
        return sb.toString();
    }

    private int countOracle(String status) {
        int n = 0;
        for (OracleResult r : oracleResults) {
            if (r.status().name().equals(status)) {
                n++;
            }
        }
        return n;
    }

    private String buildEventTimeline() {
        StringBuilder sb = new StringBuilder();
        sb.append("eventId\toffsetMillis\tlogicalSequence\taction\tstatus\tdetail\n");
        for (SimulationRuntime.EventRecord rec : timeline) {
            ScenarioEvent e = rec.event();
            sb.append(e.eventId()).append("\t")
              .append(e.offsetMillis()).append("\t")
              .append(e.logicalSequence()).append("\t")
              .append(e.action().name()).append("\t")
              .append(rec.status()).append("\t")
              .append(rec.detail()).append("\n");
        }
        return sb.toString();
    }

    private String buildExternalInteractions() {
        StringBuilder sb = new StringBuilder();
        sb.append("sequence\tvirtualOccurredAt\toperation\tordinal\tapplicationId\toutcome\tsideEffectCertainty\texternalOrderId\tscriptId\n");
        for (ExternalInteraction i : result.interactions()) {
            sb.append(i.interactionSequence()).append("\t")
              .append(i.virtualOccurredAt()).append("\t")
              .append(i.operation().name()).append("\t")
              .append(i.ordinal()).append("\t")
              .append(i.applicationId()).append("\t")
              .append(i.outcome()).append("\t")
              .append(i.sideEffectCertainty()).append("\t")
              .append(i.externalOrderId()).append("\t")
              .append(i.scriptId()).append("\n");
        }
        return sb.toString();
    }

    private String buildOracleResults() {
        StringBuilder sb = new StringBuilder();
        sb.append("oracleId\truleType\tstatus\texpected\tactual\tdetail\n");
        for (OracleResult r : oracleResults) {
            sb.append(r.oracleId()).append("\t")
              .append(r.ruleType()).append("\t")
              .append(r.status().name()).append("\t")
              .append(r.expected()).append("\t")
              .append(r.actual()).append("\t")
              .append(r.detail()).append("\n");
        }
        return sb.toString();
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }
}
