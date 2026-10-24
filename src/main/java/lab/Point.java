package lab;

/**
 * 点位记录。
 *
 * @param pointId 点位编号（{@code [A-Z][A-Z0-9_-]{0,31}}）
 * @param name    点位名称（1—40 字符，不含空白）
 */
public record Point(String pointId, String name) {
}
