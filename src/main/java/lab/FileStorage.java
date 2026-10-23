package lab;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class FileStorage implements Storage {

    private final Path pointsFile;
    private final Path applicationsFile;
    private final Path outboxFile;
    private final Path inboxFile;
    private final Path workflowsFile;

    public FileStorage(Path dataDir) {
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new RuntimeException("cannot create data dir: " + dataDir, e);
        }
        this.pointsFile = dataDir.resolve("points.txt");
        this.applicationsFile = dataDir.resolve("applications.txt");
        this.outboxFile = dataDir.resolve("outbox.txt");
        this.inboxFile = dataDir.resolve("inbox.txt");
        this.workflowsFile = dataDir.resolve("workflows.txt");
    }

    @Override
    public List<Point> loadPoints() {
        List<Point> result = new ArrayList<>();
        if (!Files.exists(pointsFile)) {
            return result;
        }
        try (BufferedReader r = Files.newBufferedReader(pointsFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] parts = line.split("\\|", -1);
                if (parts.length != 3 || !parts[0].equals("P")) {
                    continue;
                }
                result.add(new Point(parts[1], parts[2]));
            }
        } catch (IOException e) {
            throw new RuntimeException("cannot load points", e);
        }
        return result;
    }

    @Override
    public List<TransferApplication> loadApplications() {
        List<TransferApplication> result = new ArrayList<>();
        if (!Files.exists(applicationsFile)) {
            return result;
        }
        try (BufferedReader r = Files.newBufferedReader(applicationsFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] parts = line.split("\\|", -1);
                if (parts.length != 6 || !parts[0].equals("A")) {
                    continue;
                }
                ApplicationState state;
                try {
                    state = ApplicationState.valueOf(parts[4]);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                String externalOrderId = parts[5].isEmpty() ? null : parts[5];
                result.add(new TransferApplication(parts[1], parts[2], parts[3], state, externalOrderId));
            }
        } catch (IOException e) {
            throw new RuntimeException("cannot load applications", e);
        }
        return result;
    }

    @Override
    public List<OutboxEntry> loadOutbox() {
        List<OutboxEntry> result = new ArrayList<>();
        if (!Files.exists(outboxFile)) {
            return result;
        }
        try (BufferedReader r = Files.newBufferedReader(outboxFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] parts = line.split("\\|", -1);
                if (parts.length != 8 || !parts[0].equals("O")) {
                    continue;
                }
                OutboxStatus status;
                try {
                    status = OutboxStatus.valueOf(parts[5]);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                String externalOrderId = parts[6].isEmpty() ? null : parts[6];
                int attempts;
                try {
                    attempts = Integer.parseInt(parts[7]);
                } catch (NumberFormatException e) {
                    continue;
                }
                result.add(new OutboxEntry(parts[1], parts[2], parts[3], parts[4],
                        status, externalOrderId, attempts));
            }
        } catch (IOException e) {
            throw new RuntimeException("cannot load outbox", e);
        }
        return result;
    }

    @Override
    public List<InboxEntry> loadInbox() {
        List<InboxEntry> result = new ArrayList<>();
        if (!Files.exists(inboxFile)) {
            return result;
        }
        try (BufferedReader r = Files.newBufferedReader(inboxFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] parts = line.split("\\|", -1);
                if (parts.length != 6 || !parts[0].equals("I")) {
                    continue;
                }
                InboxStatus status;
                try {
                    status = InboxStatus.valueOf(parts[4]);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                result.add(new InboxEntry(parts[1], parts[2], parts[3], status, parts[5]));
            }
        } catch (IOException e) {
            throw new RuntimeException("cannot load inbox", e);
        }
        return result;
    }

    @Override
    public List<WorkflowInstance> loadWorkflows() {
        List<WorkflowInstance> result = new ArrayList<>();
        if (!Files.exists(workflowsFile)) {
            return result;
        }
        try (BufferedReader r = Files.newBufferedReader(workflowsFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] parts = line.split("\\|", -1);
                if (parts.length != 6 || !parts[0].equals("W")) {
                    continue;
                }
                WorkflowState state;
                try {
                    state = WorkflowState.valueOf(parts[3]);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                int currentStepIndex;
                try {
                    currentStepIndex = Integer.parseInt(parts[4]);
                } catch (NumberFormatException e) {
                    continue;
                }
                List<WorkflowStep> steps = new ArrayList<>();
                boolean ok = true;
                for (String stepText : parts[5].split(",")) {
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
                result.add(new WorkflowInstance(parts[1], parts[2], state,
                        currentStepIndex, List.copyOf(steps)));
            }
        } catch (IOException e) {
            throw new RuntimeException("cannot load workflows", e);
        }
        return result;
    }

    @Override
    public void savePoints(List<Point> points) {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(pointsFile, StandardCharsets.UTF_8))) {
            for (Point p : points) {
                w.println("P|" + p.pointId() + "|" + p.name());
            }
        } catch (IOException e) {
            throw new RuntimeException("cannot save points", e);
        }
    }

    @Override
    public void saveApplications(List<TransferApplication> applications) {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(applicationsFile, StandardCharsets.UTF_8))) {
            for (TransferApplication a : applications) {
                String external = a.externalOrderId() == null ? "" : a.externalOrderId();
                w.println("A|" + a.applicationId() + "|" + a.fromPointId()
                        + "|" + a.toPointId() + "|" + a.state().name()
                        + "|" + external);
            }
        } catch (IOException e) {
            throw new RuntimeException("cannot save applications", e);
        }
    }

    @Override
    public void saveOutbox(List<OutboxEntry> outbox) {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(outboxFile, StandardCharsets.UTF_8))) {
            for (OutboxEntry e : outbox) {
                String external = e.externalOrderId() == null ? "" : e.externalOrderId();
                w.println("O|" + e.entryId() + "|" + e.applicationId()
                        + "|" + e.fromPointId() + "|" + e.toPointId()
                        + "|" + e.status().name()
                        + "|" + external
                        + "|" + e.attempts());
            }
        } catch (IOException e) {
            throw new RuntimeException("cannot save outbox", e);
        }
    }

    @Override
    public void saveInbox(List<InboxEntry> inbox) {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(inboxFile, StandardCharsets.UTF_8))) {
            for (InboxEntry e : inbox) {
                String reason = e.reason() == null ? "" : e.reason();
                w.println("I|" + e.entryId() + "|" + e.applicationId()
                        + "|" + e.eventType()
                        + "|" + e.status().name()
                        + "|" + reason);
            }
        } catch (IOException e) {
            throw new RuntimeException("cannot save inbox", e);
        }
    }

    @Override
    public void saveWorkflows(List<WorkflowInstance> workflows) {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(workflowsFile, StandardCharsets.UTF_8))) {
            for (WorkflowInstance wf : workflows) {
                StringBuilder steps = new StringBuilder();
                for (int i = 0; i < wf.steps().size(); i++) {
                    if (i > 0) {
                        steps.append(",");
                    }
                    WorkflowStep step = wf.steps().get(i);
                    steps.append(step.name()).append(":").append(step.state().name());
                }
                w.println("W|" + wf.instanceId() + "|" + wf.applicationId()
                        + "|" + wf.state().name()
                        + "|" + wf.currentStepIndex()
                        + "|" + steps);
            }
        } catch (IOException e) {
            throw new RuntimeException("cannot save workflows", e);
        }
    }
}
