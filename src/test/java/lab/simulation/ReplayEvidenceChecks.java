package lab.simulation;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 证据包与回放的 12 项具名检查：RPL-01 — RPL-12（PR2b/T4）。
 *
 * <p>每个编号一个独立方法 {@code rpl01()} … {@code rpl12()}；只有该编号的全部断言都通过，
 * 才打印 {@code RPL-XX PASS} 并让 passed 加一。失败时打印 FAIL 与 INTENT，全部跑完以退出码 1 结束。
 */
public final class ReplayEvidenceChecks {

    private static final int CASE_TOTAL = 12;

    private static final List<String> ALL_FILES = List.of(
            "event-timeline.tsv",
            "external-interactions.tsv",
            "manifest.sha256",
            "oracle-results.tsv",
            "run-summary.txt",
            "scenario.scenario",
            "scenario.sha256");

    private static final List<String> MANIFEST_FILES = List.of(
            "event-timeline.tsv",
            "external-interactions.tsv",
            "oracle-results.tsv",
            "run-summary.txt",
            "scenario.scenario",
            "scenario.sha256");

    private static final List<String> SUMMARY_FIELDS = List.of(
            "scenarioId",
            "scenarioVersion",
            "scenarioDigest",
            "rootSeed",
            "virtualStartAt",
            "schemaVersion",
            "runStatus",
            "plannedEventCount",
            "executedEventCount",
            "passCount",
            "failCount",
            "skippedCount",
            "firstFailureEventId",
            "firstFailureCode",
            "oraclePassCount",
            "oracleFailCount",
            "createCallCount",
            "queryCallCount",
            "validExternalOrderCount",
            "evidenceLevel",
            "realExternalWriteCount");

    private static final String TIMELINE_HEADER =
            "eventId\tplannedOffsetMillis\tlogicalSequence\taction\tstatus\tvirtualOccurredAt\tdetail";

    private static int passed = 0;
    private static int failed = 0;
    private static Path tmpDir;

    private static final String INTENT_01 =
            "证据目录精确 7 个文件、manifest 恰好 6 行且逐文件重算 SHA-256 一致";
    private static final String INTENT_02 =
            "同一场景两次运行 → compare 输出 REPLAY_MATCH <id> version=<n>，退出 0";
    private static final String INTENT_03 =
            "run-summary 21 个字段顺序固定，且 planned=pass+fail+skipped、executed=pass+fail";
    private static final String INTENT_04 =
            "event-timeline 表头固定为 7 列，行数等于 plannedEventCount";
    private static final String INTENT_05 =
            "三方摘要一致：sha256(scenario.scenario)=scenario.sha256=run-summary 的 scenarioDigest";
    private static final String INTENT_06 =
            "内容被改但 manifest 未更新 → REPLAY_INVALID_EVIDENCE + B:<code>，退出 2";
    private static final String INTENT_07 =
            "目录多文件/少文件、文件非普通文件 → INVALID（EVIDENCE_FILE_SET / EVIDENCE_FILE_NOT_REGULAR）";
    private static final String INTENT_08 =
            "两个不同场景的证据 → REPLAY_DIFFERENT scenario.scenario，退出 1";
    private static final String INTENT_09 =
            "改 scenario.scenario 并删掉 manifest 对应行 → INVALID，退出 2（复现 REP-04 的反面）";
    private static final String INTENT_10 =
            "manifest 缺行/重复行/未知文件/绝对路径/.. 穿越/子目录/软链接 逐项拒绝";
    private static final String INTENT_11 =
            "FAILED 运行仍生成 7 文件并校验通过，CLI 输出 RUN_FAILED + EVIDENCE_WRITTEN，退出 1";
    private static final String INTENT_12 =
            "目标目录已存在被拒且原字节不变；写入故障注入 → 退出 3、无 .staging- 残留、无 EVIDENCE_WRITTEN";

    private ReplayEvidenceChecks() {
    }

    public static void main(String[] args) {
        try {
            tmpDir = Files.createTempDirectory("replay-evidence-checks-");
            run("RPL-01", INTENT_01, ReplayEvidenceChecks::rpl01);
            run("RPL-02", INTENT_02, ReplayEvidenceChecks::rpl02);
            run("RPL-03", INTENT_03, ReplayEvidenceChecks::rpl03);
            run("RPL-04", INTENT_04, ReplayEvidenceChecks::rpl04);
            run("RPL-05", INTENT_05, ReplayEvidenceChecks::rpl05);
            run("RPL-06", INTENT_06, ReplayEvidenceChecks::rpl06);
            run("RPL-07", INTENT_07, ReplayEvidenceChecks::rpl07);
            run("RPL-08", INTENT_08, ReplayEvidenceChecks::rpl08);
            run("RPL-09", INTENT_09, ReplayEvidenceChecks::rpl09);
            run("RPL-10", INTENT_10, ReplayEvidenceChecks::rpl10);
            run("RPL-11", INTENT_11, ReplayEvidenceChecks::rpl11);
            run("RPL-12", INTENT_12, ReplayEvidenceChecks::rpl12);
        } catch (Exception e) {
            System.out.println("RPL-HARNESS FAIL: " + e);
            System.exit(1);
        } finally {
            deleteRecursively(tmpDir);
        }

        if (passed + failed != CASE_TOTAL) {
            throw new AssertionError("case 数量不符：passed+failed=" + (passed + failed)
                    + " 期望=" + CASE_TOTAL);
        }
        if (failed > 0) {
            System.out.println("REPLAY_EVIDENCE_R1_CASES_FAILED=" + failed);
            System.out.println("REPLAY_EVIDENCE_R1_CASES_PASSED=" + passed);
            System.exit(1);
        }
        System.out.println("REPLAY_EVIDENCE_R1_CASES_PASSED=" + passed);
    }

    // ================= RPL-01 =================

    private static void rpl01() throws Exception {
        Path evidence = produce("rpl01", Path.of("scenarios/sim-001-happy.scenario"));
        new EvidenceBundleValidator().validate(evidence);

        require(new TreeSet<>(listNames(evidence)).equals(new TreeSet<>(ALL_FILES)),
                "目录条目=" + listNames(evidence));
        require(listNames(evidence).size() == 7, "目录条目数=" + listNames(evidence).size());

        List<String> manifest = readLines(evidence.resolve("manifest.sha256"));
        require(manifest.size() == 6, "manifest 行数=" + manifest.size());
        for (String line : manifest) {
            require(line.matches("[0-9a-f]{64}  [A-Za-z0-9._-]+"), "manifest 行格式=" + line);
            int split = line.indexOf("  ");
            String expected = line.substring(0, split);
            String name = line.substring(split + 2);
            require(sha256(evidence.resolve(name)).equals(expected), "manifest 摘要不一致：" + name);
        }
    }

    // ================= RPL-02 =================

    private static void rpl02() throws Exception {
        Path a = produce("rpl02a", Path.of("scenarios/sim-002-timeout-created.scenario"));
        Path b = produce("rpl02b", Path.of("scenarios/sim-002-timeout-created.scenario"));

        ReplayResult result = new ReplayComparator().compare(a, b);
        require(result.isMatch(), "compare 结果=" + result.toLine());
        require(result.scenarioId().equals("SIM-002"), "scenarioId=" + result.scenarioId());
        require(result.version() == 1, "version=" + result.version());

        CliResult cli = cli("compare", a.toString(), b.toString());
        require(cli.exitCode() == 0, "CLI 退出码=" + cli.exitCode() + " 输出=" + cli.output());
        require(cli.output().equals("REPLAY_MATCH SIM-002 version=1" + System.lineSeparator())
                        || cli.output().trim().equals("REPLAY_MATCH SIM-002 version=1"),
                "CLI 输出=" + cli.output());
    }

    // ================= RPL-03 =================

    private static void rpl03() throws Exception {
        Path evidence = produce("rpl03", Path.of("scenarios/sim-001-happy.scenario"));
        List<String> summary = readLines(evidence.resolve("run-summary.txt"));

        require(summary.size() == 21, "run-summary 行数=" + summary.size());
        for (int i = 0; i < 21; i++) {
            require(summary.get(i).startsWith(SUMMARY_FIELDS.get(i) + "="),
                    "第 " + (i + 1) + " 行字段名=" + summary.get(i) + "，期望 " + SUMMARY_FIELDS.get(i));
        }

        int planned = intField(summary, "plannedEventCount");
        int executed = intField(summary, "executedEventCount");
        int pass = intField(summary, "passCount");
        int fail = intField(summary, "failCount");
        int skipped = intField(summary, "skippedCount");
        require(planned == pass + fail + skipped, "planned!=pass+fail+skipped：" + planned + " vs "
                + (pass + fail + skipped));
        require(executed == pass + fail, "executed!=pass+fail：" + executed + " vs " + (pass + fail));
        require(planned == 6 && pass == 6 && fail == 0 && skipped == 0,
                "plan=" + planned + " pass=" + pass + " fail=" + fail + " skipped=" + skipped);
    }

    // ================= RPL-04 =================

    private static void rpl04() throws Exception {
        Path evidence = produce("rpl04", Path.of("scenarios/sim-001-happy.scenario"));
        List<String> timeline = readLines(evidence.resolve("event-timeline.tsv"));

        require(timeline.get(0).equals(TIMELINE_HEADER), "表头=" + timeline.get(0));
        require(timeline.size() == 1 + 6, "数据行数=" + (timeline.size() - 1));
        for (int i = 1; i < timeline.size(); i++) {
            String[] cells = timeline.get(i).split("\t", -1);
            require(cells.length == 7, "第 " + i + " 行列数=" + cells.length);
            require(cells[0].startsWith("EVT-"), "第 " + i + " 行 eventId=" + cells[0]);
            require(cells[5].matches(".+"), "第 " + i + " 行 virtualOccurredAt 为空");
        }
    }

    // ================= RPL-05 =================

    private static void rpl05() throws Exception {
        Path evidence = produce("rpl05", Path.of("scenarios/sim-001-happy.scenario"));
        String actual = sha256(evidence.resolve("scenario.scenario"));
        String fromSha = readLines(evidence.resolve("scenario.sha256")).get(0).split("\\s+")[0];
        String fromSummary = field(readLines(evidence.resolve("run-summary.txt")), "scenarioDigest");

        require(actual.equals(fromSha), "scenario.sha256=" + fromSha + " 实际=" + actual);
        require(actual.equals(fromSummary), "run-summary scenarioDigest=" + fromSummary + " 实际=" + actual);
        require(new EvidenceBundleValidator().validateAndDigest(evidence).startsWith(actual),
                "validator 返回的摘要与实际不一致");
    }

    // ================= RPL-06 =================

    private static void rpl06() throws Exception {
        Path a = produce("rpl06a", Path.of("scenarios/sim-001-happy.scenario"));
        Path b = produce("rpl06b", Path.of("scenarios/sim-001-happy.scenario"));
        appendLine(b.resolve("event-timeline.tsv"), "TAMPERED");

        CliResult cli = cli("compare", a.toString(), b.toString());
        require(cli.exitCode() == 2, "CLI 退出码=" + cli.exitCode() + " 输出=" + cli.output());
        require(cli.output().trim().startsWith("REPLAY_INVALID_EVIDENCE"), "输出=" + cli.output());
        require(cli.output().contains("B:"), "detail 必须标明 B 侧：" + cli.output());
    }

    // ================= RPL-07 =================

    private static void rpl07() throws Exception {
        Path extra = produce("rpl07a", Path.of("scenarios/sim-001-happy.scenario"));
        Files.writeString(extra.resolve("extra.txt"), "x", StandardCharsets.UTF_8);
        expectInvalid(extra, "EVIDENCE_FILE_SET", "多一个普通文件");

        Path missing = produce("rpl07b", Path.of("scenarios/sim-001-happy.scenario"));
        Files.delete(missing.resolve("oracle-results.tsv"));
        expectInvalid(missing, "EVIDENCE_FILE_SET", "少一个文件");

        Path notRegular = produce("rpl07c", Path.of("scenarios/sim-001-happy.scenario"));
        Path real = Files.createTempFile(tmpDir, "copy-", ".tsv");
        Files.copy(notRegular.resolve("run-summary.txt"), real,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.delete(notRegular.resolve("run-summary.txt"));
        Files.createSymbolicLink(notRegular.resolve("run-summary.txt"), real);
        expectInvalid(notRegular, "EVIDENCE_FILE_NOT_REGULAR", "普通文件被换成软链接");
    }

    // ================= RPL-08 =================

    private static void rpl08() throws Exception {
        Path one = produce("rpl08a", Path.of("scenarios/sim-001-happy.scenario"));
        Path two = produce("rpl08b", Path.of("scenarios/sim-002-timeout-created.scenario"));

        CliResult cli = cli("compare", one.toString(), two.toString());
        require(cli.exitCode() == 1, "CLI 退出码=" + cli.exitCode() + " 输出=" + cli.output());
        require(cli.output().trim().equals("REPLAY_DIFFERENT scenario.scenario"),
                "首个不同文件=" + cli.output().trim());
    }

    // ================= RPL-09 =================

    private static void rpl09() throws Exception {
        Path evidence = produce("rpl09", Path.of("scenarios/sim-001-happy.scenario"));
        Files.write(evidence.resolve("scenario.scenario"),
                "SCENARIO SIM-999 1\nSTART_AT 2026-01-01T00:00:00Z\nSEED 1\nEND\n"
                        .getBytes(StandardCharsets.UTF_8));
        List<String> manifest = readLines(evidence.resolve("manifest.sha256"));
        List<String> kept = new ArrayList<>();
        for (String line : manifest) {
            if (!line.endsWith("  scenario.scenario")) {
                kept.add(line);
            }
        }
        writeLines(evidence.resolve("manifest.sha256"), kept);

        expectInvalid(evidence, "EVIDENCE_MANIFEST_LINES", "改场景并删 manifest 行");
        Path other = produce("rpl09b", Path.of("scenarios/sim-001-happy.scenario"));
        CliResult cli = cli("compare", evidence.toString(), other.toString());
        require(cli.exitCode() == 2, "CLI 退出码=" + cli.exitCode());
        require(cli.output().trim().startsWith("REPLAY_INVALID_EVIDENCE A:"), "输出=" + cli.output());
    }

    // ================= RPL-10 =================

    private static void rpl10() throws Exception {
        evidenceSetInvalid("EVIDENCE_MANIFEST_LINES", "缺行", "rpl10a", manifest -> {
            manifest.remove(manifest.size() - 1);
        });
        evidenceSetInvalid("EVIDENCE_MANIFEST_ORDER", "重复行", "rpl10b", manifest -> {
            manifest.set(1, manifest.get(0));
        });
        evidenceSetInvalid("EVIDENCE_MANIFEST_ORDER", "未知文件名", "rpl10c", manifest -> {
            manifest.set(0, manifest.get(0).replace("event-timeline.tsv", "extra.tsv"));
        });
        evidenceSetInvalid("EVIDENCE_MANIFEST_FORMAT", "绝对路径", "rpl10d", manifest -> {
            manifest.set(0, manifest.get(0).replace("event-timeline.tsv", "/etc/passwd"));
        });
        evidenceSetInvalid("EVIDENCE_MANIFEST_FORMAT", ".. 穿越", "rpl10e", manifest -> {
            manifest.set(0, manifest.get(0).replace("event-timeline.tsv", "../x.tsv"));
        });
        evidenceSetInvalid("EVIDENCE_MANIFEST_FORMAT", "子目录名", "rpl10f", manifest -> {
            manifest.set(0, manifest.get(0).replace("event-timeline.tsv", "sub/x.tsv"));
        });

        Path withSubDir = produce("rpl10g", Path.of("scenarios/sim-001-happy.scenario"));
        Files.createDirectory(withSubDir.resolve("sub"));
        expectInvalid(withSubDir, "EVIDENCE_FILE_SET", "目录里多一个子目录");
    }

    // ================= RPL-11 =================

    private static void rpl11() throws Exception {
        Path scenario = Path.of("scenarios/invalid/invalid-unscripted-call.scenario");
        Path evidence = produce("rpl11", scenario);
        new EvidenceBundleValidator().validate(evidence);

        List<String> summary = readLines(evidence.resolve("run-summary.txt"));
        require("FAILED".equals(field(summary, "runStatus")), "runStatus=" + field(summary, "runStatus"));
        require("1".equals(field(summary, "failCount")), "failCount=" + field(summary, "failCount"));

        Path viaCli = tmpDir.resolve("rpl11-cli");
        CliResult cli = cli("run", scenario.toAbsolutePath().toString(), viaCli.toString());
        require(cli.exitCode() == 1, "CLI 退出码=" + cli.exitCode() + " 输出=" + cli.output());
        require(cli.output().contains("RUN_FAILED"), "输出缺少 RUN_FAILED：" + cli.output());
        require(cli.output().contains("EVIDENCE_WRITTEN"), "失败运行也必须写证据：" + cli.output());
        require(listNames(viaCli).size() == 7, "CLI 证据文件数=" + listNames(viaCli).size());
        new EvidenceBundleValidator().validate(viaCli);
    }

    // ================= RPL-12 =================

    private static void rpl12() throws Exception {
        Path scenario = Path.of("scenarios/sim-001-happy.scenario");
        Path existing = produce("rpl12", scenario);
        Path marker = existing.resolve("run-summary.txt");
        byte[] before = Files.readAllBytes(marker);

        CliResult cli = cli("run", scenario.toAbsolutePath().toString(), existing.toString());
        require(cli.exitCode() == 3, "CLI 退出码=" + cli.exitCode() + " 输出=" + cli.output());
        require(!cli.output().contains("EVIDENCE_WRITTEN"), "被拒时不得输出 EVIDENCE_WRITTEN：" + cli.output());
        require(java.util.Arrays.equals(before, Files.readAllBytes(marker)), "已有证据字节被改动");
        require(listNames(existing).size() == 7, "已有证据目录条目数变化：" + listNames(existing));

        Path blockedParent = tmpDir.resolve("rpl12-blocked");
        Files.createDirectory(blockedParent);
        Path target = blockedParent.resolve("evidence");
        Files.setPosixFilePermissions(blockedParent, PosixFilePermissions.fromString("r-x------"));
        CliResult fault;
        try {
            fault = cli("run", scenario.toAbsolutePath().toString(), target.toString());
        } finally {
            Files.setPosixFilePermissions(blockedParent, PosixFilePermissions.fromString("rwx------"));
        }
        require(fault.exitCode() == 3, "故障注入退出码=" + fault.exitCode() + " 输出=" + fault.output());
        require(!fault.output().contains("EVIDENCE_WRITTEN"), "故障时必须无 EVIDENCE_WRITTEN：" + fault.output());
        Set<String> names = new TreeSet<>(listNames(blockedParent));
        require(names.equals(Set.of("evidence")) || names.isEmpty(),
                "残留条目=" + names);
        for (String name : names) {
            require(!name.startsWith(".staging-"), "残留 staging 目录：" + name);
        }
        require(!Files.exists(target), "失败后目标目录不得存在：" + target);
    }

    // ================= 基础设施 =================

    private interface CaseBody {
        void run() throws Exception;
    }

    private interface ManifestMutation {
        void apply(List<String> manifest) throws Exception;
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

    private static Path produce(String name, Path scenario) throws Exception {
        ScenarioPackage pkg = ScenarioParser.parse(scenario);
        SimulationRuntime runtime = new SimulationRuntime(pkg);
        SimulationRunResult result = runtime.run();
        Path evidence = tmpDir.resolve(name);
        new EvidenceBundleWriter(scenario, pkg, result, runtime.timeline(), result.oracleResults())
                .write(evidence);
        return evidence;
    }

    private static void expectInvalid(Path dir, String code, String label) {
        try {
            new EvidenceBundleValidator().validate(dir);
        } catch (EvidenceValidationException e) {
            require(e.code().equals(code), label + "：code=" + e.code() + "，期望 " + code);
            return;
        }
        throw new AssertionError(label + "：未抛 EvidenceValidationException");
    }

    private static void evidenceSetInvalid(String code, String label, String name,
                                           ManifestMutation mutation) throws Exception {
        Path evidence = produce(name, Path.of("scenarios/sim-001-happy.scenario"));
        Path manifestPath = evidence.resolve("manifest.sha256");
        List<String> manifest = readLines(manifestPath);
        mutation.apply(manifest);
        writeLines(manifestPath, manifest);
        expectInvalid(evidence, code, label);
    }

    private static List<String> listNames(Path dir) throws Exception {
        try (var stream = Files.list(dir)) {
            return stream.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private static List<String> readLines(Path file) throws Exception {
        return Files.readAllLines(file, StandardCharsets.UTF_8);
    }

    private static void writeLines(Path file, List<String> lines) throws Exception {
        Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    }

    private static void appendLine(Path file, String line) throws Exception {
        Files.writeString(file, line + "\n", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);
    }

    private static String field(List<String> summary, String key) {
        for (String line : summary) {
            if (line.startsWith(key + "=")) {
                return line.substring(key.length() + 1);
            }
        }
        throw new AssertionError("run-summary 缺字段：" + key);
    }

    private static int intField(List<String> summary, String key) {
        return Integer.parseInt(field(summary, key));
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = digest.digest(Files.readAllBytes(file));
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            builder.append(String.format("%02x", b));
        }
        return builder.toString();
    }

    private record CliResult(int exitCode, String output) {
    }

    private static CliResult cli(String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(System.getProperty("java.home") + "/bin/java");
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add("lab.simulation.SimulationMain");
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        return new CliResult(exit, output);
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
