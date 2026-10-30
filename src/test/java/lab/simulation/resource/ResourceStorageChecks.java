package lab.simulation.resource;

import lab.FileStorage;
import lab.MemoryStorage;
import lab.Migration;
import lab.MigrationRunner;
import lab.Point;
import lab.RegistryState;
import lab.SqliteStorage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 资源领域的存储检查（PR4 / T3）：STO-01 — STO-06。
 *
 * <p>覆盖：内存/文件/SQLite 三类存储在同一 commitState 内保存"原有五类 + resourceWorld"；
 * SQLite Migration 3 与 schema_version 同事务、失败回滚；readonly 不跑 Migration；
 * 提交失败不留部分更新。输出 {@code RESOURCE_STORAGE_CASES_PASSED=6}。
 */
public final class ResourceStorageChecks {

    private static final int CASE_TOTAL = 6;

    private static int passed = 0;
    private static int failed = 0;
    private static Path tmpDir;

    private ResourceStorageChecks() {
    }

    public static void main(String[] args) {
        try {
            try {
                tmpDir = Files.createTempDirectory("resource-storage-checks-");
            } catch (Exception e) {
                throw new IllegalStateException("无法创建临时目录", e);
            }
            run("STO-01", "MemoryStorage：同一状态保存五类 + resourceWorld，读取一致且深不可变", ResourceStorageChecks::sto01);
            run("STO-02", "FileStorage：resources.txt 与五类文件同批原子落盘，重读规范快照一致", ResourceStorageChecks::sto02);
            run("STO-03", "SqliteStorage：Migration 3 后四张资源表读回一致，version=3", ResourceStorageChecks::sto03);
            run("STO-04", "Migration 3 注入失败：version 停在 2、四张表不存在、autoCommit 恢复", ResourceStorageChecks::sto04);
            run("STO-05", "readonly 不跑 Migration：v2 库只读打开不建表、字节不变，可写打开才升到 3", ResourceStorageChecks::sto05);
            run("STO-06", "同一 commitState：五类与资源世界一起提交；提交失败整体回滚", ResourceStorageChecks::sto06);
        } finally {
            deleteRecursively(tmpDir);
        }

        if (passed + failed != CASE_TOTAL) {
            throw new AssertionError("case 数量不符：passed+failed=" + (passed + failed)
                    + " 期望=" + CASE_TOTAL);
        }
        if (failed > 0) {
            System.out.println("RESOURCE_STORAGE_CASES_FAILED=" + failed);
            System.out.println("RESOURCE_STORAGE_CASES_PASSED=" + passed);
            System.exit(1);
        }
        System.out.println("RESOURCE_STORAGE_CASES_PASSED=" + passed);
    }

    // ================= STO-01 =================

    private static void sto01() {
        MemoryStorage storage = new MemoryStorage();
        ResourceWorldSnapshot world = sampleWorld();
        storage.commitState(sampleState(world));

        RegistryState loaded = storage.loadState();
        require(loaded.points().size() == 1, "points=" + loaded.points().size() + " 期望=1");
        require(loaded.resourceWorld().canonicalText().equals(world.canonicalText()),
                "MemoryStorage 读回的资源世界不一致：\n" + loaded.resourceWorld().canonicalText());

        Throwable immutable = capture(() -> loaded.resourceWorld().reservations().add(null));
        require(immutable instanceof UnsupportedOperationException,
                "快照 reservations 不是不可变列表：" + immutable);
        Throwable immutableDestinations = capture(() -> loaded.resourceWorld().destinations().clear());
        require(immutableDestinations instanceof UnsupportedOperationException,
                "快照 destinations 不是不可变列表：" + immutableDestinations);
    }

    // ================= STO-02 =================

    private static void sto02() throws Exception {
        Path dir = tmpDir.resolve("sto02-file");
        ResourceWorldSnapshot world = sampleWorld();
        FileStorage storage = new FileStorage(dir);
        storage.commitState(sampleState(world));

        require(Files.isRegularFile(dir.resolve("resources.txt")),
                "FileStorage 未写出 resources.txt");
        for (String name : List.of("points.txt", "applications.txt", "outbox.txt",
                "inbox.txt", "workflows.txt", "resources.txt")) {
            require(Files.isRegularFile(dir.resolve(name)), "缺少存储文件 " + name);
        }

        RegistryState loaded = new FileStorage(dir).loadState();
        require(loaded.points().size() == 1, "points=" + loaded.points().size() + " 期望=1");
        require(loaded.resourceWorld().canonicalText().equals(world.canonicalText()),
                "FileStorage 读回的资源世界不一致：\n" + loaded.resourceWorld().canonicalText());
    }

    // ================= STO-03 =================

    private static void sto03() {
        Path db = tmpDir.resolve("sto03.sqlite");
        ResourceWorldSnapshot world = sampleWorld();
        try (SqliteStorage storage = new SqliteStorage(db)) {
            require(storage.currentVersion() == 3,
                    "SqliteStorage currentVersion=" + storage.currentVersion() + " 期望=3");
            storage.commitState(sampleState(world));
        }
        try (SqliteStorage reopened = new SqliteStorage(db)) {
            RegistryState loaded = reopened.loadState();
            require(loaded.resourceWorld().canonicalText().equals(world.canonicalText()),
                    "SqliteStorage 读回的资源世界不一致：\n" + loaded.resourceWorld().canonicalText());
            require(loaded.points().size() == 1, "points=" + loaded.points().size() + " 期望=1");
        }
    }

    // ================= STO-04 =================

    private static void sto04() throws Exception {
        Path db = tmpDir.resolve("sto04.sqlite");
        Class.forName("org.sqlite.JDBC");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            boolean originalAutoCommit = conn.getAutoCommit();
            List<Migration> baseline = new ArrayList<>(SqliteStorage.listMigrations().subList(0, 2));
            MigrationRunner.run(conn, baseline);
            require(MigrationRunner.currentVersion(conn) == 2,
                    "前置：version=" + MigrationRunner.currentVersion(conn) + " 期望=2");

            List<String> statements = new ArrayList<>(SqliteStorage.listMigrations().get(2).statements());
            statements.add("CREATE TABLE broken (");
            List<Migration> bad = new ArrayList<>(baseline);
            bad.add(new Migration(3, "add resource tables", statements));

            Throwable thrown = captureChecked(() -> MigrationRunner.run(conn, bad));
            require(thrown != null, "注入的失败 Migration 3 未抛异常");
            require(MigrationRunner.currentVersion(conn) == 2,
                    "失败后 version=" + MigrationRunner.currentVersion(conn) + " 期望仍为 2");
            for (String table : List.of("destinations", "service_points", "virtual_resources", "reservations")) {
                require(!tableExists(conn, table), "失败回滚后表仍存在：" + table);
            }
            require(conn.getAutoCommit() == originalAutoCommit,
                    "autoCommit=" + conn.getAutoCommit() + " 期望恢复为 " + originalAutoCommit);
        }
    }

    // ================= STO-05 =================

    private static void sto05() throws Exception {
        Path db = tmpDir.resolve("sto05.sqlite");
        Class.forName("org.sqlite.JDBC");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            MigrationRunner.run(conn, SqliteStorage.listMigrations().subList(0, 2));
        }
        String beforeSha = sha256(Files.readAllBytes(db));
        long beforeSize = Files.size(db);
        long beforeMtime = Files.getLastModifiedTime(db).toMillis();

        try (SqliteStorage readOnly = new SqliteStorage(db, true)) {
            require(readOnly.currentVersion() == 2,
                    "只读 v2 库 currentVersion=" + readOnly.currentVersion() + " 期望=2");
            Throwable thrown = capture(readOnly::loadState);
            require(thrown != null, "v2 库只读 loadState 却成功（说明只读时跑了 Migration）");
        }

        require(sha256(Files.readAllBytes(db)).equals(beforeSha), "只读打开后库文件字节变了");
        require(Files.size(db) == beforeSize, "只读打开后库文件大小变了");
        require(Files.getLastModifiedTime(db).toMillis() == beforeMtime, "只读打开后库文件 mtime 变了");

        try (SqliteStorage writable = new SqliteStorage(db)) {
            require(writable.currentVersion() == 3,
                    "可写打开后 currentVersion=" + writable.currentVersion() + " 期望=3");
            for (String table : List.of("destinations", "service_points", "virtual_resources", "reservations")) {
                try (Connection probe = DriverManager.getConnection("jdbc:sqlite:" + db)) {
                    require(tableExists(probe, table), "Migration 3 后表不存在：" + table);
                }
            }
            ResourceWorldSnapshot world = sampleWorld();
            writable.commitState(sampleState(world));
            require(writable.loadState().resourceWorld().canonicalText().equals(world.canonicalText()),
                    "可写打开后资源世界读回不一致");
        }
    }

    // ================= STO-06 =================

    private static void sto06() {
        Path db = tmpDir.resolve("sto06.sqlite");
        ResourceWorldSnapshot world = sampleWorld();
        try (SqliteStorage storage = new SqliteStorage(db)) {
            storage.commitState(sampleState(world));
            RegistryState loaded = storage.loadState();
            require(loaded.points().size() == 1,
                    "points=" + loaded.points().size() + " 期望=1（五类与资源世界同一提交）");
            require(loaded.resourceWorld().canonicalText().equals(world.canonicalText()),
                    "资源世界与五类不在同一提交：\n" + loaded.resourceWorld().canonicalText());

            RegistryState duplicated = new RegistryState(
                    List.of(new Point("P01", "缓冲点"), new Point("P01", "重复点")),
                    List.of(), List.of(), List.of(), List.of(), world);
            Throwable thrown = capture(() -> storage.commitState(duplicated));
            require(thrown != null, "主键重复的提交未抛异常");

            RegistryState afterFailure = storage.loadState();
            require(afterFailure.points().size() == 1,
                    "提交失败后 points=" + afterFailure.points().size() + " 期望=1（整体回滚）");
            require(afterFailure.resourceWorld().canonicalText().equals(world.canonicalText()),
                    "提交失败后资源世界变了：\n" + afterFailure.resourceWorld().canonicalText());
        }
    }

    // ================= 夹具与基础设施 =================

    private static ResourceWorldSnapshot sampleWorld() {
        return new ResourceWorldSnapshot(
                List.of(new Destination("D01", "一号目的地")),
                List.of(new ServicePoint("SP01", "D01", "P01")),
                List.of(new VirtualResource("R01", "SP01")),
                List.of(new Reservation("RSV-APP-001", "APP-001", "D01", "SP01", "R01",
                        ReservationState.HELD, 0, 10)));
    }

    private static RegistryState sampleState(ResourceWorldSnapshot world) {
        return new RegistryState(List.of(new Point("P01", "缓冲点")),
                List.of(), List.of(), List.of(), List.of(), world);
    }

    private static boolean tableExists(Connection conn, String table) throws SQLException {
        try (Statement statement = conn.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table' AND name='" + table + "'")) {
            return rs.next();
        }
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(data);
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }

    private interface CaseBody {
        void run() throws Exception;
    }

    private interface Action {
        void run();
    }

    private interface CheckedAction {
        void run() throws Exception;
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

    private static Throwable capture(Action action) {
        try {
            action.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private static Throwable captureChecked(CheckedAction action) {
        try {
            action.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // 清理失败不影响结果
                }
            });
        } catch (Exception ignored) {
            // 清理失败不影响结果
        }
    }
}
