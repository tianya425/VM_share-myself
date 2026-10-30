package lab.simulation.resource;

/**
 * 资源领域标量校验（STU-SIM-02-R1 合同 §8.1）。
 *
 * <p>只做标量校验，绝不查询 Registry 或任何世界状态。ID 规则与仓库现有
 * {@code lab.Registry.ID_PATTERN} 逐字一致（{@code [A-Z][A-Z0-9_-]{0,31}}），
 * 不另造不一致的正则。所有异常信息至少包含 type（校验类型）、field（字段名）、
 * reason（原因）三段。
 */
public final class ResourceValidation {

    /** 与 {@code lab.Registry.ID_PATTERN} 逐字一致：大写字母开头，最多 32 个字符。 */
    private static final String ID_PATTERN = "[A-Z][A-Z0-9_-]{0,31}";

    private ResourceValidation() {
    }

    /**
     * 校验 ID 字段：非 null 且匹配既有 {@code ID_PATTERN}。
     */
    public static String requireId(String fieldName, String value) {
        if (value == null || !value.matches(ID_PATTERN)) {
            throw new IllegalArgumentException(
                    "type=ID field=" + fieldName + " reason=invalid value="
                            + (value == null ? "null" : value));
        }
        return value;
    }

    /**
     * 校验名称字段：trim 后长度必须在 [min, max] 内；返回规范化（trim）后的值。
     */
    public static String requireName(String fieldName, String value, int min, int max) {
        if (value == null) {
            throw new IllegalArgumentException("type=NAME field=" + fieldName + " reason=null");
        }
        String trimmed = value.trim();
        if (trimmed.length() < min || trimmed.length() > max) {
            throw new IllegalArgumentException(
                    "type=NAME field=" + fieldName + " reason=lengthOutOfRange length="
                            + trimmed.length() + " expected=" + min + ".." + max);
        }
        return trimmed;
    }

    /**
     * 校验引用字段：非 null。
     */
    public static <T> T requireNonNull(String fieldName, T value) {
        if (value == null) {
            throw new IllegalArgumentException("type=OBJECT field=" + fieldName + " reason=null");
        }
        return value;
    }

    /**
     * 校验数值字段：必须大于等于 0。
     */
    public static long requireNonNegative(String fieldName, long value) {
        if (value < 0) {
            throw new IllegalArgumentException(
                    "type=NUMBER field=" + fieldName + " reason=negative value=" + value);
        }
        return value;
    }

    /**
     * 校验数值字段：必须大于等于 1。
     */
    public static long requirePositive(String fieldName, long value) {
        if (value < 1) {
            throw new IllegalArgumentException(
                    "type=NUMBER field=" + fieldName + " reason=notPositive value=" + value);
        }
        return value;
    }
}
