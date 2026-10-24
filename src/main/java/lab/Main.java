package lab;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 终端命令入口：按启动参数选择存储与 Fake 模式，逐行读取标准输入并输出稳定结果行。
 *
 * <p>退出码：参数错误 2；存储打不开 1；命令循环正常结束（EOF / exit）0。
 * 存储资源用 try-with-resources 管理（SqliteStorage 必须 close）。
 */
public class Main {
    /** 解析启动参数并进入命令循环。 */
    public static void main(String[] args) throws Exception {
        BufferedReader in = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintWriter out = new PrintWriter(System.out, true, StandardCharsets.UTF_8);

        StartupOptions options;
        try {
            options = StartupOptions.parse(args);
        } catch (StartupException e) {
            out.println("ERR " + e.code()
                    + (e.detail().isEmpty() ? "" : " " + e.detail()));
            out.flush();
            System.exit(2);
            return;
        }

        Storage storage;
        try {
            storage = options.createStorage();
        } catch (RegistryException e) {
            out.println("ERR " + e.code());
            out.flush();
            System.exit(1);
            return;
        }

        // 用 try-with-resources 管住存储资源：SqliteStorage 持有数据库连接，必须 close()
        try (AutoCloseable resource = closeable(storage)) {
            RcsExecutionPort rcsPort = new FakeRcsAdapter(options.fakeMode());
            Registry registry = new Registry(storage, rcsPort, options.readOnly());

            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                String[] parts = line.split("[ \t]+");
                boolean keepGoing = handleLine(parts, registry, out);
                if (!keepGoing) {
                    return;
                }
            }
        }
    }

    /**
     * 把 Storage 适配成可关闭资源：实现 {@link AutoCloseable} 的（如 SqliteStorage）关它自己，
     * 其余（内存 / 文件）为空操作。
     */
    private static AutoCloseable closeable(Storage storage) {
        if (storage instanceof AutoCloseable resource) {
            return resource;
        }
        return () -> {
        };
    }

    private static boolean handleLine(String[] parts, Registry registry, PrintWriter out) {
        String cmd = parts[0];

        if (cmd.equals("help")) {
            if (parts.length != 1) {
                out.println("ERR USAGE");
            } else {
                out.println("COMMANDS point:add/show/list application:add/show/list/dispatch/query/callback/execute/complete/cancel outbox:list/process inbox:list workflow:start/advance/list/show stats health migration:status/list help exit");
            }
            return true;
        }

        if (cmd.equals("exit")) {
            if (parts.length != 1) {
                out.println("ERR USAGE");
                return true;
            }
            out.println("BYE");
            return false;
        }

        if (cmd.equals("point")) {
            return handlePoint(parts, registry, out);
        }

        if (cmd.equals("application")) {
            return handleApplication(parts, registry, out);
        }

        if (cmd.equals("outbox")) {
            return handleOutbox(parts, registry, out);
        }

        if (cmd.equals("inbox")) {
            return handleInbox(parts, registry, out);
        }

        if (cmd.equals("workflow")) {
            return handleWorkflow(parts, registry, out);
        }

        if (cmd.equals("stats")) {
            if (parts.length != 1) {
                out.println("ERR USAGE");
                return true;
            }
            printStats(out, registry.stats());
            return true;
        }

        if (cmd.equals("health")) {
            if (parts.length != 1) {
                out.println("ERR USAGE");
                return true;
            }
            printHealth(out, registry.health());
            return true;
        }

        if (cmd.equals("migration")) {
            return handleMigration(parts, registry, out);
        }

        out.println("ERR UNKNOWN_COMMAND");
        return true;
    }

    // ---------- point ----------

    private static boolean handlePoint(String[] parts, Registry registry, PrintWriter out) {
        if (parts.length < 2) {
            out.println("ERR USAGE");
            return true;
        }

        String sub = parts[1];

        switch (sub) {
            case "add" -> {
                if (parts.length != 4) {
                    out.println("ERR USAGE");
                    return true;
                }
                try {
                    Point p = registry.registerPoint(parts[2], parts[3]);
                    out.println("OK POINT_ADDED " + p.pointId());
                } catch (RegistryException e) {
                    printError(out, e);
                }
            }
            case "show" -> {
                if (parts.length != 3) {
                    out.println("ERR USAGE");
                    return true;
                }
                try {
                    Optional<Point> found = registry.findPoint(parts[2]);
                    if (found.isPresent()) {
                        printPoint(out, found.get());
                    } else {
                        out.println("ERR POINT_NOT_FOUND " + parts[2]);
                    }
                } catch (RegistryException e) {
                    printError(out, e);
                }
            }
            case "list" -> {
                if (parts.length != 2) {
                    out.println("ERR USAGE");
                    return true;
                }
                List<Point> all = registry.listPoints();
                out.println("POINTS " + all.size());
                for (Point p : all) {
                    printPoint(out, p);
                }
            }
            default -> out.println("ERR UNKNOWN_COMMAND");
        }
        return true;
    }

    // ---------- application ----------

    private static boolean handleApplication(String[] parts, Registry registry, PrintWriter out) {
        if (parts.length < 2) {
            out.println("ERR USAGE");
            return true;
        }

        String sub = parts[1];

        switch (sub) {
            case "add" -> {
                if (parts.length != 5) {
                    out.println("ERR USAGE");
                    return true;
                }
                try {
                    RegistrationResult result = registry.registerApplication(
                            parts[2], parts[3], parts[4]);
                    if (result.replay()) {
                        out.println("OK APPLICATION_REPLAY " + result.application().applicationId());
                    } else {
                        out.println("OK APPLICATION_ADDED " + result.application().applicationId());
                    }
                } catch (RegistryException e) {
                    printError(out, e);
                }
            }
            case "show" -> {
                if (parts.length != 3) {
                    out.println("ERR USAGE");
                    return true;
                }
                try {
                    Optional<TransferApplication> found = registry.findApplication(parts[2]);
                    if (found.isPresent()) {
                        printApplication(out, found.get());
                    } else {
                        out.println("ERR APPLICATION_NOT_FOUND " + parts[2]);
                    }
                } catch (RegistryException e) {
                    printError(out, e);
                }
            }
            case "list" -> {
                if (parts.length != 2) {
                    out.println("ERR USAGE");
                    return true;
                }
                List<TransferApplication> all = registry.listApplications();
                out.println("APPLICATIONS " + all.size());
                for (TransferApplication a : all) {
                    printApplication(out, a);
                }
            }
            case "dispatch" -> handleTransition(parts, registry, out,
                    registry::dispatchApplication,
                    "OK APPLICATION_DISPATCHED", "OK APPLICATION_UNKNOWN");
            case "query" -> handleTransition(parts, registry, out,
                    registry::queryApplication,
                    "OK APPLICATION_DISPATCHED", "OK APPLICATION_DISPATCHED");
            case "callback" -> {
                if (parts.length != 4) {
                    out.println("ERR USAGE");
                    return true;
                }
                String applicationId = parts[2];
                String eventType = parts[3];
                try {
                    String expectedEntryId = "INB-" + applicationId + "-" + eventType;
                    boolean alreadyExists = registry.listInbox().stream()
                            .anyMatch(e -> e.entryId().equals(expectedEntryId));

                    InboxEntry entry = registry.receiveCallback(applicationId, eventType);

                    if (alreadyExists) {
                        out.println("OK CALLBACK_DUPLICATE " + applicationId + " " + eventType);
                    } else if (entry.status() == InboxStatus.PROCESSED) {
                        out.println("OK CALLBACK_RECEIVED " + applicationId + " " + eventType);
                    } else if ("UNKNOWN_APPLICATION".equals(entry.reason())) {
                        out.println("ERR CALLBACK_UNKNOWN_APPLICATION " + applicationId);
                    } else if ("INVALID_STATE_TRANSITION".equals(entry.reason())) {
                        out.println("ERR CALLBACK_INVALID_STATE_TRANSITION " + applicationId);
                    } else {
                        out.println("ERR CALLBACK_UNEXPECTED " + applicationId);
                    }
                } catch (RegistryException e) {
                    printError(out, e);
                }
            }
            case "execute" -> handleTransition(parts, registry, out,
                    registry::executeApplication,
                    "OK APPLICATION_EXECUTING", "OK APPLICATION_EXECUTING");
            case "complete" -> handleTransition(parts, registry, out,
                    registry::completeApplication,
                    "OK APPLICATION_COMPLETED", "OK APPLICATION_COMPLETED");
            case "cancel" -> handleTransition(parts, registry, out,
                    registry::cancelApplication,
                    "OK APPLICATION_CANCELLED", "OK APPLICATION_CANCELLED");
            default -> out.println("ERR UNKNOWN_COMMAND");
        }
        return true;
    }

    private interface TransitionOp {
        TransferApplication apply(String applicationId);
    }

    private static void handleTransition(String[] parts, Registry registry, PrintWriter out,
                                         TransitionOp op, String okPrefix, String unknownPrefix) {
        if (parts.length != 3) {
            out.println("ERR USAGE");
            return;
        }
        try {
            TransferApplication a = op.apply(parts[2]);
            if (a.state() == ApplicationState.UNKNOWN) {
                out.println(unknownPrefix + " " + a.applicationId());
            } else {
                out.println(okPrefix + " " + a.applicationId());
            }
        } catch (RegistryException e) {
            printError(out, e);
        }
    }

    // ---------- outbox ----------

    private static boolean handleOutbox(String[] parts, Registry registry, PrintWriter out) {
        if (parts.length < 2) {
            out.println("ERR USAGE");
            return true;
        }

        String sub = parts[1];

        switch (sub) {
            case "list" -> {
                if (parts.length != 2) {
                    out.println("ERR USAGE");
                    return true;
                }
                List<OutboxEntry> all = registry.listOutbox();
                out.println("OUTBOX " + all.size());
                for (OutboxEntry e : all) {
                    printOutbox(out, e);
                }
            }
            case "process" -> {
                if (parts.length != 2) {
                    out.println("ERR USAGE");
                    return true;
                }
                try {
                    int processed = registry.processOutbox();
                    out.println("OK OUTBOX_PROCESSED " + processed);
                } catch (RegistryException e) {
                    printError(out, e);
                }
            }
            default -> out.println("ERR UNKNOWN_COMMAND");
        }
        return true;
    }

    private static void printOutbox(PrintWriter out, OutboxEntry e) {
        String external = e.externalOrderId() == null ? "-" : e.externalOrderId();
        out.println("OUTBOX "
                + e.entryId() + " "
                + e.applicationId() + " "
                + e.fromPointId() + " "
                + e.toPointId() + " "
                + e.status().name() + " "
                + external + " "
                + e.attempts());
    }

    // ---------- inbox ----------

    private static boolean handleInbox(String[] parts, Registry registry, PrintWriter out) {
        if (parts.length < 2) {
            out.println("ERR USAGE");
            return true;
        }

        String sub = parts[1];

        switch (sub) {
            case "list" -> {
                if (parts.length != 2) {
                    out.println("ERR USAGE");
                    return true;
                }
                List<InboxEntry> all = registry.listInbox();
                out.println("INBOX " + all.size());
                for (InboxEntry e : all) {
                    printInbox(out, e);
                }
            }
            default -> out.println("ERR UNKNOWN_COMMAND");
        }
        return true;
    }

    private static void printInbox(PrintWriter out, InboxEntry e) {
        String reason = e.reason() == null || e.reason().isEmpty() ? "-" : e.reason();
        out.println("INBOX "
                + e.entryId() + " "
                + e.applicationId() + " "
                + e.eventType() + " "
                + e.status().name() + " "
                + reason);
    }

    // ---------- workflow ----------

    private static boolean handleWorkflow(String[] parts, Registry registry, PrintWriter out) {
        if (parts.length < 2) {
            out.println("ERR USAGE");
            return true;
        }

        String sub = parts[1];

        switch (sub) {
            case "start" -> {
                if (parts.length != 3) {
                    out.println("ERR USAGE");
                    return true;
                }
                try {
                    WorkflowInstance wf = registry.startWorkflow(parts[2]);
                    out.println("OK WORKFLOW_STARTED " + wf.instanceId());
                } catch (RegistryException e) {
                    printError(out, e);
                }
            }
            case "advance" -> {
                if (parts.length != 3) {
                    out.println("ERR USAGE");
                    return true;
                }
                try {
                    WorkflowInstance wf = registry.advanceWorkflow(parts[2]);
                    out.println("OK WORKFLOW_ADVANCED " + wf.instanceId());
                } catch (RegistryException e) {
                    printError(out, e);
                }
            }
            case "show" -> {
                if (parts.length != 3) {
                    out.println("ERR USAGE");
                    return true;
                }
                try {
                    Optional<WorkflowInstance> found = registry.findWorkflow(parts[2]);
                    if (found.isPresent()) {
                        printWorkflow(out, found.get());
                    } else {
                        out.println("ERR WORKFLOW_NOT_FOUND " + parts[2]);
                    }
                } catch (RegistryException e) {
                    printError(out, e);
                }
            }
            case "list" -> {
                if (parts.length != 2) {
                    out.println("ERR USAGE");
                    return true;
                }
                List<WorkflowInstance> all = registry.listWorkflows();
                out.println("WORKFLOWS " + all.size());
                for (WorkflowInstance wf : all) {
                    printWorkflow(out, wf);
                }
            }
            default -> out.println("ERR UNKNOWN_COMMAND");
        }
        return true;
    }

    private static void printWorkflow(PrintWriter out, WorkflowInstance wf) {
        out.println("WORKFLOW "
                + wf.instanceId() + " "
                + wf.applicationId() + " "
                + wf.state().name() + " "
                + wf.currentStepIndex() + "/" + wf.steps().size());
    }

    // ---------- stats ----------

    private static void printStats(PrintWriter out, Stats s) {
        out.println("STATS points " + s.points());
        out.println("STATS applications " + s.applications());
        out.println("STATS applications_by_state " + formatMap(s.applicationsByState()));
        out.println("STATS outbox " + s.outbox());
        out.println("STATS outbox_by_status " + formatMap(s.outboxByStatus()));
        out.println("STATS inbox " + s.inbox());
        out.println("STATS inbox_by_status " + formatMap(s.inboxByStatus()));
        out.println("STATS workflows " + s.workflows());
        out.println("STATS workflows_by_state " + formatMap(s.workflowsByState()));
    }

    private static String formatMap(java.util.Map<String, Integer> map) {
        java.util.TreeMap<String, Integer> sorted = new java.util.TreeMap<>(map);
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (java.util.Map.Entry<String, Integer> e : sorted.entrySet()) {
            if (!first) {
                sb.append(" ");
            }
            sb.append(e.getKey()).append("=").append(e.getValue());
            first = false;
        }
        return sb.toString();
    }

    // ---------- health ----------

    private static void printHealth(PrintWriter out, HealthReport h) {
        out.println("HEALTH mode " + h.mode());
        out.println("HEALTH storage " + h.storageStatus());
        out.println("HEALTH points " + h.points());
        out.println("HEALTH applications " + h.applications()
                + " unknown=" + h.unknownApplications());
out.println("HEALTH outbox total=" + h.outboxTotal()
        + " pending=" + h.outboxPending()
        + " uncertain=" + h.outboxUncertain()
        + " failed=" + h.outboxFailed());
        out.println("HEALTH inbox total=" + h.inboxTotal()
                + " pending=" + h.inboxPending());
        out.println("HEALTH workflows total=" + h.workflowsTotal()
                + " running=" + h.workflowsRunning());
        out.println("HEALTH OVERALL " + h.overall());
    }
    // ---------- migration ----------

    private static boolean handleMigration(String[] parts, Registry registry, PrintWriter out) {
        if (parts.length < 2) {
            out.println("ERR USAGE");
            return true;
        }

        String sub = parts[1];

        switch (sub) {
            case "status" -> {
                if (parts.length != 2) {
                    out.println("ERR USAGE");
                    return true;
                }
                int v = registry.migrationVersion();
                if (v < 0) {
                    out.println("MIGRATION status UNSUPPORTED");
                } else {
                    out.println("MIGRATION status version=" + v);
                }
            }
            case "list" -> {
                if (parts.length != 2) {
                    out.println("ERR USAGE");
                    return true;
                }
                List<Migration> all = registry.migrationList();
                out.println("MIGRATION list " + all.size());
                for (Migration m : all) {
                    out.println("MIGRATION "
                            + m.version() + " "
                            + m.description());
                }
            }
            default -> out.println("ERR UNKNOWN_COMMAND");
        }
        return true;
    }

    // ---------- printers ----------

    private static void printPoint(PrintWriter out, Point p) {
        out.println("POINT " + p.pointId() + " " + p.name());
    }

    private static void printApplication(PrintWriter out, TransferApplication a) {
        String external = a.externalOrderId() == null ? "-" : a.externalOrderId();
        out.println("APPLICATION "
                + a.applicationId() + " "
                + a.fromPointId() + " "
                + a.toPointId() + " "
                + a.state().name() + " "
                + external);
    }

    private static void printError(PrintWriter out, RegistryException e) {
        if (e.detail() == null || e.detail().isEmpty()) {
            out.println("ERR " + e.code());
        } else {
            out.println("ERR " + e.code() + " " + e.detail());
        }
    }
}
