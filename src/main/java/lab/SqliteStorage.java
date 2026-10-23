package lab;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite 存储：整状态单事务提交。
 *
 * <p>五张表（points / applications / outbox / inbox / workflows）的
 * 全量重写在同一个事务里完成：任一步失败整体回滚，不会留下"只更新了一半"的库。
 *
 * <p>实现 {@link AutoCloseable}，拥有连接的生命周期。
 *
 * <p>注意：只读模式的五层加固（文件必须是普通文件、{@code mode=ro} 连接、
 * 提交前抛 {@code STORAGE_READ_ONLY}、close 后字节比对）尚未在本类落地，
 * 见 docs/delivery-verification.md 的 T2 记录（T2b 待办）。
 */
public class SqliteStorage implements Storage, AutoCloseable {

    private static final List<Migration> MIGRATIONS = List.of(
            new Migration(1, "create core tables", List.of(
                    """
                    CREATE TABLE IF NOT EXISTS points (
                        point_id TEXT PRIMARY KEY,
                        name TEXT NOT NULL
                    )
                    """,
                    """
                    CREATE TABLE IF NOT EXISTS applications (
                        application_id TEXT PRIMARY KEY,
                        from_point_id TEXT NOT NULL,
                        to_point_id TEXT NOT NULL,
                        state TEXT NOT NULL,
                        external_order_id TEXT
                    )
                    """,
                    """
                    CREATE TABLE IF NOT EXISTS outbox (
                        entry_id TEXT PRIMARY KEY,
                        application_id TEXT NOT NULL,
                        from_point_id TEXT NOT NULL,
                        to_point_id TEXT NOT NULL,
                        status TEXT NOT NULL,
                        external_order_id TEXT,
                        attempts INTEGER NOT NULL
                    )
                    """,
                    """
                    CREATE TABLE IF NOT EXISTS inbox (
                        entry_id TEXT PRIMARY KEY,
                        application_id TEXT NOT NULL,
                        event_type TEXT NOT NULL,
                        status TEXT NOT NULL,
                        reason TEXT
                    )
                    """,
                    """
                    CREATE TABLE IF NOT EXISTS workflows (
                        instance_id TEXT PRIMARY KEY,
                        application_id TEXT NOT NULL,
                        state TEXT NOT NULL,
                        current_step_index INTEGER NOT NULL,
                        steps TEXT NOT NULL
                    )
                    """
            )),
            new Migration(2, "add note column to applications", List.of(
                    "ALTER TABLE applications ADD COLUMN note TEXT"
            ))
    );

    private final Connection conn;

    public SqliteStorage(Path dbFile) {
        this(dbFile, false);
    }

    /**
     * 打开 SQLite 数据库。
     *
     * @param dbFile   数据库文件路径
     * @param readOnly 只读模式：
     *                 - true：不创建目录、不创建文件、不执行 Migration；
     *                         目标不存在时抛 RegistryException。
     *                 - false：保持原有行为。
     */
    public SqliteStorage(Path dbFile, boolean readOnly) {
        try {
            if (readOnly) {
                // 只读：目标必须已经存在
                if (!Files.exists(dbFile)) {
                    throw new RegistryException("READ_ONLY_STORAGE_NOT_FOUND", "");
                }
                // 不创建目录、不创建文件、不执行 Migration
                Class.forName("org.sqlite.JDBC");
                this.conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toString());
            } else {
                // 读写：保持原行为
                if (dbFile.getParent() != null) {
                    Files.createDirectories(dbFile.getParent());
                }
                Class.forName("org.sqlite.JDBC");
                this.conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toString());
                MigrationRunner.run(this.conn, MIGRATIONS);
            }
        } catch (RegistryException e) {
            throw e;
        } catch (ClassNotFoundException | SQLException | IOException e) {
            throw new RuntimeException("cannot open sqlite db: " + dbFile, e);
        }
    }

    public static List<Migration> listMigrations() {
        return MIGRATIONS;
    }

    public int currentVersion() {
        try {
            return MigrationRunner.currentVersion(conn);
        } catch (SQLException e) {
            throw new RuntimeException("cannot read schema version", e);
        }
    }

    // ---------- Storage ----------

    @Override
    public synchronized RegistryState loadState() {
        return new RegistryState(
                readPoints(),
                readApplications(),
                readOutbox(),
                readInbox(),
                readWorkflows());
    }

    /**
     * 单事务提交整份状态：五张表先全删再全插，一起 commit。
     * 失败则 rollback，已提交状态不变。
     */
    @Override
    public synchronized void commitState(RegistryState next) {
        try {
            conn.setAutoCommit(false);
            try (Statement statement = conn.createStatement()) {
                statement.execute("DELETE FROM points");
                statement.execute("DELETE FROM applications");
                statement.execute("DELETE FROM outbox");
                statement.execute("DELETE FROM inbox");
                statement.execute("DELETE FROM workflows");
            }
            insertPoints(next.points());
            insertApplications(next.applications());
            insertOutbox(next.outbox());
            insertInbox(next.inbox());
            insertWorkflows(next.workflows());
            conn.commit();
        } catch (SQLException e) {
            rollbackQuietly();
            throw new RuntimeException("cannot commit state", e);
        } finally {
            setAutoCommitQuietly(true);
        }
    }

    @Override
    public synchronized void close() {
        try {
            conn.close();
        } catch (SQLException e) {
            throw new RuntimeException("cannot close sqlite db", e);
        }
    }

    // ---------- 读取 ----------

    private List<Point> readPoints() {
        List<Point> result = new ArrayList<>();
        try (Statement statement = conn.createStatement();
             ResultSet rs = statement.executeQuery("SELECT point_id, name FROM points")) {
            while (rs.next()) {
                result.add(new Point(rs.getString("point_id"), rs.getString("name")));
            }
        } catch (SQLException e) {
            throw new RuntimeException("cannot load points", e);
        }
        return result;
    }

    private List<TransferApplication> readApplications() {
        List<TransferApplication> result = new ArrayList<>();
        try (Statement statement = conn.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT application_id, from_point_id, to_point_id, state, external_order_id FROM applications")) {
            while (rs.next()) {
                ApplicationState state;
                try {
                    state = ApplicationState.valueOf(rs.getString("state"));
                } catch (IllegalArgumentException e) {
                    continue;
                }
                result.add(new TransferApplication(
                        rs.getString("application_id"),
                        rs.getString("from_point_id"),
                        rs.getString("to_point_id"),
                        state,
                        rs.getString("external_order_id")));
            }
        } catch (SQLException e) {
            throw new RuntimeException("cannot load applications", e);
        }
        return result;
    }

    private List<OutboxEntry> readOutbox() {
        List<OutboxEntry> result = new ArrayList<>();
        try (Statement statement = conn.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT entry_id, application_id, from_point_id, to_point_id, status, external_order_id, attempts FROM outbox")) {
            while (rs.next()) {
                OutboxStatus status;
                try {
                    status = OutboxStatus.valueOf(rs.getString("status"));
                } catch (IllegalArgumentException e) {
                    continue;
                }
                result.add(new OutboxEntry(
                        rs.getString("entry_id"),
                        rs.getString("application_id"),
                        rs.getString("from_point_id"),
                        rs.getString("to_point_id"),
                        status,
                        rs.getString("external_order_id"),
                        rs.getInt("attempts")));
            }
        } catch (SQLException e) {
            throw new RuntimeException("cannot load outbox", e);
        }
        return result;
    }

    private List<InboxEntry> readInbox() {
        List<InboxEntry> result = new ArrayList<>();
        try (Statement statement = conn.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT entry_id, application_id, event_type, status, reason FROM inbox")) {
            while (rs.next()) {
                InboxStatus status;
                try {
                    status = InboxStatus.valueOf(rs.getString("status"));
                } catch (IllegalArgumentException e) {
                    continue;
                }
                result.add(new InboxEntry(
                        rs.getString("entry_id"),
                        rs.getString("application_id"),
                        rs.getString("event_type"),
                        status,
                        rs.getString("reason")));
            }
        } catch (SQLException e) {
            throw new RuntimeException("cannot load inbox", e);
        }
        return result;
    }

    private List<WorkflowInstance> readWorkflows() {
        List<WorkflowInstance> result = new ArrayList<>();
        try (Statement statement = conn.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT instance_id, application_id, state, current_step_index, steps FROM workflows")) {
            while (rs.next()) {
                WorkflowState state;
                try {
                    state = WorkflowState.valueOf(rs.getString("state"));
                } catch (IllegalArgumentException e) {
                    continue;
                }
                List<WorkflowStep> steps = new ArrayList<>();
                boolean ok = true;
                for (String stepText : rs.getString("steps").split(",")) {
                    String[] kv = stepText.split(":", -1);
                    if (kv.length != 2) {
                        ok = false;
                        break;
                    }
                    WorkflowStepState stepState;
                    try {
                        stepState = WorkflowStepState.valueOf(kv[1]);
                    } catch (IllegalArgumentException e) {
                        ok = false;
                        break;
                    }
                    steps.add(new WorkflowStep(kv[0], stepState));
                }
                if (!ok) {
                    continue;
                }
                result.add(new WorkflowInstance(
                        rs.getString("instance_id"),
                        rs.getString("application_id"),
                        state,
                        rs.getInt("current_step_index"),
                        List.copyOf(steps)));
            }
        } catch (SQLException e) {
            throw new RuntimeException("cannot load workflows", e);
        }
        return result;
    }

    // ---------- 写入（只在 commitState 的事务里调用） ----------

    private void insertPoints(List<Point> points) throws SQLException {
        try (PreparedStatement statement = conn.prepareStatement(
                "INSERT INTO points (point_id, name) VALUES (?, ?)")) {
            for (Point p : points) {
                statement.setString(1, p.pointId());
                statement.setString(2, p.name());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertApplications(List<TransferApplication> applications) throws SQLException {
        try (PreparedStatement statement = conn.prepareStatement(
                "INSERT INTO applications (application_id, from_point_id, to_point_id, state, external_order_id) VALUES (?, ?, ?, ?, ?)")) {
            for (TransferApplication a : applications) {
                statement.setString(1, a.applicationId());
                statement.setString(2, a.fromPointId());
                statement.setString(3, a.toPointId());
                statement.setString(4, a.state().name());
                statement.setString(5, a.externalOrderId());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertOutbox(List<OutboxEntry> outbox) throws SQLException {
        try (PreparedStatement statement = conn.prepareStatement(
                "INSERT INTO outbox (entry_id, application_id, from_point_id, to_point_id, status, external_order_id, attempts) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            for (OutboxEntry e : outbox) {
                statement.setString(1, e.entryId());
                statement.setString(2, e.applicationId());
                statement.setString(3, e.fromPointId());
                statement.setString(4, e.toPointId());
                statement.setString(5, e.status().name());
                statement.setString(6, e.externalOrderId());
                statement.setInt(7, e.attempts());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertInbox(List<InboxEntry> inbox) throws SQLException {
        try (PreparedStatement statement = conn.prepareStatement(
                "INSERT INTO inbox (entry_id, application_id, event_type, status, reason) VALUES (?, ?, ?, ?, ?)")) {
            for (InboxEntry e : inbox) {
                statement.setString(1, e.entryId());
                statement.setString(2, e.applicationId());
                statement.setString(3, e.eventType());
                statement.setString(4, e.status().name());
                statement.setString(5, e.reason());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertWorkflows(List<WorkflowInstance> workflows) throws SQLException {
        try (PreparedStatement statement = conn.prepareStatement(
                "INSERT INTO workflows (instance_id, application_id, state, current_step_index, steps) VALUES (?, ?, ?, ?, ?)")) {
            for (WorkflowInstance wf : workflows) {
                StringBuilder steps = new StringBuilder();
                for (int i = 0; i < wf.steps().size(); i++) {
                    if (i > 0) {
                        steps.append(',');
                    }
                    WorkflowStep step = wf.steps().get(i);
                    steps.append(step.name()).append(':').append(step.state().name());
                }
                statement.setString(1, wf.instanceId());
                statement.setString(2, wf.applicationId());
                statement.setString(3, wf.state().name());
                statement.setInt(4, wf.currentStepIndex());
                statement.setString(5, steps.toString());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    // ---------- 事务辅助 ----------

    private void rollbackQuietly() {
        try {
            conn.rollback();
        } catch (SQLException ignored) {
            // 回滚失败只能保留原始异常；连接随后会被 setAutoCommit 复位
        }
    }

    private void setAutoCommitQuietly(boolean value) {
        try {
            conn.setAutoCommit(value);
        } catch (SQLException ignored) {
            // 连接已损坏时不再覆盖原始异常
        }
    }
}
