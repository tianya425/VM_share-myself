package lab;

import lab.simulation.resource.Destination;
import lab.simulation.resource.Reservation;
import lab.simulation.resource.ReservationState;
import lab.simulation.resource.ResourceAvailability;
import lab.simulation.resource.ResourceWorldSnapshot;
import lab.simulation.resource.ServicePoint;
import lab.simulation.resource.VirtualResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.sqlite.SQLiteConfig;

/**
 * SQLite 存储：整状态单事务提交。
 *
 * <p>五张表（points / applications / outbox / inbox / workflows）的
 * 全量重写在同一个事务里完成：任一步失败整体回滚，不会留下"只更新了一半"的库。
 *
 * <p>实现 {@link AutoCloseable}，拥有连接的生命周期。
 *
 * <p>只读模式五层保护：
 * <ol>
 *   <li>目标必须已存在，且是普通文件（{@code NOFOLLOW_LINKS}，目录与符号链接一律拒绝）；</li>
 *   <li>连接用 {@code SQLITE_OPEN_READONLY}（{@link SQLiteConfig#setReadOnly});</li>
 *   <li>不执行 Migration；</li>
 *   <li>{@link #commitState} 与兼容 {@code save*} 在写任何 SQL 之前抛 {@code STORAGE_READ_ONLY}；</li>
 *   <li>只读打开/尝试写/关闭之后，库文件与 sidecar 的 bytes+size+mtime 不变（由调用方断言）。</li>
 * </ol>
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
            )),
            new Migration(3, "add resource tables", List.of(
                    """
                    CREATE TABLE IF NOT EXISTS destinations (
                        destination_id TEXT PRIMARY KEY,
                        name TEXT NOT NULL
                    )
                    """,
                    """
                    CREATE TABLE IF NOT EXISTS service_points (
                        service_point_id TEXT PRIMARY KEY,
                        destination_id TEXT NOT NULL,
                        registry_point_id TEXT NOT NULL,
                        name TEXT NOT NULL
                    )
                    """,
                    """
                    CREATE TABLE IF NOT EXISTS virtual_resources (
                        resource_id TEXT PRIMARY KEY,
                        service_point_id TEXT NOT NULL,
                        availability TEXT NOT NULL
                    )
                    """,
                    """
                    CREATE TABLE IF NOT EXISTS reservations (
                        reservation_id TEXT PRIMARY KEY,
                        application_id TEXT NOT NULL,
                        destination_id TEXT NOT NULL,
                        service_point_id TEXT NOT NULL,
                        resource_id TEXT NOT NULL,
                        state TEXT NOT NULL,
                        offset_millis INTEGER NOT NULL,
                        logical_sequence INTEGER NOT NULL
                    )
                    """
            ))
    );

    private final Path dbFile;
    private final Connection conn;
    private final boolean readOnly;

    public SqliteStorage(Path dbFile) {
        this(dbFile, false);
    }

    /**
     * 打开 SQLite 数据库。
     *
     * @param dbFile   数据库文件路径
     * @param readOnly 只读模式：
     *                 - true：目标必须已存在且是普通文件；不创建目录/文件；不执行 Migration；
     *                         连接本身以只读方式打开；写入在 SQL 之前被拒绝。
     *                 - false：保持原有行为。
     */
    public SqliteStorage(Path dbFile, boolean readOnly) {
        this.dbFile = dbFile;
        this.readOnly = readOnly;
        try {
            if (readOnly) {
                // 第 1 层：目标必须已存在且是普通文件；目录、符号链接（NOFOLLOW）一律按"找不到"处理
                if (!Files.isRegularFile(dbFile, LinkOption.NOFOLLOW_LINKS)) {
                    throw new RegistryException("READ_ONLY_STORAGE_NOT_FOUND", "");
                }
                // 第 2 层：只读连接（SQLITE_OPEN_READONLY）
                Class.forName("org.sqlite.JDBC");
                SQLiteConfig config = new SQLiteConfig();
                config.setReadOnly(true);
                this.conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile, config.toProperties());
                // 第 3 层：只读打开不执行 Migration
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
        if (readOnly) {
            // 只读连接上不能建表：版本号只查询，schema_version 不存在视为 0
            return readVersionWithoutDdl();
        }
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
                readWorkflows(),
                readResourceWorld());
    }

    /**
     * 单事务提交整份状态：五张表 + 四张资源表先全删再全插，一起 commit。
     * 失败则 rollback，已提交状态不变。
     */
    @Override
    public synchronized void commitState(RegistryState next) {
        checkWritable(); // 第 4 层：写任何 SQL 之前就拒绝
        try {
            conn.setAutoCommit(false);
            try (Statement statement = conn.createStatement()) {
                statement.execute("DELETE FROM points");
                statement.execute("DELETE FROM applications");
                statement.execute("DELETE FROM outbox");
                statement.execute("DELETE FROM inbox");
                statement.execute("DELETE FROM workflows");
                statement.execute("DELETE FROM destinations");
                statement.execute("DELETE FROM service_points");
                statement.execute("DELETE FROM virtual_resources");
                statement.execute("DELETE FROM reservations");
            }
            insertPoints(next.points());
            insertApplications(next.applications());
            insertOutbox(next.outbox());
            insertInbox(next.inbox());
            insertWorkflows(next.workflows());
            insertResourceWorld(next.resourceWorld());
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
            throw new RuntimeException("cannot close sqlite db: " + dbFile, e);
        }
    }

    // ---------- 只读保护（第 4 层） ----------

    /**
     * 兼容委托也必须先拒写，不能在 loadState 的读 SQL 之后才发现是只读。
     */
    @Override
    public void savePoints(List<Point> points) {
        checkWritable();
        Storage.super.savePoints(points);
    }

    @Override
    public void saveApplications(List<TransferApplication> applications) {
        checkWritable();
        Storage.super.saveApplications(applications);
    }

    @Override
    public void saveOutbox(List<OutboxEntry> outbox) {
        checkWritable();
        Storage.super.saveOutbox(outbox);
    }

    @Override
    public void saveInbox(List<InboxEntry> inbox) {
        checkWritable();
        Storage.super.saveInbox(inbox);
    }

    @Override
    public void saveWorkflows(List<WorkflowInstance> workflows) {
        checkWritable();
        Storage.super.saveWorkflows(workflows);
    }

    private void checkWritable() {
        if (readOnly) {
            throw new RegistryException("STORAGE_READ_ONLY", "");
        }
    }

    /**
     * 只读连接上的版本号查询：不建表、不写库。
     */
    private int readVersionWithoutDdl() {
        try (Statement statement = conn.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table' AND name='schema_version'")) {
            if (!rs.next()) {
                return 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("cannot read schema version", e);
        }
        try (Statement statement = conn.createStatement();
             ResultSet rs = statement.executeQuery("SELECT version FROM schema_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new RuntimeException("cannot read schema version", e);
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

    /**
     * 读资源世界。表结构由 Migration 3 建立；行内容不合法时 fail-closed。
     */
    private ResourceWorldSnapshot readResourceWorld() {
        List<Destination> destinations = new ArrayList<>();
        List<ServicePoint> servicePoints = new ArrayList<>();
        List<VirtualResource> resources = new ArrayList<>();
        List<Reservation> reservations = new ArrayList<>();
        try (Statement statement = conn.createStatement();
             ResultSet rs = statement.executeQuery("SELECT destination_id, name FROM destinations")) {
            while (rs.next()) {
                destinations.add(new Destination(rs.getString("destination_id"), rs.getString("name")));
            }
        } catch (SQLException e) {
            throw new RuntimeException("cannot load destinations", e);
        }
        try (Statement statement = conn.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT service_point_id, destination_id, registry_point_id, name FROM service_points")) {
            while (rs.next()) {
                servicePoints.add(new ServicePoint(
                        rs.getString("service_point_id"),
                        rs.getString("destination_id"),
                        rs.getString("registry_point_id"),
                        rs.getString("name")));
            }
        } catch (SQLException e) {
            throw new RuntimeException("cannot load service_points", e);
        }
        try (Statement statement = conn.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT resource_id, service_point_id, availability FROM virtual_resources")) {
            while (rs.next()) {
                resources.add(new VirtualResource(
                        rs.getString("resource_id"),
                        rs.getString("service_point_id"),
                        parseAvailability(rs.getString("availability"))));
            }
        } catch (SQLException e) {
            throw new RuntimeException("cannot load virtual_resources", e);
        }
        try (Statement statement = conn.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT reservation_id, application_id, destination_id, service_point_id, "
                             + "resource_id, state, offset_millis, logical_sequence FROM reservations")) {
            while (rs.next()) {
                reservations.add(new Reservation(
                        rs.getString("reservation_id"),
                        rs.getString("application_id"),
                        rs.getString("destination_id"),
                        rs.getString("service_point_id"),
                        rs.getString("resource_id"),
                        parseReservationState(rs.getString("state")),
                        rs.getLong("offset_millis"),
                        rs.getLong("logical_sequence")));
            }
        } catch (SQLException e) {
            throw new RuntimeException("cannot load reservations", e);
        }
        return new ResourceWorldSnapshot(destinations, servicePoints, resources, reservations);
    }

    private static ResourceAvailability parseAvailability(String value) {
        try {
            return ResourceAvailability.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("cannot load virtual_resources: availability=" + value, e);
        }
    }

    private static ReservationState parseReservationState(String value) {
        try {
            return ReservationState.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("cannot load reservations: state=" + value, e);
        }
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

    private void insertResourceWorld(ResourceWorldSnapshot world) throws SQLException {
        try (PreparedStatement statement = conn.prepareStatement(
                "INSERT INTO destinations (destination_id, name) VALUES (?, ?)")) {
            for (Destination destination : world.destinations()) {
                statement.setString(1, destination.destinationId());
                statement.setString(2, destination.name());
                statement.addBatch();
            }
            statement.executeBatch();
        }
        try (PreparedStatement statement = conn.prepareStatement(
                "INSERT INTO service_points (service_point_id, destination_id, registry_point_id, name) VALUES (?, ?, ?, ?)")) {
            for (ServicePoint servicePoint : world.servicePoints()) {
                statement.setString(1, servicePoint.servicePointId());
                statement.setString(2, servicePoint.destinationId());
                statement.setString(3, servicePoint.registryPointId());
                statement.setString(4, servicePoint.name());
                statement.addBatch();
            }
            statement.executeBatch();
        }
        try (PreparedStatement statement = conn.prepareStatement(
                "INSERT INTO virtual_resources (resource_id, service_point_id, availability) VALUES (?, ?, ?)")) {
            for (VirtualResource resource : world.resources()) {
                statement.setString(1, resource.resourceId());
                statement.setString(2, resource.servicePointId());
                statement.setString(3, resource.availability().name());
                statement.addBatch();
            }
            statement.executeBatch();
        }
        try (PreparedStatement statement = conn.prepareStatement(
                "INSERT INTO reservations (reservation_id, application_id, destination_id, service_point_id, resource_id, state, offset_millis, logical_sequence) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (Reservation reservation : world.reservations()) {
                statement.setString(1, reservation.reservationId());
                statement.setString(2, reservation.applicationId());
                statement.setString(3, reservation.destinationId());
                statement.setString(4, reservation.servicePointId());
                statement.setString(5, reservation.resourceId());
                statement.setString(6, reservation.state().name());
                statement.setLong(7, reservation.offsetMillis());
                statement.setLong(8, reservation.logicalSequence());
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
