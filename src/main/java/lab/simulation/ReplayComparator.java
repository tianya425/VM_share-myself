package lab.simulation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 比较两次仿真运行的证据目录。
 *
 * <p>规则：
 * <ol>
 *   <li>校验两个目录的 manifest</li>
 *   <li>校验 scenarioDigest 相同</li>
 *   <li>比较 run-summary、event-timeline、external-interactions、oracle-results</li>
 *   <li>全部相同 → REPLAY_MATCH</li>
 *   <li>任一不同 → REPLAY_DIFFERENT + 首个差异文件</li>
 *   <li>manifest 缺失或错误 → REPLAY_INVALID_EVIDENCE</li>
 * </ol>
 *
 * <p>目录路径本身不参与比较。
 */
public class ReplayComparator {

    private static final List<String> COMPARE_FILES = List.of(
            "event-timeline.tsv",
            "external-interactions.tsv",
            "oracle-results.tsv",
            "run-summary.txt");

    /**
     * 比较两个证据目录。
     *
     * @return 结果码：
     *         REPLAY_MATCH / REPLAY_DIFFERENT / REPLAY_INVALID_EVIDENCE
     */
    public String compare(Path dirA, Path dirB) {
        // 1. 校验 manifest
        if (!verifyManifest(dirA) || !verifyManifest(dirB)) {
            return "REPLAY_INVALID_EVIDENCE";
        }

        // 2. scenarioDigest 相同
        String digestA = readDigest(dirA);
        String digestB = readDigest(dirB);
        if (digestA == null || digestB == null || !digestA.equals(digestB)) {
            return "REPLAY_INVALID_EVIDENCE";
        }

        // 3. 逐文件比较
        for (String name : COMPARE_FILES) {
            String a = readFile(dirA, name);
            String b = readFile(dirB, name);
            if (a == null || b == null) {
                return "REPLAY_INVALID_EVIDENCE";
            }
            if (!a.equals(b)) {
                return "REPLAY_DIFFERENT " + name;
            }
        }

        return "REPLAY_MATCH";
    }

    // ---------- 内部 ----------

    private boolean verifyManifest(Path dir) {
        Path manifest = dir.resolve("manifest.sha256");
        if (!Files.exists(manifest)) {
            return false;
        }
        try {
            List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] parts = line.split("\\s+");
                if (parts.length != 2) {
                    return false;
                }
                String expected = parts[0];
                String name = parts[1];
                Path file = dir.resolve(name);
                if (!Files.exists(file)) {
                    return false;
                }
                byte[] bytes = Files.readAllBytes(file);
                if (!sha256(bytes).equals(expected)) {
                    return false;
                }
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private String readDigest(Path dir) {
        return readFile(dir, "scenario.sha256");
    }

    private String readFile(Path dir, String name) {
        Path file = dir.resolve(name);
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
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
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }
}
