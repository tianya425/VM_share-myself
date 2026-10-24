package lab.simulation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;

/**
 * ScenarioParser 的 16 项具名检查：SCN-01 — SCN-16（PR2a/T1）。
 *
 * <p>每个编号一个独立方法 {@code scn01()} … {@code scn16()}；只有该编号的
 * 全部断言都通过，才打印 {@code SCN-XX PASS} 并让 passed 加一。
 *
 * <p>失败处理：每个 case 单独捕获失败并打印 {@code SCN-XX FAIL: 失败行} 与
 * {@code SCN-XX INTENT: 断言意图}，全部跑完后只要有任一失败就以退出码 1 结束；
 * 16 项全过时最后一行是 {@code SCENARIO_PARSER_CASES_PASSED=16} 且退出码为 0。
 *
 * <p>本类只使用 {@link ScenarioParser#parse(Path)} 与 {@link ScenarioPackage}
 * 的公开访问器，不读取任何私有状态。
 */
public final class ScenarioParserChecks {

    private static final int CASE_TOTAL = 16;

    private static final Path VALID = Path.of("scenarios/sim-001-happy.scenario");
    private static final Path INVALID_BACKWARD = Path.of("scenarios/invalid/invalid-backward-time.scenario");

    private static int passed = 0;
    private static int failed = 0;
    private static Path tmpDir;

    private static final String INTENT_01 =
            "合法场景：字段取值、事件/脚本/期望数量、中文参数保留、时间轴升序、digest 为 64 位小写十六进制";
    private static final String INTENT_02 =
            "digest 必须是原始字节的 SHA-256，与独立计算结果一致；内容不同则 digest 不同";
    private static final String INTENT_03 =
            "ScenarioPackage / ScenarioEvent / ExternalScript / OracleRule 的集合一律不可修改";
    private static final String INTENT_04 =
            "文件边界：不存在、目录、符号链接都必须是 SCENARIO_FILE_NOT_FOUND，且 detail 不含绝对路径";
    private static final String INTENT_05 =
            "文件边界：超过 1 MiB 必须是 SCENARIO_FILE_TOO_LARGE（在解析内容之前拒绝）";
    private static final String INTENT_06 =
            "编码边界：BOM 与 NUL 都必须是 SCENARIO_ENCODING";
    private static final String INTENT_07 =
            "编码边界：非法 UTF-8 字节必须被严格拒绝（REPORT），不得静默替换";
    private static final String INTENT_08 =
            "头部：SCENARIO / START_AT / SEED 必须各出现一次且顺序正确（重复或错序 → SCENARIO_HEADER_MISSING）";
    private static final String INTENT_09 =
            "取值：version 只支持 1；<1 → SCENARIO_HEADER_MISSING，>1 → SCENARIO_UNSUPPORTED_VERSION，非数字 → SCENARIO_HEADER_MISSING";
    private static final String INTENT_10 =
            "唯一性：eventId / scriptId / oracleId 重复 → SCENARIO_DUPLICATE_EVENT；编号格式非法 → SCENARIO_HEADER_MISSING";
    private static final String INTENT_11 =
            "时间轴：offset 倒退 → SCENARIO_EVENT_ORDER 且 detail 是被拒绝事件的 eventId";
    private static final String INTENT_12 =
            "时间轴：offset < 0、logicalSequence < 1、同 (offset,sequence) 重复 → SCENARIO_EVENT_ORDER";
    private static final String INTENT_13 =
            "脚本：CREATE / QUERY 的 ordinal 各自从 1 连续递增，否则 SCENARIO_SCRIPT_ORDINAL";
    private static final String INTENT_14 =
            "脚本：outcome 必须属于该 operation 的允许集合；需要外单号时必须非 '-'，不需要时必须为 '-'";
    private static final String INTENT_15 =
            "动作：每种 action 的参数数量固定；APPLICATION_CALLBACK 的事件类型必须是 STARTED/COMPLETED/FAILED";
    private static final String INTENT_16 =
            "收尾与期望：END 唯一且最后；EXPECT 参数数量与 COUNT 在 parse 期校验为非负整数";

    private ScenarioParserChecks() {
    }

    public static void main(String[] args) {
        try {
            try {
                tmpDir = Files.createTempDirectory("scn-parser-checks-");
            } catch (IOException e) {
                throw new IllegalStateException("无法创建临时目录", e);
            }
            run("SCN-01", INTENT_01, ScenarioParserChecks::scn01);
            run("SCN-02", INTENT_02, ScenarioParserChecks::scn02);
            run("SCN-03", INTENT_03, ScenarioParserChecks::scn03);
            run("SCN-04", INTENT_04, ScenarioParserChecks::scn04);
            run("SCN-05", INTENT_05, ScenarioParserChecks::scn05);
            run("SCN-06", INTENT_06, ScenarioParserChecks::scn06);
            run("SCN-07", INTENT_07, ScenarioParserChecks::scn07);
            run("SCN-08", INTENT_08, ScenarioParserChecks::scn08);
            run("SCN-09", INTENT_09, ScenarioParserChecks::scn09);
            run("SCN-10", INTENT_10, ScenarioParserChecks::scn10);
            run("SCN-11", INTENT_11, ScenarioParserChecks::scn11);
            run("SCN-12", INTENT_12, ScenarioParserChecks::scn12);
            run("SCN-13", INTENT_13, ScenarioParserChecks::scn13);
            run("SCN-14", INTENT_14, ScenarioParserChecks::scn14);
            run("SCN-15", INTENT_15, ScenarioParserChecks::scn15);
            run("SCN-16", INTENT_16, ScenarioParserChecks::scn16);
        } finally {
            deleteRecursively(tmpDir);
        }

        if (passed + failed != CASE_TOTAL) {
            throw new AssertionError("case 数量不符：passed+failed=" + (passed + failed)
                    + " 期望=" + CASE_TOTAL);
        }
        if (failed > 0) {
            System.out.println("SCENARIO_PARSER_CASES_FAILED=" + failed);
            System.out.println("SCENARIO_PARSER_CASES_PASSED=" + passed);
            System.exit(1);
        }
        System.out.println("SCENARIO_PARSER_CASES_PASSED=" + passed);
    }

    // ================= SCN-01 =================

    /** 合法场景的全部稳定字段。 */
    private static void scn01() {
        ScenarioPackage pkg = ScenarioParser.parse(VALID);

        require(pkg.scenarioId().equals("SIM-001"), "scenarioId=" + pkg.scenarioId());
        require(pkg.version() == 1, "version=" + pkg.version());
        require(pkg.startAt().equals(java.time.Instant.parse("2026-01-01T00:00:00Z")),
                "startAt=" + pkg.startAt());
        require(pkg.rootSeed() == 1001L, "rootSeed=" + pkg.rootSeed());
        require(pkg.events().size() == 6, "events=" + pkg.events().size());
        require(pkg.externalScripts().size() == 1, "externalScripts=" + pkg.externalScripts().size());
        require(pkg.oracles().size() == 5, "oracles=" + pkg.oracles().size());

        ScenarioEvent first = pkg.events().get(0);
        require(first.eventId().equals("EVT-001"), "首个 eventId=" + first.eventId());
        require(first.offsetMillis() == 0L, "首个 offsetMillis=" + first.offsetMillis());
        require(first.logicalSequence() == 10L, "首个 logicalSequence=" + first.logicalSequence());
        require(first.action() == ScenarioEvent.Action.POINT_ADD, "首个 action=" + first.action());
        require(first.arguments().equals(List.of("P01", "原料暂存点")),
                "首个参数=" + first.arguments());

        long previousOffset = -1L;
        long previousSequence = -1L;
        for (ScenarioEvent event : pkg.events()) {
            require(event.offsetMillis() >= previousOffset, "时间轴必须单调不减");
            if (event.offsetMillis() == previousOffset) {
                require(event.logicalSequence() > previousSequence, "同一时刻必须按 logicalSequence 升序");
            }
            previousOffset = event.offsetMillis();
            previousSequence = event.logicalSequence();
        }

        ExternalScript script = pkg.externalScripts().get(0);
        require(script.scriptId().equals("EXT-001"), "scriptId=" + script.scriptId());
        require(script.operation() == ExternalScript.Operation.CREATE, "operation=" + script.operation());
        require(script.ordinal() == 1, "ordinal=" + script.ordinal());
        require(script.outcome().equals("ACCEPTED"), "outcome=" + script.outcome());
        require(script.externalOrderId().equals("EXT-APP-001"), "externalOrderId=" + script.externalOrderId());

        List<String> oracleIds = pkg.oracles().stream().map(OracleRule::oracleId).toList();
        require(oracleIds.equals(List.of("ORC-001", "ORC-002", "ORC-003", "ORC-004", "ORC-005")),
                "oracleIds=" + oracleIds);

        require(pkg.digest().matches("[0-9a-f]{64}"), "digest=" + pkg.digest());
    }

    // ================= SCN-02 =================

    /** digest 必须等于原始字节的 SHA-256。 */
    private static void scn02() throws Exception {
        byte[] raw = Files.readAllBytes(VALID);
        require(ScenarioParser.parse(VALID).digest().equals(sha256(raw)), "digest 与 sha256(原始字节) 不一致");

        Path copy = writeText("scn02-copy.scenario", Files.readString(VALID, StandardCharsets.UTF_8));
        require(ScenarioParser.parse(copy).digest().equals(sha256(raw)), "同内容不同路径的 digest 必须相同");

        Path changed = writeText("scn02-changed.scenario",
                Files.readString(VALID, StandardCharsets.UTF_8).replace("SEED 1001", "SEED 1002"));
        require(!ScenarioParser.parse(changed).digest().equals(sha256(raw)), "内容变化后 digest 必须变化");
    }

    // ================= SCN-03 =================

    /** 所有集合不可修改。 */
    private static void scn03() {
        ScenarioPackage pkg = ScenarioParser.parse(VALID);

        expectUnsupported(() -> pkg.events().add(null), "events 可修改");
        expectUnsupported(() -> pkg.externalScripts().add(null), "externalScripts 可修改");
        expectUnsupported(() -> pkg.oracles().add(null), "oracles 可修改");
        expectUnsupported(() -> pkg.events().get(0).arguments().add("X"), "事件参数可修改");
        expectUnsupported(() -> pkg.oracles().get(0).arguments().add("X"), "期望参数可修改");
        expectUnsupported(() -> pkg.events().clear(), "events.clear 未被拒绝");
    }

    // ================= SCN-04 =================

    /** 文件边界：不存在 / 目录 / 符号链接。 */
    private static void scn04() throws Exception {
        Path missing = tmpDir.resolve("scn04-missing.scenario");
        expectErr(missing, "SCENARIO_FILE_NOT_FOUND", null, "不存在的文件");
        String detail = errorDetail(missing);
        require(!detail.contains("/"), "detail 泄露了路径分隔符：" + detail);
        require(!Path.of(detail.isEmpty() ? "." : detail).isAbsolute(), "detail 是绝对路径：" + detail);

        expectErr(tmpDir, "SCENARIO_FILE_NOT_FOUND", null, "目录");

        Path link = tmpDir.resolve("scn04-link.scenario");
        Files.deleteIfExists(link);
        Files.createSymbolicLink(link, VALID.toAbsolutePath());
        expectErr(link, "SCENARIO_FILE_NOT_FOUND", null, "符号链接（必须不跟随）");
    }

    // ================= SCN-05 =================

    /** 文件过大。 */
    private static void scn05() throws Exception {
        byte[] large = new byte[1024 * 1024 + 1];
        java.util.Arrays.fill(large, (byte) '#');
        Path file = tmpDir.resolve("scn05-large.scenario");
        Files.write(file, large);
        expectErr(file, "SCENARIO_FILE_TOO_LARGE", null, "1 MiB + 1 字节");

        Path exact = tmpDir.resolve("scn05-exact.scenario");
        byte[] exactBytes = new byte[1024 * 1024];
        java.util.Arrays.fill(exactBytes, (byte) '#');
        Files.write(exact, exactBytes);
        expectError(exact, "SCENARIO_HEADER_MISSING", "恰为 1 MiB 时必须先过大小检查再报头部错误");
    }

    // ================= SCN-06 =================

    /** BOM 与 NUL。 */
    private static void scn06() throws Exception {
        String body = "SCENARIO SIM-06 1\nSTART_AT 2026-01-01T00:00:00Z\nSEED 6\nEND\n";

        Path bom = tmpDir.resolve("scn06-bom.scenario");
        byte[] bomBytes = concat(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF},
                body.getBytes(StandardCharsets.UTF_8));
        Files.write(bom, bomBytes);
        expectErr(bom, "SCENARIO_ENCODING", "BOM", "BOM");

        Path nul = tmpDir.resolve("scn06-nul.scenario");
        byte[] nulBytes = body.getBytes(StandardCharsets.UTF_8);
        nulBytes[nulBytes.length - 2] = 0;
        Files.write(nul, nulBytes);
        expectErr(nul, "SCENARIO_ENCODING", "NUL", "NUL 字节");
    }

    // ================= SCN-07 =================

    /** 严格 UTF-8。 */
    private static void scn07() throws Exception {
        Path bad = tmpDir.resolve("scn07-bad-utf8.scenario");
        byte[] head = "SCENARIO SIM-07 1\nSTART_AT 2026-01-01T00:00:00Z\nSEED 7\nAT 0 10 EVT-001 POINT_ADD P01 "
                .getBytes(StandardCharsets.UTF_8);
        byte[] tail = "\nEND\n".getBytes(StandardCharsets.UTF_8);
        byte[] bytes = concat(head, new byte[]{(byte) 0xFF}, tail);
        Files.write(bad, bytes);
        expectErr(bad, "SCENARIO_ENCODING", "UTF-8", "0xFF 不是合法 UTF-8");

        Path good = writeText("scn07-good.scenario",
                "SCENARIO SIM-07 1\nSTART_AT 2026-01-01T00:00:00Z\nSEED 7\n"
                        + "AT 0 10 EVT-001 POINT_ADD P01 原料暂存点\nEND\n");
        require(ScenarioParser.parse(good).events().get(0).arguments().get(1).equals("原料暂存点"),
                "合法中文必须原样保留");
    }

    // ================= SCN-08 =================

    /** 头部唯一性与顺序。 */
    private static void scn08() {
        expectErr(writeText("scn08-no-start.scenario",
                        "SCENARIO SIM-08 1\nSEED 8\nEND\n"),
                "SCENARIO_HEADER_MISSING", null, "缺 START_AT");
        expectErr(writeText("scn08-no-seed.scenario",
                        "SCENARIO SIM-08 1\nSTART_AT 2026-01-01T00:00:00Z\nEND\n"),
                "SCENARIO_HEADER_MISSING", null, "缺 SEED");
        expectErr(writeText("scn08-swapped.scenario",
                        "START_AT 2026-01-01T00:00:00Z\nSCENARIO SIM-08 1\nSEED 8\nEND\n"),
                "SCENARIO_HEADER_MISSING", null, "START_AT 在 SCENARIO 之前");
        expectErr(writeText("scn08-dup-scenario.scenario",
                        "SCENARIO SIM-08 1\nSTART_AT 2026-01-01T00:00:00Z\nSEED 8\nSCENARIO SIM-08 1\nEND\n"),
                "SCENARIO_HEADER_MISSING", null, "SCENARIO 重复");
        expectErr(writeText("scn08-dup-seed.scenario",
                        "SCENARIO SIM-08 1\nSTART_AT 2026-01-01T00:00:00Z\nSEED 8\nSEED 9\nEND\n"),
                "SCENARIO_HEADER_MISSING", null, "SEED 重复");
        expectErr(writeText("scn08-bad-id.scenario",
                        "SCENARIO -SIM 1\nSTART_AT 2026-01-01T00:00:00Z\nSEED 8\nEND\n"),
                "SCENARIO_HEADER_MISSING", null, "scenarioId 格式非法");
        expectErr(writeText("scn08-seed-overflow.scenario",
                        "SCENARIO SIM-08 1\nSTART_AT 2026-01-01T00:00:00Z\nSEED 99999999999999999999\nEND\n"),
                "SCENARIO_HEADER_MISSING", null, "seed 溢出");
        expectErr(writeText("scn08-empty.scenario", ""),
                "SCENARIO_HEADER_MISSING", null, "空文件");
        expectErr(writeText("scn08-bad-instant.scenario",
                        "SCENARIO SIM-08 1\nSTART_AT 2026-13-01T00:00:00Z\nSEED 8\nEND\n"),
                "SCENARIO_HEADER_MISSING", null, "Instant 非法");
    }

    // ================= SCN-09 =================

    /** version 只支持 1。 */
    private static void scn09() {
        expectErr(writeText("scn09-v0.scenario",
                        "SCENARIO SIM-09 0\nSTART_AT 2026-01-01T00:00:00Z\nSEED 9\nEND\n"),
                "SCENARIO_HEADER_MISSING", null, "version=0");
        expectErr(writeText("scn09-v2.scenario",
                        "SCENARIO SIM-09 2\nSTART_AT 2026-01-01T00:00:00Z\nSEED 9\nEND\n"),
                "SCENARIO_UNSUPPORTED_VERSION", "version=2", "version=2 必须明确拒绝");
        expectErr(writeText("scn09-vnan.scenario",
                        "SCENARIO SIM-09 one\nSTART_AT 2026-01-01T00:00:00Z\nSEED 9\nEND\n"),
                "SCENARIO_HEADER_MISSING", null, "version 非数字");
        require(ScenarioParser.parse(writeText("scn09-v1.scenario",
                        "SCENARIO SIM-09 1\nSTART_AT 2026-01-01T00:00:00Z\nSEED 9\nEND\n"))
                .version() == 1, "version=1 必须接受");
    }

    // ================= SCN-10 =================

    /** 唯一性与编号格式。 */
    private static void scn10() {
        expectErr(Path.of("scenarios/invalid/invalid-duplicate-event.scenario"),
                "SCENARIO_DUPLICATE_EVENT", "EVT-001", "重复 eventId");
        expectErr(writeText("scn10-dup-script.scenario",
                        "SCENARIO SIM-10 1\nSTART_AT 2026-01-01T00:00:00Z\nSEED 10\n"
                                + "EXTERNAL EXT-001 CREATE 1 ACCEPTED EXT-1\n"
                                + "EXTERNAL EXT-001 QUERY 1 ACCEPTED EXT-1\n"
                                + "END\n"),
                "SCENARIO_DUPLICATE_EVENT", "EXT-001", "重复 scriptId");
        expectErr(writeText("scn10-dup-oracle.scenario",
                        "SCENARIO SIM-10 1\nSTART_AT 2026-01-01T00:00:00Z\nSEED 10\n"
                                + "EXPECT ORC-001 CREATE_CALLS APP-001 1\n"
                                + "EXPECT ORC-001 QUERY_CALLS APP-001 0\n"
                                + "END\n"),
                "SCENARIO_DUPLICATE_EVENT", "ORC-001", "重复 oracleId");
        expectErr(writeText("scn10-bad-event-id.scenario",
                        "SCENARIO SIM-10 1\nSTART_AT 2026-01-01T00:00:00Z\nSEED 10\n"
                                + "AT 0 10 EVT@1 POINT_ADD P01 原料暂存点\nEND\n"),
                "SCENARIO_HEADER_MISSING", null, "eventId 格式非法");
    }

    // ================= SCN-11 =================

    /** 时间倒退。 */
    private static void scn11() {
        expectErr(INVALID_BACKWARD, "SCENARIO_EVENT_ORDER", "EVT-002", "场景文件的倒退");
        expectErr(writeText("scn11-backward.scenario",
                        "SCENARIO SIM-11 1\nSTART_AT 2026-01-01T00:00:00Z\nSEED 11\n"
                                + "AT 1000 10 EVT-001 POINT_ADD P01 原料暂存点\n"
                                + "AT 999 20 EVT-002 POINT_ADD P02 装配线接收点\n"
                                + "END\n"),
                "SCENARIO_EVENT_ORDER", "EVT-002", "offset 从 1000 退到 999");
    }

    // ================= SCN-12 =================

    /** 时间轴取值与同键重复。 */
    private static void scn12() {
        expectErr(writeText("scn12-neg-offset.scenario",
                        "SCENARIO SIM-12 1\nSTART_AT 2026-01-01T00:00:00Z\nSEED 12\n"
                                + "AT -1 10 EVT-001 POINT_ADD P01 原料暂存点\nEND\n"),
                "SCENARIO_EVENT_ORDER", null, "offset < 0");
        expectErr(writeText("scn12-zero-seq.scenario",
                        "SCENARIO SIM-12 1\nSTART_AT 2026-01-01T00:00:00Z\nSEED 12\n"
                                + "AT 0 0 EVT-001 POINT_ADD P01 原料暂存点\nEND\n"),
                "SCENARIO_EVENT_ORDER", null, "logicalSequence = 0");
        expectErr(writeText("scn12-same-key.scenario",
                        "SCENARIO SIM-12 1\nSTART_AT 2026-01-01T00:00:00Z\nSEED 12\n"
                                + "AT 0 10 EVT-001 POINT_ADD P01 原料暂存点\n"
                                + "AT 0 10 EVT-002 POINT_ADD P02 装配线接收点\n"
                                + "END\n"),
                "SCENARIO_EVENT_ORDER", "0/10", "同 (offset,sequence) 重复");
        ScenarioPackage ok = ScenarioParser.parse(writeText("scn12-same-offset.scenario",
                "SCENARIO SIM-12 1\nSTART_AT 2026-01-01T00:00:00Z\nSEED 12\n"
                        + "AT 0 10 EVT-001 POINT_ADD P01 原料暂存点\n"
                        + "AT 0 20 EVT-002 POINT_ADD P02 装配线接收点\n"
                        + "END\n"));
        require(ok.events().size() == 2, "同一 offset 不同 sequence 必须接受");
    }

    // ================= SCN-13 =================

    /** 脚本 ordinal 连续性。 */
    private static void scn13() {
        expectErr(writeText("scn13-from-zero.scenario",
                        "SCENARIO SIM-13 1\nSTART_AT 2026-01-01T00:00:00Z\nSEED 13\n"
                                + "EXTERNAL EXT-001 CREATE 0 ACCEPTED EXT-1\nEND\n"),
                "SCENARIO_SCRIPT_ORDINAL", null, "ordinal 从 0 开始");
        expectErr(writeText("scn13-gap.scenario",
                        "SCENARIO SIM-13 1\nSTART_AT 2026-01-01T00:00:00Z\nSEED 13\n"
                                + "EXTERNAL EXT-001 CREATE 1 ACCEPTED EXT-1\n"
                                + "EXTERNAL EXT-002 CREATE 3 ACCEPTED EXT-2\n"
                                + "END\n"),
                "SCENARIO_SCRIPT_ORDINAL", "CREATE", "CREATE 的 ordinal 1,3 不连续");
        expectErr(writeText("scn13-query-from-2.scenario",
                        "SCENARIO SIM-13 1\nSTART_AT 2026-01-01T00:00:00Z\nSEED 13\n"
                                + "EXTERNAL EXT-001 QUERY 2 NOT_FOUND -\nEND\n"),
                "SCENARIO_SCRIPT_ORDINAL", "QUERY", "QUERY 从 2 开始");
        ScenarioPackage ok = ScenarioParser.parse(writeText("scn13-two-ops.scenario",
                "SCENARIO SIM-13 1\nSTART_AT 2026-01-01T00:00:00Z\nSEED 13\n"
                        + "EXTERNAL EXT-001 CREATE 1 ACCEPTED EXT-1\n"
                        + "EXTERNAL EXT-002 QUERY 1 ACCEPTED EXT-1\n"
                        + "END\n"));
        require(ok.externalScripts().size() == 2, "CREATE 与 QUERY 各自从 1 连续必须接受");
    }

    // ================= SCN-14 =================

    /** 脚本 outcome 与外单号组合。 */
    private static void scn14() {
        expectErr(writeText("scn14-create-bad-outcome.scenario",
                        "SCENARIO SIM-14 1\nSTART_AT 2026-01-01T00:00:00Z\nSEED 14\n"
                                + "EXTERNAL EXT-001 CREATE 1 NOT_FOUND -\nEND\n"),
                "SCENARIO_UNKNOWN_ACTION", "NOT_FOUND", "CREATE 不允许 NOT_FOUND");
        expectErr(writeText("scn14-query-bad-outcome.scenario",
                        "SCENARIO SIM-14 1\nSTART_AT 2026-01-01T00:00:00Z\nSEED 14\n"
                                + "EXTERNAL EXT-001 QUERY 1 TIMEOUT_BUT_CREATED EXT-1\nEND\n"),
                "SCENARIO_UNKNOWN_ACTION", "TIMEOUT_BUT_CREATED", "QUERY 不允许超时类 outcome");
        expectErr(writeText("scn14-bad-op.scenario",
                        "SCENARIO SIM-14 1\nSTART_AT 2026-01-01T00:00:00Z\nSEED 14\n"
                                + "EXTERNAL EXT-001 CANCEL 1 ACCEPTED EXT-1\nEND\n"),
                "SCENARIO_UNKNOWN_ACTION", "CANCEL", "未知 operation");
        expectErr(writeText("scn14-accepted-no-id.scenario",
                        "SCENARIO SIM-14 1\nSTART_AT 2026-01-01T00:00:00Z\nSEED 14\n"
                                + "EXTERNAL EXT-001 CREATE 1 ACCEPTED -\nEND\n"),
                "SCENARIO_ARGUMENT_COUNT", "externalOrderId", "ACCEPTED 必须给外单号");
        expectErr(writeText("scn14-notfound-with-id.scenario",
                        "SCENARIO SIM-14 1\nSTART_AT 2026-01-01T00:00:00Z\nSEED 14\n"
                                + "EXTERNAL EXT-001 QUERY 1 NOT_FOUND EXT-1\nEND\n"),
                "SCENARIO_ARGUMENT_COUNT", "externalOrderId", "NOT_FOUND 必须是 '-'");
        ScenarioPackage ok = ScenarioParser.parse(writeText("scn14-timeout-created.scenario",
                "SCENARIO SIM-14 1\nSTART_AT 2026-01-01T00:00:00Z\nSEED 14\n"
                        + "EXTERNAL EXT-001 CREATE 1 TIMEOUT_BUT_CREATED EXT-1\n"
                        + "EXTERNAL EXT-002 CREATE 2 TIMEOUT_NOT_CREATED -\n"
                        + "EXTERNAL EXT-003 QUERY 1 NOT_FOUND -\n"
                        + "END\n"));
        require(ok.externalScripts().size() == 3, "合法组合必须接受");
    }

    // ================= SCN-15 =================

    /** 动作参数数量与回调事件类型。 */
    private static void scn15() {
        expectErr(writeText("scn15-point-args.scenario",
                        "SCENARIO SIM-15 1\nSTART_AT 2026-01-01T00:00:00Z\nSEED 15\n"
                                + "AT 0 10 EVT-001 POINT_ADD P01\nEND\n"),
                "SCENARIO_ARGUMENT_COUNT", "POINT_ADD", "POINT_ADD 少一个参数");
        expectErr(writeText("scn15-outbox-args.scenario",
                        "SCENARIO SIM-15 1\nSTART_AT 2026-01-01T00:00:00Z\nSEED 15\n"
                                + "AT 0 10 EVT-001 OUTBOX_PROCESS X\nEND\n"),
                "SCENARIO_ARGUMENT_COUNT", "OUTBOX_PROCESS", "OUTBOX_PROCESS 不接受参数");
        expectErr(writeText("scn15-bad-callback.scenario",
                        "SCENARIO SIM-15 1\nSTART_AT 2026-01-01T00:00:00Z\nSEED 15\n"
                                + "AT 0 10 EVT-001 APPLICATION_CALLBACK APP-001 BOGUS\nEND\n"),
                "SCENARIO_UNKNOWN_ACTION", "BOGUS", "回调事件类型非法");
        expectErr(writeText("scn15-bad-action.scenario",
                        "SCENARIO SIM-15 1\nSTART_AT 2026-01-01T00:00:00Z\nSEED 15\n"
                                + "AT 0 10 EVT-001 FLY_UP P01\nEND\n"),
                "SCENARIO_UNKNOWN_ACTION", "FLY_UP", "未知 action");
        expectErr(writeText("scn15-unknown-line.scenario",
                        "SCENARIO SIM-15 1\nSTART_AT 2026-01-01T00:00:00Z\nSEED 15\n"
                                + "WHATEVER 1\nEND\n"),
                "SCENARIO_UNKNOWN_ACTION", "WHATEVER", "未知记录类型");
    }

    // ================= SCN-16 =================

    /** END 与 EXPECT 取值。 */
    private static void scn16() {
        expectErr(writeText("scn16-no-end.scenario",
                        "SCENARIO SIM-16 1\nSTART_AT 2026-01-01T00:00:00Z\nSEED 16\n"
                                + "AT 0 10 EVT-001 POINT_ADD P01 原料暂存点\n"),
                "SCENARIO_END_MISSING", null, "缺 END");
        expectErr(writeText("scn16-end-not-last.scenario",
                        "SCENARIO SIM-16 1\nSTART_AT 2026-01-01T00:00:00Z\nSEED 16\nEND\n"
                                + "AT 0 10 EVT-001 POINT_ADD P01 原料暂存点\n"),
                "SCENARIO_END_MISSING", "not last", "END 不是最后一条");
        expectErr(writeText("scn16-end-args.scenario",
                        "SCENARIO SIM-16 1\nSTART_AT 2026-01-01T00:00:00Z\nSEED 16\nEND now\n"),
                "SCENARIO_ARGUMENT_COUNT", "END", "END 不接受参数");
        expectErr(writeText("scn16-negative-count.scenario",
                        "SCENARIO SIM-16 1\nSTART_AT 2026-01-01T00:00:00Z\nSEED 16\n"
                                + "EXPECT ORC-001 CREATE_CALLS APP-001 -1\nEND\n"),
                "SCENARIO_ARGUMENT_COUNT", "CREATE_CALLS", "COUNT 为负");
        expectErr(writeText("scn16-nonnumeric-count.scenario",
                        "SCENARIO SIM-16 1\nSTART_AT 2026-01-01T00:00:00Z\nSEED 16\n"
                                + "EXPECT ORC-001 QUERY_CALLS APP-001 x\nEND\n"),
                "SCENARIO_ARGUMENT_COUNT", "QUERY_CALLS", "COUNT 非整数");
        expectErr(writeText("scn16-bad-oracle-type.scenario",
                        "SCENARIO SIM-16 1\nSTART_AT 2026-01-01T00:00:00Z\nSEED 16\n"
                                + "EXPECT ORC-001 SOMETHING APP-001 1\nEND\n"),
                "SCENARIO_UNKNOWN_ORACLE", "SOMETHING", "未知 ruleType");
        expectErr(writeText("scn16-oracle-arg-count.scenario",
                        "SCENARIO SIM-16 1\nSTART_AT 2026-01-01T00:00:00Z\nSEED 16\n"
                                + "EXPECT ORC-001 NO_DUPLICATE_EXTERNAL_ORDER APP-001 EXTRA\nEND\n"),
                "SCENARIO_ARGUMENT_COUNT", "NO_DUPLICATE_EXTERNAL_ORDER", "EXPECT 参数数量错误");
    }

    // ================= 基础设施 =================

    private interface CaseBody {
        void run() throws Exception;
    }

    private interface Action {
        void run();
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

    private static Path writeText(String fileName, String content) {
        try {
            Path file = tmpDir.resolve(fileName);
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            throw new IllegalStateException("无法写临时场景 " + fileName, e);
        }
    }

    private static void expectErr(Path file, String code, String detail, String label) {
        try {
            ScenarioParser.parse(file);
        } catch (ScenarioValidationException e) {
            require(e.code().equals(code), label + "：code=" + e.code() + "，期望 " + code);
            if (detail != null) {
                require(e.detail().equals(detail), label + "：detail=" + e.detail() + "，期望 " + detail);
            }
            return;
        }
        throw new AssertionError(label + "：未抛出异常（期望 " + code + "）");
    }

    private static void expectError(Path file, String code, String label) {
        expectErr(file, code, null, label);
    }

    private static String errorDetail(Path file) {
        try {
            ScenarioParser.parse(file);
        } catch (ScenarioValidationException e) {
            return e.detail();
        }
        throw new AssertionError("期望抛出异常但没有：" + file);
    }

    private static void expectUnsupported(Action action, String label) {
        try {
            action.run();
        } catch (UnsupportedOperationException expected) {
            return;
        }
        throw new AssertionError(label + "：未抛 UnsupportedOperationException");
    }

    private static String sha256(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = digest.digest(data);
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            builder.append(String.format("%02x", b));
        }
        return builder.toString();
    }

    private static byte[] concat(byte[] left, byte[] right) {
        byte[] result = new byte[left.length + right.length];
        System.arraycopy(left, 0, result, 0, left.length);
        System.arraycopy(right, 0, result, left.length, right.length);
        return result;
    }

    private static byte[] concat(byte[] a, byte[] b, byte[] c) {
        return concat(concat(a, b), c);
    }

    private static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 清理失败不影响结果
                }
            });
        } catch (IOException ignored) {
            // 清理失败不影响结果
        }
    }
}
