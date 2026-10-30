package lab.simulation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 把一次仿真运行的证据原子发布到目标目录（PR2b/T2）。
 *
 * <p>发布流程：
 * <ol>
 *   <li>目标目录已存在 → 直接拒绝（EVIDENCE_DIR_EXISTS）</li>
 *   <li>同父目录建 {@code .staging-<UUID>}，写入 6 个内容文件 + manifest</li>
 *   <li>发布前 {@link EvidenceBundleValidator#validate(Path) validate(staging)}</li>
 *   <li>{@code Files.move(staging, target, ATOMIC_MOVE)}</li>
 *   <li>发布后 {@code validate(target)}</li>
 *   <li>任一步失败：删 staging 与未通过校验的目标目录，最终目标不存在</li>
 * </ol>
 *
 * <p>生成 7 个文件（UTF-8、LF、稳定排序）：scenario.scenario / scenario.sha256 /
 * run-summary.txt（21 个固定字段）/ event-timeline.tsv / external-interactions.tsv /
 * oracle-results.tsv / manifest.sha256（不含自身，6 行）。
 */
public class EvidenceBundleWriter {

    private static final EvidenceBundleValidator VALIDATOR = new EvidenceBundleValidator();

    private final Path scenarioFile;
    private final ScenarioPackage scenario;
    private final SimulationRunResult result;
    private final List<SimulationRuntime.EventRecord> timeline;
    private final List<OracleResult> oracleResults;

    /**
     * 创建证据写入器。
     *
     * @param scenarioFile  实际执行的场景文件路径（用于复制原始字节）
     * @param scenario      场景描述
     * @param result        一次运行的结果
     * @param timeline      事件时间线
     * @param oracleResults Oracle 检查结果
     */
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
     * 原子发布证据目录。
     *
     * @throws IOException                 读写失败
     * @throws EvidenceValidationException 目标目录已存在，或发布前后的校验失败
     */
    public void write(Path dir) throws IOException {
        Path target = dir.toAbsolutePath();
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new EvidenceValidationException("EVIDENCE_DIR_EXISTS", name(target));
        }
        Path parent = target.getParent();
        Files.createDirectories(parent);
        Path staging = parent.resolve(".staging-" + UUID.randomUUID());

        try {
            Files.createDirectories(staging);
            writeContent(staging);
            VALIDATOR.validate(staging);
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            VALIDATOR.validate(target);
        } catch (IOException | RuntimeException e) {
            deleteRecursively(staging);
            deleteRecursively(target);
            throw e;
        }
    }

    private void writeContent(Path dir) throws IOException {
        // 1. scenario.scenario（原始字节副本）
        Files.write(dir.resolve("scenario.scenario"), Files.readAllBytes(scenarioFile));

        // 2. scenario.sha256
        Files.writeString(dir.resolve("scenario.sha256"),
                scenario.digest() + "  scenario.scenario\n",
                StandardCharsets.UTF_8);

        // 3. run-summary.txt（21 个固定字段）
        Files.writeString(dir.resolve("run-summary.txt"), buildRunSummary(), StandardCharsets.UTF_8);

        // 4. event-timeline.tsv
        Files.writeString(dir.resolve("event-timeline.tsv"), buildEventTimeline(), StandardCharsets.UTF_8);

        // 5. external-interactions.tsv
        Files.writeString(dir.resolve("external-interactions.tsv"), buildExternalInteractions(),
                StandardCharsets.UTF_8);

        // 6. oracle-results.tsv
        Files.writeString(dir.resolve("oracle-results.tsv"), buildOracleResults(), StandardCharsets.UTF_8);

        // 7. manifest.sha256（不含自身，按文件名升序）
        List<String> names = new ArrayList<>(EvidenceBundleValidator.MANIFEST_FILES);
        Collections.sort(names);
        StringBuilder manifest = new StringBuilder();
        for (String fileName : names) {
            byte[] bytes = Files.readAllBytes(dir.resolve(fileName));
            manifest.append(sha256(bytes)).append("  ").append(fileName).append("\n");
        }
        Files.writeString(dir.resolve("manifest.sha256"), manifest.toString(), StandardCharsets.UTF_8);
    }

    private String buildRunSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("scenarioId=").append(scenario.scenarioId()).append("\n");
        sb.append("scenarioVersion=").append(scenario.version()).append("\n");
        sb.append("scenarioDigest=").append(scenario.digest()).append("\n");
        sb.append("rootSeed=").append(scenario.rootSeed()).append("\n");
        sb.append("virtualStartAt=").append(scenario.startAt()).append("\n");
        sb.append("schemaVersion=").append(result.schemaVersion()).append("\n");
        sb.append("runStatus=").append(result.runStatus()).append("\n");
        sb.append("plannedEventCount=").append(result.plannedCount()).append("\n");
        sb.append("executedEventCount=").append(result.executedCount()).append("\n");
        sb.append("eventPassCount=").append(result.passCount()).append("\n");
        sb.append("eventFailCount=").append(result.failCount()).append("\n");
        sb.append("eventSkippedCount=").append(result.skippedCount()).append("\n");
        sb.append("firstFailureEventId=").append(orDash(result.firstFailureEventId())).append("\n");
        sb.append("firstFailureCode=").append(orDash(result.firstFailureCode())).append("\n");
        sb.append("oraclePassCount=").append(countOracle("PASS")).append("\n");
        sb.append("oracleFailCount=").append(countOracle("FAIL")).append("\n");
        sb.append("createCallCount=").append(result.createCallCount()).append("\n");
        sb.append("queryCallCount=").append(result.queryCallCount()).append("\n");
        sb.append("validExternalOrderCount=").append(result.validExternalOrderCount()).append("\n");
        sb.append("evidenceLevel=").append(result.evidenceLevel()).append("\n");
        sb.append("realExternalWriteCount=").append(result.realExternalWriteCount()).append("\n");
        return sb.toString();
    }

    private static String orDash(String value) {
        return value == null || value.isEmpty() ? "-" : value;
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
        sb.append(EvidenceBundleValidator.TIMELINE_HEADER).append("\n");
        for (SimulationRuntime.EventRecord rec : timeline) {
            sb.append(rec.eventId()).append("\t")
                    .append(rec.plannedOffsetMillis()).append("\t")
                    .append(rec.logicalSequence()).append("\t")
                    .append(rec.action().name()).append("\t")
                    .append(rec.status()).append("\t")
                    .append(rec.virtualOccurredAt()).append("\t")
                    .append(rec.detail()).append("\n");
        }
        return sb.toString();
    }

    private String buildExternalInteractions() {
        StringBuilder sb = new StringBuilder();
        sb.append("sequence\tvirtualOccurredAt\toperation\tordinal\tapplicationId\toutcome\t"
                + "sideEffectCertainty\texternalOrderId\tscriptId\n");
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

    private static String name(Path path) {
        Path fileName = path.getFileName();
        return fileName == null ? "" : fileName.toString();
    }

    private static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 清理尽力而为；失败不掩盖原异常
                }
            });
        } catch (IOException ignored) {
            // 清理尽力而为
        }
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
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
