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

public class SqliteStorage implements Storage {

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
        try {
            if (dbFile.getParent() != null) {
                Files.createDirectories(dbFile.getParent());
            }
            Class.forName("org.sqlite.JDBC");
            this.conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toString());
            MigrationRunner.run(this.conn, MIGRATIONS);
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

    @Override
    public List<Point> loadPoints() {
        List<Point> result = new ArrayList<>();
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT point_id, name FROM points")) {
            while (rs.next()) {
                result.add(new Point(rs.getString("point_id"), rs.getString("name")));
            }
        } catch (SQLException e) {
            throw new RuntimeException("cannot load points", e);
        }
        return result;
    }

    @Override
    public List<TransferApplication> loadApplications() {
        List<TransferApplication> result = new ArrayList<>();
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
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

    @Override
    public List<OutboxEntry> loadOutbox() {
        List<OutboxEntry> result = new ArrayList<>();
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
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

    @Override
    public List<InboxEntry> loadInbox() {
        List<InboxEntry> result = new ArrayList<>();
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
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

    @Override
    public List<WorkflowInstance> loadWorkflows() {
        List<WorkflowInstance> result = new ArrayList<>();
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
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

    @Override
    public void savePoints(List<Point> points) {
        try {
            conn.setAutoCommit(false);
            try (Statement s = conn.createStatement()) {
                s.execute("DELETE FROM points");
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO points (point_id, name) VALUES (?, ?)")) {
                for (Point p : points) {
                    ps.setString(1, p.pointId());
                    ps.setString(2, p.name());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            conn.commit();
        } catch (SQLException e) {
            rollbackQuietly();
            throw new RuntimeException("cannot save points", e);
        } finally {
            setAutoCommitQuietly(true);
        }
    }

    @Override
    public void saveApplications(List<TransferApplication> applications) {
        try {
            conn.setAutoCommit(false);
            try (Statement s = conn.createStatement()) {
                s.execute("DELETE FROM applications");
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO applications (application_id, from_point_id, to_point_id, state, external_order_id) VALUES (?, ?, ?, ?, ?)")) {
                for (TransferApplication a : applications) {
                    ps.setString(1, a.applicationId());
                    ps.setString(2, a.fromPointId());
                    ps.setString(3, a.toPointId());
                    ps.setString(4, a.state().name());
                    ps.setString(5, a.externalOrderId());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            conn.commit();
        } catch (SQLException e) {
            rollbackQuietly();
            throw new RuntimeException("cannot save applications", e);
        } finally {
            setAutoCommitQuietly(true);
        }
    }

    @Override
    public void saveOutbox(List<OutboxEntry> outbox) {
        try {
            conn.setAutoCommit(false);
            try (Statement s = conn.createStatement()) {
                s.execute("DELETE FROM outbox");
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO outbox (entry_id, application_id, from_point_id, to_point_id, status, external_order_id, attempts) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                for (OutboxEntry e : outbox) {
                    ps.setString(1, e.entryId());
                    ps.setString(2, e.applicationId());
                    ps.setString(3, e.fromPointId());
                    ps.setString(4, e.toPointId());
                    ps.setString(5, e.status().name());
                    ps.setString(6, e.externalOrderId());
                    ps.setInt(7, e.attempts());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            conn.commit();
        } catch (SQLException e) {
            rollbackQuietly();
            throw new RuntimeException("cannot save outbox", e);
        } finally {
            setAutoCommitQuietly(true);
        }
    }

    @Override
    public void saveInbox(List<InboxEntry> inbox) {
        try {
            conn.setAutoCommit(false);
            try (Statement s = conn.createStatement()) {
                s.execute("DELETE FROM inbox");
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO inbox (entry_id, application_id, event_type, status, reason) VALUES (?, ?, ?, ?, ?)")) {
                for (InboxEntry e : inbox) {
                    ps.setString(1, e.entryId());
                    ps.setString(2, e.applicationId());
                    ps.setString(3, e.eventType());
                    ps.setString(4, e.status().name());
                    ps.setString(5, e.reason());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            conn.commit();
        } catch (SQLException e) {
            rollbackQuietly();
            throw new RuntimeException("cannot save inbox", e);
        } finally {
            setAutoCommitQuietly(true);
        }
    }

    @Override
    public void saveWorkflows(List<WorkflowInstance> workflows) {
        try {
            conn.setAutoCommit(false);
            try (Statement s = conn.createStatement()) {
                s.execute("DELETE FROM workflows");
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO workflows (instance_id, application_id, state, current_step_index, steps) VALUES (?, ?, ?, ?, ?)")) {
                for (WorkflowInstance wf : workflows) {
                    StringBuilder steps = new StringBuilder();
                    for (int i = 0; i < wf.steps().size(); i++) {
                        if (i > 0) {
                            steps.append(",");
                        }
                        WorkflowStep step = wf.steps().get(i);
                        steps.append(step.name()).append(":").append(step.state().name());
                    }
                    ps.setString(1, wf.instanceId());
                    ps.setString(2, wf.applicationId());
                    ps.setString(3, wf.state().name());
                    ps.setInt(4, wf.currentStepIndex());
                    ps.setString(5, steps.toString());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            conn.commit();
        } catch (SQLException e) {
            rollbackQuietly();
            throw new RuntimeException("cannot save workflows", e);
        } finally {
            setAutoCommitQuietly(true);
        }
    }

    private void rollbackQuietly() {
        try {
            conn.rollback();
        } catch (SQLException ignored) {
        }
    }

    private void setAutoCommitQuietly(boolean value) {
        try {
            conn.setAutoCommit(value);
        } catch (SQLException ignored) {
        }
    }
}
