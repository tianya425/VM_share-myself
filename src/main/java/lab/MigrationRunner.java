package lab;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

public class MigrationRunner {

    public static int run(Connection conn, List<Migration> migrations) throws SQLException {
        ensureSchemaVersionTable(conn);
        int current = readCurrentVersion(conn);
        int target = migrations.stream().mapToInt(Migration::version).max().orElse(0);

        if (current >= target) {
            return current;
        }

        for (Migration m : migrations) {
            if (m.version() > current) {
                applyMigration(conn, m);
                updateVersion(conn, m.version());
                current = m.version();
            }
        }
        return current;
    }

    public static int currentVersion(Connection conn) throws SQLException {
        ensureSchemaVersionTable(conn);
        return readCurrentVersion(conn);
    }

    private static void ensureSchemaVersionTable(Connection conn) throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("""
                CREATE TABLE IF NOT EXISTS schema_version (
                    version INTEGER PRIMARY KEY
                )
                """);
        }
    }

    private static int readCurrentVersion(Connection conn) throws SQLException {
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT version FROM schema_version")) {
            if (rs.next()) {
                return rs.getInt("version");
            }
            return 0;
        }
    }

    private static void applyMigration(Connection conn, Migration m) throws SQLException {
        try (Statement s = conn.createStatement()) {
            for (String sql : m.statements()) {
                s.execute(sql);
            }
        }
    }

    private static void updateVersion(Connection conn, int version) throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("DELETE FROM schema_version");
            s.execute("INSERT INTO schema_version (version) VALUES (" + version + ")");
        }
    }
}
