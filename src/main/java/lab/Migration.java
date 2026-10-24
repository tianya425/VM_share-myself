package lab;

import java.util.List;

/**
 * 一条数据库迁移：版本号、描述与按顺序执行的 SQL。
 *
 * @param version     目标版本（从 1 开始连续递增）
 * @param description 稳定描述（写入 {@code migration list} 输出）
 * @param statements  该版本要执行的 SQL 语句
 */
public record Migration(int version, String description, List<String> statements) {
}
