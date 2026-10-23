package lab.simulation;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 把 .scenario 文件解析为不可变的 ScenarioPackage。
 *
 * <p>校验顺序固定为：
 * <ol>
 *   <li>文件存在、是普通文件、大小不超过 1 MiB</li>
 *   <li>UTF-8、无 BOM、无 NUL</li>
 *   <li>计算原始字节 SHA-256</li>
 *   <li>SCENARIO、START_AT、SEED 各出现一次且顺序正确</li>
 *   <li>END 是最后一条非注释记录</li>
 *   <li>scenarioId、eventId、scriptId、oracleId 格式</li>
 *   <li>version、offset、sequence、ordinal、seed 可解析</li>
 *   <li>事件顺序、编号和顺序键唯一</li>
 *   <li>external script 的 operation/outcome 组合及连续 ordinal</li>
 *   <li>action 和参数数量</li>
 *   <li>Oracle 类型、参数数量和编号唯一</li>
 *   <li>构造 ScenarioPackage</li>
 * </ol>
 */
public class ScenarioParser {

    private static final long MAX_FILE_BYTES = 1024L * 1024L;

    private static final Set<String> CREATE_OUTCOMES = Set.of(
            "ACCEPTED",
            "REJECTED",
            "TIMEOUT_BUT_CREATED",
            "TIMEOUT_NOT_CREATED");

    private static final Set<String> QUERY_OUTCOMES = Set.of(
            "ACCEPTED",
            "NOT_FOUND");

    /**
     * 解析并校验场景文件。
     */
    public static ScenarioPackage parse(Path file) {
        // 1. 文件边界
        if (!Files.exists(file) || !Files.isRegularFile(file)) {
            throw new ScenarioValidationException("SCENARIO_FILE_NOT_FOUND", file.toString());
        }
        try {
            long size = Files.size(file);
            if (size > MAX_FILE_BYTES) {
                throw new ScenarioValidationException("SCENARIO_FILE_TOO_LARGE", String.valueOf(size));
            }
        } catch (IOException e) {
            throw new ScenarioValidationException("SCENARIO_FILE_NOT_FOUND", file.toString());
        }

        // 2. 读取原始字节
        byte[] raw;
        try {
            raw = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new ScenarioValidationException("SCENARIO_FILE_NOT_FOUND", file.toString());
        }

        // 无 BOM、无 NUL
        if (raw.length >= 3
                && (raw[0] & 0xFF) == 0xEF
                && (raw[1] & 0xFF) == 0xBB
                && (raw[2] & 0xFF) == 0xBF) {
            throw new ScenarioValidationException("SCENARIO_ENCODING", "BOM");
        }
        for (byte b : raw) {
            if (b == 0) {
                throw new ScenarioValidationException("SCENARIO_ENCODING", "NUL");
            }
        }

        // 3. 严格 UTF-8 解码
        String text;
        try {
            var decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            text = decoder.decode(java.nio.ByteBuffer.wrap(raw)).toString();
        } catch (CharacterCodingException e) {
            throw new ScenarioValidationException("SCENARIO_ENCODING", "UTF-8");
        }

        // 4. 计算 SHA-256
        String digest = sha256(raw);

        // 5. 逐行处理
        String[] lines = text.split("\n", -1);

        List<String> records = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.replace("\r", "").trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            records.add(trimmed);
        }

        if (records.isEmpty()) {
            throw new ScenarioValidationException("SCENARIO_HEADER_MISSING", "empty");
        }

        int idx = 0;

        // SCENARIO
        String[] scenarioHeader = records.get(idx).split("[ \t]+");
        if (scenarioHeader.length != 3 || !scenarioHeader[0].equals("SCENARIO")) {
            throw new ScenarioValidationException("SCENARIO_HEADER_MISSING", "SCENARIO");
        }
        String scenarioId = scenarioHeader[1];
        checkId(scenarioId, "scenarioId");
        int version;
        try {
            version = Integer.parseInt(scenarioHeader[2]);
        } catch (NumberFormatException e) {
            throw new ScenarioValidationException("SCENARIO_HEADER_MISSING", "version");
        }
        if (version < 1) {
            throw new ScenarioValidationException("SCENARIO_HEADER_MISSING", "version<1");
        }
        idx++;

        // START_AT
        if (idx >= records.size()) {
            throw new ScenarioValidationException("SCENARIO_HEADER_MISSING", "START_AT");
        }
        String[] startLine = records.get(idx).split("[ \t]+");
        if (startLine.length != 2 || !startLine[0].equals("START_AT")) {
            throw new ScenarioValidationException("SCENARIO_HEADER_MISSING", "START_AT");
        }
        Instant startAt;
        try {
            startAt = Instant.parse(startLine[1]);
        } catch (DateTimeParseException e) {
            throw new ScenarioValidationException("SCENARIO_HEADER_MISSING", "START_AT");
        }
        idx++;

        // SEED
        if (idx >= records.size()) {
            throw new ScenarioValidationException("SCENARIO_HEADER_MISSING", "SEED");
        }
        String[] seedLine = records.get(idx).split("[ \t]+");
        if (seedLine.length != 2 || !seedLine[0].equals("SEED")) {
            throw new ScenarioValidationException("SCENARIO_HEADER_MISSING", "SEED");
        }
        long rootSeed;
        try {
            rootSeed = Long.parseLong(seedLine[1]);
        } catch (NumberFormatException e) {
            throw new ScenarioValidationException("SCENARIO_HEADER_MISSING", "SEED");
        }
        idx++;

        // 剩余记录：EXTERNAL / AT / EXPECT / END
        List<ExternalScript> scripts = new ArrayList<>();
        List<ScenarioEvent> events = new ArrayList<>();
        List<OracleRule> oracles = new ArrayList<>();
        Set<String> scriptIds = new HashSet<>();
        Set<String> eventIds = new HashSet<>();
        Set<String> oracleIds = new HashSet<>();
        Set<String> orderKeys = new HashSet<>();

        boolean sawEnd = false;

        while (idx < records.size()) {
            String line = records.get(idx);
            String[] parts = line.split("[ \t]+");
            String head = parts[0];

            if (head.equals("END")) {
                if (parts.length != 1) {
                    throw new ScenarioValidationException("SCENARIO_ARGUMENT_COUNT", "END");
                }
                if (idx != records.size() - 1) {
                    throw new ScenarioValidationException("SCENARIO_END_MISSING", "not last");
                }
                sawEnd = true;
                idx++;
                break;
            }

            if (head.equals("EXTERNAL")) {
                // EXTERNAL <scriptId> <CREATE|QUERY> <ordinal> <outcome> <externalOrderId|->
                if (parts.length != 6) {
                    throw new ScenarioValidationException("SCENARIO_ARGUMENT_COUNT", "EXTERNAL");
                }
                String scriptId = parts[1];
                checkId(scriptId, "scriptId");
                if (!scriptIds.add(scriptId)) {
                    throw new ScenarioValidationException("SCENARIO_DUPLICATE_EVENT", scriptId);
                }
                ExternalScript.Operation op;
                try {
                    op = ExternalScript.Operation.valueOf(parts[2]);
                } catch (IllegalArgumentException e) {
                    throw new ScenarioValidationException("SCENARIO_UNKNOWN_ACTION", parts[2]);
                }
                int ordinal;
                try {
                    ordinal = Integer.parseInt(parts[3]);
                } catch (NumberFormatException e) {
                    throw new ScenarioValidationException("SCENARIO_SCRIPT_ORDINAL", parts[3]);
                }
                if (ordinal < 1) {
                    throw new ScenarioValidationException("SCENARIO_SCRIPT_ORDINAL", parts[3]);
                }
                String outcome = parts[4];
                if (op == ExternalScript.Operation.CREATE && !CREATE_OUTCOMES.contains(outcome)) {
                    throw new ScenarioValidationException("SCENARIO_UNKNOWN_ACTION", outcome);
                }
                if (op == ExternalScript.Operation.QUERY && !QUERY_OUTCOMES.contains(outcome)) {
                    throw new ScenarioValidationException("SCENARIO_UNKNOWN_ACTION", outcome);
                }
                String externalOrderId = parts[5];
                boolean needsId = outcome.equals("ACCEPTED")
                        || outcome.equals("TIMEOUT_BUT_CREATED");
                if (needsId && externalOrderId.equals("-")) {
                    throw new ScenarioValidationException("SCENARIO_ARGUMENT_COUNT", "externalOrderId");
                }
                if (!needsId && !externalOrderId.equals("-")) {
                    throw new ScenarioValidationException("SCENARIO_ARGUMENT_COUNT", "externalOrderId");
                }
                scripts.add(new ExternalScript(scriptId, op, ordinal, outcome, externalOrderId));
                idx++;
                continue;
            }

            if (head.equals("AT")) {
                // AT <offsetMillis> <logicalSequence> <eventId> <action> [args...]
                if (parts.length < 5) {
                    throw new ScenarioValidationException("SCENARIO_ARGUMENT_COUNT", "AT");
                }
                long offset;
                try {
                    offset = Long.parseLong(parts[1]);
                } catch (NumberFormatException e) {
                    throw new ScenarioValidationException("SCENARIO_EVENT_ORDER", parts[1]);
                }
                long sequence;
                try {
                    sequence = Long.parseLong(parts[2]);
                } catch (NumberFormatException e) {
                    throw new ScenarioValidationException("SCENARIO_EVENT_ORDER", parts[2]);
                }
                if (offset < 0 || sequence < 1) {
                    throw new ScenarioValidationException("SCENARIO_EVENT_ORDER", parts[1] + " " + parts[2]);
                }
                String eventId = parts[3];
                checkId(eventId, "eventId");
                if (!eventIds.add(eventId)) {
                    throw new ScenarioValidationException("SCENARIO_DUPLICATE_EVENT", eventId);
                }
                String orderKey = offset + "/" + sequence;
                if (!orderKeys.add(orderKey)) {
                    throw new ScenarioValidationException("SCENARIO_EVENT_ORDER", orderKey);
                }
                ScenarioEvent.Action action;
                try {
                    action = ScenarioEvent.Action.valueOf(parts[4]);
                } catch (IllegalArgumentException e) {
                    throw new ScenarioValidationException("SCENARIO_UNKNOWN_ACTION", parts[4]);
                }
                List<String> args = new ArrayList<>();
                for (int i = 5; i < parts.length; i++) {
                    args.add(parts[i]);
                }
                checkActionArgs(action, args);
                events.add(new ScenarioEvent(offset, sequence, eventId, action, args));
                idx++;
                continue;
            }

            if (head.equals("EXPECT")) {
                // EXPECT <oracleId> <ruleType> [args...]
                if (parts.length < 3) {
                    throw new ScenarioValidationException("SCENARIO_ARGUMENT_COUNT", "EXPECT");
                }
                String oracleId = parts[1];
                checkId(oracleId, "oracleId");
                if (!oracleIds.add(oracleId)) {
                    throw new ScenarioValidationException("SCENARIO_DUPLICATE_EVENT", oracleId);
                }
                OracleRule.RuleType type;
                try {
                    type = OracleRule.RuleType.valueOf(parts[2]);
                } catch (IllegalArgumentException e) {
                    throw new ScenarioValidationException("SCENARIO_UNKNOWN_ORACLE", parts[2]);
                }
                List<String> args = new ArrayList<>();
                for (int i = 3; i < parts.length; i++) {
                    args.add(parts[i]);
                }
                checkOracleArgs(type, args);
                oracles.add(new OracleRule(oracleId, type, args));
                idx++;
                continue;
            }

            throw new ScenarioValidationException("SCENARIO_UNKNOWN_ACTION", head);
        }

        if (!sawEnd) {
            throw new ScenarioValidationException("SCENARIO_END_MISSING", "END");
        }

        // 事件顺序校验
        for (int i = 1; i < events.size(); i++) {
            ScenarioEvent prev = events.get(i - 1);
            ScenarioEvent cur = events.get(i);
            if (cur.offsetMillis() < prev.offsetMillis()) {
                throw new ScenarioValidationException("SCENARIO_EVENT_ORDER", cur.eventId());
            }
            if (cur.offsetMillis() == prev.offsetMillis()
                    && cur.logicalSequence() <= prev.logicalSequence()) {
                throw new ScenarioValidationException("SCENARIO_EVENT_ORDER", cur.eventId());
            }
        }

        // 脚本 ordinal 连续性校验
        checkScriptOrdinals(scripts);

        return new ScenarioPackage(
                scenarioId,
                version,
                startAt,
                rootSeed,
                digest,
                scripts,
                events,
                oracles);
    }

    private static void checkId(String id, String field) {
        if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) {
            throw new ScenarioValidationException("SCENARIO_HEADER_MISSING", field + "=" + id);
        }
    }

    private static void checkActionArgs(ScenarioEvent.Action action, List<String> args) {
        int need = switch (action) {
            case POINT_ADD -> 2;
            case APPLICATION_ADD -> 3;
            case APPLICATION_DISPATCH -> 1;
            case APPLICATION_QUERY -> 1;
            case APPLICATION_CALLBACK -> 2;
            case OUTBOX_PROCESS -> 0;
            case WORKFLOW_START -> 1;
            case WORKFLOW_ADVANCE -> 1;
        };
        if (args.size() != need) {
            throw new ScenarioValidationException("SCENARIO_ARGUMENT_COUNT", action.name());
        }
    }

    private static void checkOracleArgs(OracleRule.RuleType type, List<String> args) {
        int need = switch (type) {
            case APPLICATION_STATE -> 2;
            case OUTBOX_STATUS -> 2;
            case CREATE_CALLS -> 2;
            case QUERY_CALLS -> 2;
            case VALID_EXTERNAL_ORDERS -> 2;
            case NO_DUPLICATE_EXTERNAL_ORDER -> 1;
        };
        if (args.size() != need) {
            throw new ScenarioValidationException("SCENARIO_ARGUMENT_COUNT", type.name());
        }
    }

    private static void checkScriptOrdinals(List<ExternalScript> scripts) {
        // 按 operation 分组，检查每个 operation 的 ordinal 从 1 连续递增
        java.util.Map<ExternalScript.Operation, List<Integer>> byOp =
                new java.util.EnumMap<>(ExternalScript.Operation.class);
        for (ExternalScript.Operation op : ExternalScript.Operation.values()) {
            byOp.put(op, new ArrayList<>());
        }
        for (ExternalScript s : scripts) {
            byOp.get(s.operation()).add(s.ordinal());
        }
        for (var entry : byOp.entrySet()) {
            List<Integer> ordinals = entry.getValue();
            java.util.Collections.sort(ordinals);
            for (int i = 0; i < ordinals.size(); i++) {
                if (ordinals.get(i) != i + 1) {
                    throw new ScenarioValidationException(
                            "SCENARIO_SCRIPT_ORDINAL", entry.getKey().name());
                }
            }
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
