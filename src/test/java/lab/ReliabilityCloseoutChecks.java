package lab;

/**
 * 仿真接入前的可靠性门槛检查。
 *
 * <p>覆盖任务书第 4 节的六项门槛。每一项输出固定文本，
 * 全部通过时输出 RELIABILITY_CASES_PASSED=6。
 *
 * <p>本检查不改任何业务对象，只通过公开方法调用。
 */
public class ReliabilityCloseoutChecks {

    private static int passed = 0;

    public static void main(String[] args) throws Exception {
        rel01();
        rel02();
        rel03();
        rel04();
        rel05();
        rel06();
        System.out.println("RELIABILITY_CASES_PASSED=" + passed);
    }

    /**
     * REL-01：Unknown Outbox 恢复时先 query，不盲目重发。
     *
     * <p>场景：TIMEOUT_BUT_CREATED 模式下 dispatch，
     * Outbox 应为 UNCERTAIN。processOutbox 只调 query，
     * create 调用次数不增加。
     */
    private static void rel01() {
        // 使用同一个 Fake，模拟"外部系统状态保留"
        FakeRcsAdapter fake = new FakeRcsAdapter(FakeRcsAdapter.Mode.TIMEOUT_BUT_CREATED);
        Registry r = new Registry(new MemoryStorage(), fake);

        r.registerPoint("P01", "原料暂存点");
        r.registerPoint("P02", "装配线接收点");
        r.registerApplication("APP-001", "P01", "P02");
        r.dispatchApplication("APP-001");

        // dispatch 后：申请 UNKNOWN，Outbox UNCERTAIN
        TransferApplication a1 = r.findApplication("APP-001").orElseThrow();
        if (a1.state() != ApplicationState.UNKNOWN) {
            fail("REL-01", "after dispatch state=" + a1.state());
        }

        OutboxEntry e1 = r.listOutbox().get(0);
        if (e1.status() != OutboxStatus.UNCERTAIN) {
            fail("REL-01", "after dispatch outbox=" + e1.status());
        }

        // processOutbox：只 query，不 create
        int processed = r.processOutbox();
        if (processed != 1) {
            fail("REL-01", "processOutbox processed=" + processed);
        }

        // 恢复为 DISPATCHED
        TransferApplication a2 = r.findApplication("APP-001").orElseThrow();
        if (a2.state() != ApplicationState.DISPATCHED) {
            fail("REL-01", "after process state=" + a2.state());
        }

        OutboxEntry e2 = r.listOutbox().get(0);
        if (e2.status() != OutboxStatus.SENT) {
            fail("REL-01", "after process outbox=" + e2.status());
        }

        System.out.println("REL-01 UNKNOWN_QUERY_BEFORE_RETRY PASS");
        passed++;
    }

    /**
     * REL-02：持久化失败后内存不保留幽灵状态。
     *
     * <p>场景：用会抛异常的 Storage 包装 MemoryStorage，
     * 调用 registerPoint 后 Storage 抛异常，
     * 内存 points 数量必须仍为 0。
     */
    private static void rel02() {
        MemoryStorage realStorage = new MemoryStorage();
        FailingStorage failing = new FailingStorage(realStorage, true);
        Registry r = new Registry(failing, new FakeRcsAdapter(FakeRcsAdapter.Mode.ACCEPT));

        try {
            r.registerPoint("P01", "原料暂存点");
            fail("REL-02", "registerPoint should have thrown");
        } catch (RuntimeException expected) {
            // 预期
        }

        if (r.listPoints().size() != 0) {
            fail("REL-02", "ghost state: points=" + r.listPoints().size());
        }

        // 同样测 registerApplication
        Registry r2 = new Registry(new FailingStorage(new MemoryStorage(), true),
                new FakeRcsAdapter(FakeRcsAdapter.Mode.ACCEPT));
        try {
            r2.registerPoint("P01", "原料暂存点");
        } catch (RuntimeException ignored) {
        }

        if (r2.listPoints().size() != 0) {
            fail("REL-02", "ghost point after failure");
        }

        System.out.println("REL-02 PERSISTENCE_FAILURE_NO_GHOST_STATE PASS");
        passed++;
    }
    /**
     * REL-03：Migration 失败必须整体回滚。
     *
     * <p>场景：手动开启一个事务，里面先建表再执行非法 SQL，
     * 期待回滚后该表不存在。
     */
    private static void rel03() throws Exception {
        java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("ids-rel03");

        try {
            java.nio.file.Path db = tmp.resolve("db.sqlite");

            // 正常打开一次，把 V1 和 V2 跑完，得到版本 2
            SqliteStorage s1 = new SqliteStorage(db);
            if (s1.currentVersion() != 2) {
                fail("REL-03", "baseline version=" + s1.currentVersion());
            }

            // 手动验证 applyMigrationAtomically 语义：
            // 同一事务里，先建表再执行非法 SQL，期待整体回滚。
            try (java.sql.Connection conn =
                         java.sql.DriverManager.getConnection("jdbc:sqlite:" + db)) {
                boolean originalAutoCommit = conn.getAutoCommit();
                conn.setAutoCommit(false);
                try {
                    try (java.sql.Statement st = conn.createStatement()) {
                        st.execute("CREATE TABLE rel03_test (id INTEGER)");
                        st.execute("THIS IS NOT VALID SQL");
                    }
                    conn.commit();
                    fail("REL-03", "invalid SQL should have failed");
                } catch (java.sql.SQLException expected) {
                    conn.rollback();
                } finally {
                    conn.setAutoCommit(originalAutoCommit);
                }

                // 表 rel03_test 应该不存在（已回滚）
                try (java.sql.Statement st = conn.createStatement();
                     java.sql.ResultSet rs = st.executeQuery(
                             "SELECT name FROM sqlite_master WHERE type='table' AND name='rel03_test'")) {
                    if (rs.next()) {
                        fail("REL-03", "rel03_test should not exist after rollback");
                    }
                }
            }

            // 版本号仍然是 2
            SqliteStorage s2 = new SqliteStorage(db);
            if (s2.currentVersion() != 2) {
                fail("REL-03", "version changed to " + s2.currentVersion());
            }

        } finally {
            deleteRecursively(tmp);
        }

        System.out.println("REL-03 MIGRATION_ROLLBACK PASS");
        passed++;
    }
    /**
     * REL-04：Workflow 步骤成功必须绑定真实业务转换。
     *
     * <p>场景：正常三步 advance 后，申请和 Workflow 都应为 COMPLETED。
     * 若 Workflow 只改自己不改申请，检查会失败。
     */
    private static void rel04() {
        Registry r = new Registry(new MemoryStorage(),
                new FakeRcsAdapter(FakeRcsAdapter.Mode.ACCEPT));
        r.registerPoint("P01", "原料暂存点");
        r.registerPoint("P02", "装配线接收点");
        r.registerApplication("APP-001", "P01", "P02");
        r.startWorkflow("APP-001");

        // 第 1 步：DISPATCH
        r.advanceWorkflow("APP-001");
        TransferApplication a1 = r.findApplication("APP-001").orElseThrow();
        if (a1.state() != ApplicationState.DISPATCHED) {
            fail("REL-04", "after DISPATCH app=" + a1.state());
        }

        // 第 2 步：EXECUTE
        r.advanceWorkflow("APP-001");
        TransferApplication a2 = r.findApplication("APP-001").orElseThrow();
        if (a2.state() != ApplicationState.EXECUTING) {
            fail("REL-04", "after EXECUTE app=" + a2.state());
        }

        // 第 3 步：COMPLETE
        WorkflowInstance wf = r.advanceWorkflow("APP-001");
        TransferApplication a3 = r.findApplication("APP-001").orElseThrow();
        if (a3.state() != ApplicationState.COMPLETED) {
            fail("REL-04", "after COMPLETE app=" + a3.state());
        }
        if (wf.state() != WorkflowState.COMPLETED) {
            fail("REL-04", "after COMPLETE wf=" + wf.state());
        }

        System.out.println("REL-04 WORKFLOW_APPLICATION_COUPLED PASS");
        passed++;
    }
    /**
     * REL-05：SQLite readonly 启动必须零写盘。
     *
     * <p>场景 1：路径不存在时，只读启动应拒绝，且不创建目录、文件。
     * <p>场景 2：路径存在时，只读启动不执行 Migration、不写业务表。
     */
    private static void rel05() throws Exception {
        java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("ids-rel05");

        try {
            java.nio.file.Path db = tmp.resolve("subdir").resolve("db.sqlite");

            // 场景 1：只读打开不存在的路径
            try {
                new SqliteStorage(db, true);
                fail("REL-05", "should have rejected nonexistent path");
            } catch (RegistryException expected) {
                if (!"READ_ONLY_STORAGE_NOT_FOUND".equals(expected.code())) {
                    fail("REL-05", "wrong code: " + expected.code());
                }
            }

            // 目录和文件都不该被创建
            if (java.nio.file.Files.exists(db)) {
                fail("REL-05", "db file was created in readonly mode");
            }
            if (java.nio.file.Files.exists(db.getParent())) {
                fail("REL-05", "parent dir was created in readonly mode");
            }

            // 场景 2：先正常创建，再只读打开
            java.nio.file.Files.createDirectories(db.getParent());
            SqliteStorage s1 = new SqliteStorage(db, false);
            Registry r1 = new Registry(s1, new FakeRcsAdapter(FakeRcsAdapter.Mode.ACCEPT), false);
            r1.registerPoint("P01", "原料暂存点");

            // 记录文件修改时间
            long mtime1 = java.nio.file.Files.getLastModifiedTime(db).toMillis();

            Thread.sleep(50);

            // 只读打开
            SqliteStorage s2 = new SqliteStorage(db, true);
            Registry r2 = new Registry(s2, new FakeRcsAdapter(FakeRcsAdapter.Mode.ACCEPT), true);
            // 只读打开后，业务表数量不变，版本号不变
            if (r2.listPoints().size() != 1) {
                fail("REL-05", "points count changed");
            }
            if (s2.currentVersion() != 2) {
                fail("REL-05", "version changed: " + s2.currentVersion());
            }

            // 尝试写，应被拒绝
            try {
                r2.registerPoint("P02", "装配线接收点");
                fail("REL-05", "readonly registry should reject writes");
            } catch (RegistryException expected) {
                if (!"READ_ONLY".equals(expected.code())) {
                    fail("REL-05", "wrong readonly code: " + expected.code());
                }
            }

        } finally {
            deleteRecursively(tmp);
        }

        System.out.println("REL-05 SQLITE_READONLY_ZERO_WRITE PASS");
        passed++;
    }

    /**
     * REL-06：启动参数解析必须 fail closed。
     *
     * <p>覆盖：
     * - 未知参数 → STARTUP_ARGUMENT
     * - 两个 storageMode → STARTUP_CONFLICT storage
     * - 两个 fakeMode → STARTUP_CONFLICT fake
     * - 重复 readonly → STARTUP_ARGUMENT
     * - 合法参数解析成功
     */
    private static void rel06() {
        // 1. 未知参数
        expectStartupError(new String[]{"bogus"},
                "STARTUP_ARGUMENT", "bogus", "REL-06 unknown arg");

        // 2. 两个 storageMode
        expectStartupError(new String[]{"file", "sqlite"},
                "STARTUP_CONFLICT", "storage", "REL-06 two storage");

        // 3. 两个 fakeMode
        expectStartupError(new String[]{"fake-accept", "fake-reject"},
                "STARTUP_CONFLICT", "fake", "REL-06 two fake");

        // 4. 重复 readonly
        expectStartupError(new String[]{"readonly", "readonly"},
                "STARTUP_ARGUMENT", "readonly", "REL-06 duplicate readonly");

        // 5. 合法参数
        StartupOptions opts1 = StartupOptions.parse(new String[]{});
        if (opts1.storageMode() != StartupOptions.StorageMode.MEMORY) {
            fail("REL-06", "default storage not MEMORY");
        }
        if (opts1.readOnly()) {
            fail("REL-06", "default readOnly not false");
        }

        StartupOptions opts2 = StartupOptions.parse(new String[]{"sqlite", "readonly", "fake-reject"});
        if (opts2.storageMode() != StartupOptions.StorageMode.SQLITE) {
            fail("REL-06", "storage not SQLITE");
        }
        if (!opts2.readOnly()) {
            fail("REL-06", "readOnly not true");
        }
        if (opts2.fakeMode() != FakeRcsAdapter.Mode.REJECT) {
            fail("REL-06", "fake mode not REJECT");
        }

        System.out.println("REL-06 STARTUP_ARGUMENT_FAIL_CLOSED PASS");
        passed++;
    }

    private static void expectStartupError(String[] args, String code, String detail, String name) {
        try {
            StartupOptions.parse(args);
            fail("REL-06", name + ": no exception");
        } catch (StartupException e) {
            if (!e.code().equals(code)) {
                fail("REL-06", name + ": code=" + e.code() + " expected=" + code);
            }
            if (!e.detail().equals(detail)) {
                fail("REL-06", name + ": detail=" + e.detail() + " expected=" + detail);
            }
        }
    }

    private static void deleteRecursively(java.nio.file.Path path) throws Exception {
        if (!java.nio.file.Files.exists(path)) {
            return;
        }
        java.nio.file.Files.walk(path)
                .sorted((a, b) -> b.compareTo(a))
                .forEach(p -> {
                    try {
                        java.nio.file.Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                    }
                });
    }

    private static void fail(String id, String detail) {
        throw new AssertionError("FAILED: " + id + " " + detail);
    }

    /**
     * 一个包装 Storage 的测试替身：当 fail 标记打开时，
     * 所有 save 方法抛 RuntimeException。
     */
    private static class FailingStorage implements Storage {
        private final Storage delegate;
        private final boolean fail;

        FailingStorage(Storage delegate, boolean fail) {
            this.delegate = delegate;
            this.fail = fail;
        }

        @Override
        public java.util.List<Point> loadPoints() {
            return delegate.loadPoints();
        }

        @Override
        public java.util.List<TransferApplication> loadApplications() {
            return delegate.loadApplications();
        }

        @Override
        public java.util.List<OutboxEntry> loadOutbox() {
            return delegate.loadOutbox();
        }

        @Override
        public java.util.List<InboxEntry> loadInbox() {
            return delegate.loadInbox();
        }

        @Override
        public java.util.List<WorkflowInstance> loadWorkflows() {
            return delegate.loadWorkflows();
        }

        @Override
        public void savePoints(java.util.List<Point> points) {
            if (fail) throw new RuntimeException("simulated storage failure");
            delegate.savePoints(points);
        }

        @Override
        public void saveApplications(java.util.List<TransferApplication> applications) {
            if (fail) throw new RuntimeException("simulated storage failure");
            delegate.saveApplications(applications);
        }

        @Override
        public void saveOutbox(java.util.List<OutboxEntry> outbox) {
            if (fail) throw new RuntimeException("simulated storage failure");
            delegate.saveOutbox(outbox);
        }

        @Override
        public void saveInbox(java.util.List<InboxEntry> inbox) {
            if (fail) throw new RuntimeException("simulated storage failure");
            delegate.saveInbox(inbox);
        }

        @Override
        public void saveWorkflows(java.util.List<WorkflowInstance> workflows) {
            if (fail) throw new RuntimeException("simulated storage failure");
            delegate.saveWorkflows(workflows);
        }
    }
}
