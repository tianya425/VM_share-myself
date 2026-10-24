package lab.simulation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 比较两次仿真运行的证据目录（PR2b/T3）。
 *
 * <p>规则：
 * <ol>
 *   <li>先校验 A、再校验 B；任一侧非法 → {@code REPLAY_INVALID_EVIDENCE A|B:<code>}</li>
 *   <li>比较场景摘要（scenarioDigest）；不同 → {@code REPLAY_DIFFERENT scenario.scenario}</li>
 *   <li>逐字节比较 scenario.scenario / run-summary.txt / event-timeline.tsv /
 *       external-interactions.tsv / oracle-results.tsv，首个不同 → {@code REPLAY_DIFFERENT <file>}</li>
 *   <li>全部相同 → {@code REPLAY_MATCH <scenarioId> version=<n>}</li>
 * </ol>
 *
 * <p>目录路径本身不参与比较。
 */
public class ReplayComparator {

    /** 逐字节比较顺序（首个不同即为结果）。 */
    private static final List<String> COMPARE_FILES = List.of(
            "scenario.scenario",
            "run-summary.txt",
            "event-timeline.tsv",
            "external-interactions.tsv",
            "oracle-results.tsv");

    private final EvidenceBundleValidator validator = new EvidenceBundleValidator();

    /**
     * 比较两个证据目录。
     */
    public ReplayResult compare(Path dirA, Path dirB) {
        String digestA;
        try {
            digestA = validator.validateAndDigest(dirA);
        } catch (EvidenceValidationException e) {
            return invalid("A:" + e.code());
        }
        String digestB;
        try {
            digestB = validator.validateAndDigest(dirB);
        } catch (EvidenceValidationException e) {
            return invalid("B:" + e.code());
        }

        if (!digestA.equals(digestB)) {
            return different("scenario.scenario");
        }
        for (String fileName : COMPARE_FILES) {
            if (!sameBytes(dirA.resolve(fileName), dirB.resolve(fileName))) {
                return different(fileName);
            }
        }

        List<String> summary = readLines(dirA.resolve("run-summary.txt"));
        String scenarioId = field(summary, "scenarioId");
        int version = Integer.parseInt(field(summary, "scenarioVersion"));
        return new ReplayResult(ReplayResult.MATCH, scenarioId, version, "");
    }

    private ReplayResult invalid(String detail) {
        return new ReplayResult(ReplayResult.INVALID, "", 0, detail);
    }

    private ReplayResult different(String fileName) {
        return new ReplayResult(ReplayResult.DIFFERENT, "", 0, fileName);
    }

    private boolean sameBytes(Path a, Path b) {
        try {
            return java.util.Arrays.equals(Files.readAllBytes(a), Files.readAllBytes(b));
        } catch (IOException e) {
            return false;
        }
    }

    private List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new EvidenceValidationException("EVIDENCE_FILE_NOT_REGULAR", file.getFileName().toString());
        }
    }

    private String field(List<String> summary, String key) {
        for (String line : summary) {
            if (line.startsWith(key + "=")) {
                return line.substring(key.length() + 1);
            }
        }
        throw new EvidenceValidationException("EVIDENCE_SUMMARY_FIELDS", key);
    }
}
