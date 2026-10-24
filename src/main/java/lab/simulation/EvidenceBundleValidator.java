package lab.simulation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 证据包校验器（PR2b/T1）。
 *
 * <p>逐项拒绝：
 * <ol>
 *   <li>目录不是普通目录（不跟随符号链接）</li>
 *   <li>直接子项不是精确的 7 个文件</li>
 *   <li>任一文件不是普通文件（软链接一律拒绝）</li>
 *   <li>manifest 不是恰好 6 行 / 格式错 / 文件名不合规 / 升序不等于约定集合</li>
 *   <li>逐文件重算 SHA-256 与 manifest 不一致</li>
 *   <li>run-summary 不是 21 个固定字段 / 计数算术不成立</li>
 *   <li>event-timeline 表头不固定 / 行数与 plannedEventCount 不符</li>
 *   <li>三方摘要不一致（scenario.scenario / scenario.sha256 / run-summary）</li>
 * </ol>
 */
public class EvidenceBundleValidator {

    /** manifest 之外的 6 个内容文件，按文件名升序。 */
    public static final List<String> MANIFEST_FILES = List.of(
            "event-timeline.tsv",
            "external-interactions.tsv",
            "oracle-results.tsv",
            "run-summary.txt",
            "scenario.scenario",
            "scenario.sha256");

    /** 证据目录必须精确包含的 7 个文件，按文件名升序。 */
    public static final List<String> FILES = List.of(
            "event-timeline.tsv",
            "external-interactions.tsv",
            "manifest.sha256",
            "oracle-results.tsv",
            "run-summary.txt",
            "scenario.scenario",
            "scenario.sha256");

    /** run-summary.txt 的 21 个字段，顺序固定。 */
    public static final List<String> SUMMARY_FIELDS = List.of(
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

    /** event-timeline.tsv 的固定表头。 */
    public static final String TIMELINE_HEADER =
            "eventId\tplannedOffsetMillis\tlogicalSequence\taction\tstatus\tvirtualOccurredAt\tdetail";

    private static final Pattern MANIFEST_LINE =
            Pattern.compile("^([0-9a-f]{64})  ([A-Za-z0-9._-]+)$");
    private static final Pattern DIGEST_LINE =
            Pattern.compile("^([0-9a-f]{64})  scenario\\.scenario$");

    /**
     * 校验一个证据目录；不通过时抛 {@link EvidenceValidationException}。
     */
    public void validate(Path dir) {
        validateAndDigest(dir);
    }

    /**
     * 校验证据目录并返回三方可信的场景摘要。
     */
    public String validateAndDigest(Path dir) {
        checkDirectory(dir);
        checkEntrySet(dir);
        checkRegularFiles(dir);
        List<String> manifest = checkManifest(dir);
        verifyManifestDigests(dir, manifest);
        List<String> summary = checkSummary(dir);
        checkTimeline(dir, summary);
        return checkDigestConsistency(dir, summary);
    }

    // ---------- 1/2 目录与条目集合 ----------

    private void checkDirectory(Path dir) {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            throw new EvidenceValidationException("EVIDENCE_DIR_NOT_FOUND", name(dir));
        }
        if (Files.isSymbolicLink(dir) || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            throw new EvidenceValidationException("EVIDENCE_DIR_NOT_DIRECTORY", name(dir));
        }
    }

    private void checkEntrySet(Path dir) {
        Set<String> actual = new TreeSet<>(listNames(dir));
        Set<String> expected = new TreeSet<>(FILES);
        if (!actual.equals(expected)) {
            Set<String> missing = new TreeSet<>(expected);
            missing.removeAll(actual);
            Set<String> extra = new TreeSet<>(actual);
            extra.removeAll(expected);
            throw new EvidenceValidationException("EVIDENCE_FILE_SET",
                    "missing=" + missing + " extra=" + extra);
        }
    }

    private void checkRegularFiles(Path dir) {
        for (String fileName : FILES) {
            Path file = dir.resolve(fileName);
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new EvidenceValidationException("EVIDENCE_FILE_NOT_REGULAR", fileName);
            }
        }
    }

    // ---------- 3/4 manifest 与摘要 ----------

    private List<String> checkManifest(Path dir) {
        List<String> lines = readLines(dir.resolve("manifest.sha256"));
        if (lines.size() != MANIFEST_FILES.size()) {
            throw new EvidenceValidationException("EVIDENCE_MANIFEST_LINES",
                    "lines=" + lines.size());
        }
        List<String> names = new ArrayList<>();
        for (String line : lines) {
            Matcher matcher = MANIFEST_LINE.matcher(line);
            if (!matcher.matches()) {
                throw new EvidenceValidationException("EVIDENCE_MANIFEST_FORMAT", line);
            }
            String fileName = matcher.group(2);
            if (fileName.contains("..")) {
                throw new EvidenceValidationException("EVIDENCE_MANIFEST_FORMAT", line);
            }
            names.add(fileName);
        }
        if (!names.equals(MANIFEST_FILES)) {
            throw new EvidenceValidationException("EVIDENCE_MANIFEST_ORDER", names.toString());
        }
        return lines;
    }

    private void verifyManifestDigests(Path dir, List<String> manifest) {
        for (String line : manifest) {
            Matcher matcher = MANIFEST_LINE.matcher(line);
            matcher.matches();
            String expected = matcher.group(1);
            String fileName = matcher.group(2);
            String actual = sha256(dir.resolve(fileName));
            if (!actual.equals(expected)) {
                throw new EvidenceValidationException("EVIDENCE_MANIFEST_MISMATCH", fileName);
            }
        }
    }

    // ---------- 5 run-summary ----------

    private List<String> checkSummary(Path dir) {
        List<String> lines = readLines(dir.resolve("run-summary.txt"));
        if (lines.size() != SUMMARY_FIELDS.size()) {
            throw new EvidenceValidationException("EVIDENCE_SUMMARY_LINES",
                    "lines=" + lines.size());
        }
        for (int i = 0; i < SUMMARY_FIELDS.size(); i++) {
            if (!lines.get(i).startsWith(SUMMARY_FIELDS.get(i) + "=")) {
                throw new EvidenceValidationException("EVIDENCE_SUMMARY_FIELDS",
                        "line=" + (i + 1) + " " + lines.get(i));
            }
        }
        int planned = intField(lines, "plannedEventCount");
        int executed = intField(lines, "executedEventCount");
        int pass = intField(lines, "passCount");
        int fail = intField(lines, "failCount");
        int skipped = intField(lines, "skippedCount");
        if (planned != pass + fail + skipped) {
            throw new EvidenceValidationException("EVIDENCE_SUMMARY_ARITHMETIC",
                    "planned=" + planned + " pass+fail+skipped=" + (pass + fail + skipped));
        }
        if (executed != pass + fail) {
            throw new EvidenceValidationException("EVIDENCE_SUMMARY_ARITHMETIC",
                    "executed=" + executed + " pass+fail=" + (pass + fail));
        }
        return lines;
    }

    // ---------- 6/7 时间线与三方摘要 ----------

    private void checkTimeline(Path dir, List<String> summary) {
        List<String> lines = readLines(dir.resolve("event-timeline.tsv"));
        if (lines.isEmpty() || !lines.get(0).equals(TIMELINE_HEADER)) {
            throw new EvidenceValidationException("EVIDENCE_TIMELINE_HEADER",
                    lines.isEmpty() ? "(empty)" : lines.get(0));
        }
        int planned = intField(summary, "plannedEventCount");
        if (lines.size() - 1 != planned) {
            throw new EvidenceValidationException("EVIDENCE_TIMELINE_ROWS",
                    "rows=" + (lines.size() - 1) + " planned=" + planned);
        }
    }

    private String checkDigestConsistency(Path dir, List<String> summary) {
        List<String> digestLines = readLines(dir.resolve("scenario.sha256"));
        if (digestLines.size() != 1) {
            throw new EvidenceValidationException("EVIDENCE_SCENARIO_DIGEST_FORMAT",
                    "lines=" + digestLines.size());
        }
        Matcher matcher = DIGEST_LINE.matcher(digestLines.get(0));
        if (!matcher.matches()) {
            throw new EvidenceValidationException("EVIDENCE_SCENARIO_DIGEST_FORMAT", digestLines.get(0));
        }
        String fromFile = matcher.group(1);
        String actual = sha256(dir.resolve("scenario.scenario"));
        if (!actual.equals(fromFile)) {
            throw new EvidenceValidationException("EVIDENCE_DIGEST_MISMATCH", "scenario.sha256");
        }
        String fromSummary = field(summary, "scenarioDigest");
        if (!actual.equals(fromSummary)) {
            throw new EvidenceValidationException("EVIDENCE_DIGEST_MISMATCH", "run-summary.txt");
        }
        return actual;
    }

    // ---------- 工具 ----------

    private static List<String> listNames(Path dir) {
        try (var stream = Files.list(dir)) {
            return stream.map(path -> path.getFileName().toString()).toList();
        } catch (IOException e) {
            throw new EvidenceValidationException("EVIDENCE_DIR_NOT_FOUND", name(dir));
        }
    }

    private static List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new EvidenceValidationException("EVIDENCE_FILE_NOT_REGULAR", name(file));
        }
    }

    private static String field(List<String> summary, String key) {
        for (String line : summary) {
            if (line.startsWith(key + "=")) {
                return line.substring(key.length() + 1);
            }
        }
        throw new EvidenceValidationException("EVIDENCE_SUMMARY_FIELDS", key);
    }

    private static int intField(List<String> summary, String key) {
        try {
            return Integer.parseInt(field(summary, key));
        } catch (NumberFormatException e) {
            throw new EvidenceValidationException("EVIDENCE_SUMMARY_FIELDS", key);
        }
    }

    private static String name(Path path) {
        Path fileName = path.getFileName();
        return fileName == null ? "" : fileName.toString();
    }

    private static String sha256(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(Files.readAllBytes(file));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new EvidenceValidationException("EVIDENCE_FILE_NOT_REGULAR", name(file));
        }
    }
}
