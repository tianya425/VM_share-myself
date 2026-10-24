package lab;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * 执行数据库 Migration 的工具类。
 *
 * <p>一条 Migration 的所有 SQL 语句和 schema_version 更新
 * 处于同一事务；任一步失败时整体回滚，不留部分表、不前进版本。
 */
public class MigrationRunner {

    /**
     * 依次执行所有版本号大于当前版本的 Migration。
     *
     * @return 执行完成后的当前版本
     */
    public static int run(Connection conn, List<Migration> migrations) throws SQLException {
        ensureSchemaVersionTable(conn);
        int current = readCurrentVersion(conn);
        int target = migrations.stream().mapToInt(Migration::version).max().orElse(0);

        if (current >= target) {
            return current;
        }

        for (Migration m : migrations) {
            if (m.version() > current) {
                applyMigrationAtomically(conn, m);
                current = m.version();
            }
        }
        return current;
    }

    /**
     * 读取当前数据库版本。
     */
    public static int currentVersion(Connection conn) throws SQLException {
        ensureSchemaVersionTable(conn);
        return readCurrentVersion(conn);
    }

    /**
     * 确保 schema_version 表存在。该表只保存一行当前版本。
     */
    private static void ensureSchemaVersionTable(Connection conn) throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("""
                CREATE TABLE IF NOT EXISTS schema_version (
                    version INTEGER PRIMARY KEY
                )
                """);
        }
    }

    /**
     * 读取当前版本；表为空时返回 0。
     */
    private static int readCurrentVersion(Connection conn) throws SQLException {
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT version FROM schema_version")) {
            if (rs.next()) {
                return rs.getInt("version");
            }
            return 0;
        }
    }

    /**
     * 把一条 Migration 的所有语句和版本更新放在同一事务里。
     *
     * <p>任一步失败则整体回滚，不留部分表、不前进版本。
     * 失败既可能是 {@link SQLException}（SQL 错误），也可能是 {@link RuntimeException}
     * （驱动或迁移定义在运行期抛出）——两种都必须回滚；否则 finally 里恢复 autoCommit
     * 会把未完成的事务提交掉，留下"部分迁移"。
     *
     * <p>完成后恢复原 autoCommit 值。
     */
    private static void applyMigrationAtomically(Connection conn, Migration m) throws SQLException {
        boolean originalAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            // 1. 执行该 Migration 的全部语句
            try (Statement s = conn.createStatement()) {
                for (String sql : m.statements()) {
                    s.execute(sql);
                }
            }
            // 2. 更新版本
            try (Statement s = conn.createStatement()) {
                s.execute("DELETE FROM schema_version");
                s.execute("INSERT INTO schema_version (version) VALUES (" + m.version() + ")");
            }
            // 3. 提交
            conn.commit();
        } catch (SQLException | RuntimeException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(originalAutoCommit);
        }
    }
}
