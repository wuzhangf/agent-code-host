package com.sage.business.code.application;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sage.business.code.application.workspace.DefaultWorkspaceExecutionCoordinator;
import com.sage.business.code.application.workspace.WorkspaceExecutionCoordinator;
import com.sage.business.code.application.workspace.WorkspaceExecutionService;
import com.sage.business.code.contract.v2.workspace.WorkspaceExecutionResponse;
import com.sage.business.code.contract.ApprovalRequest;
import com.sage.business.code.contract.BusinessCodeTaskContract;
import com.sage.business.code.contract.RunRequest;
import com.sage.business.code.domain.ApprovedBusinessTask;
import com.sage.business.code.application.BusinessCodeExecutor;
import com.sage.business.code.domain.BusinessException;
import com.sage.business.code.domain.BusinessExecutionResult;
import com.sage.business.code.domain.BusinessRunContext;
import com.sage.business.code.domain.RunRecord;
import com.sage.business.code.domain.RunStatus;
import com.sage.business.code.domain.TaskRecord;
import com.sage.business.code.infrastructure.AtomicJsonStore;
import com.sage.business.code.infrastructure.BaselineContentStore;
import com.sage.business.code.infrastructure.GitWorktreeProvisioner;
import com.sage.business.code.infrastructure.WorkspaceRegistry;
import com.sage.business.code.infrastructure.allocator.WorkspaceResourceAllocator;
import com.sage.business.code.contract.v2.workspace.WorkspaceResourceKind;
import com.sage.business.code.infrastructure.attention.SqliteAttentionStore;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * L2 production-path acceptance for the dirty workspace closed loop
 * (design DW §5.3 / WU-DW-00..05). The composition is the same shape as
 * BusinessServiceFactory: TaskApplicationService wired with the workspace
 * execution coordinator, the workspace execution service, the baseline
 * content store and a deterministic fake executor — never the bare
 * TaskApplicationService double that silently misses the coordinator path.
 *
 * <p>Deterministic oracles only: file bytes, git status/index/HEAD, run
 * status, workspace execution sideEffectState and change artifact fields.
 * No model is involved; the fake executor is the deterministic stand-in for
 * the agent and its call count is asserted.</p>
 */
public class DirtyWorkspaceProductionPathTest {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private Path workspace;
    private TaskApplicationService tasks;
    private WorkspaceExecutionService executions;
    private WorkspaceResourceAllocator resources;
    private BaselineContentStore contents;
    private AtomicJsonStore stateStore;
    private final AtomicInteger modelCalls = new AtomicInteger();
    private final AtomicReference<String> executedRoot = new AtomicReference<String>();
    private volatile java.util.function.Consumer<WorkspaceExecutionCoordinator.RunWorkspaceRequest> prepareHook;

    @Before
    public void setUp() throws Exception {
        workspace = temp.newFolder("workspace").toPath();
        Files.createDirectories(workspace.resolve("src/main/java"));
        Files.createDirectories(workspace.resolve("docs"));
        Files.write(workspace.resolve("src/main/java/App.java"),
                "class App { int a = 1; }\n".getBytes(StandardCharsets.UTF_8));
        Files.write(workspace.resolve("docs/a.md"), "doc\n".getBytes(StandardCharsets.UTF_8));
        gitOut("init");
        gitOut("config", "user.email", "test@example.invalid");
        gitOut("config", "user.name", "test");
        gitOut("add", ".");
        gitOut("commit", "-m", "initial");
        // Pre-existing user WIP before the run starts (design DW-001).
        Files.write(workspace.resolve("src/main/java/App.java"),
                "class App { int a = 1; }\n// WIP\n".getBytes(StandardCharsets.UTF_8));
        Path state = temp.newFolder("state").toPath();
        stateStore = new AtomicJsonStore(state, MAPPER);
        AtomicJsonStore store = stateStore;
        WorkspaceRegistry registry = new WorkspaceRegistry(
                Collections.singletonMap("sample", workspace.toString()));
        contents = new BaselineContentStore(store);
        executions = new WorkspaceExecutionService(registry, store,
                new com.sage.business.code.infrastructure.WorkspaceLeaseManager(
                        state.resolve("workspaces")),
                new GitWorktreeProvisioner(), MAPPER, new SqliteAttentionStore(state),
                state.resolve("worktree-executions"), contents);
        resources = new WorkspaceResourceAllocator(state.resolve("resource-allocator"));
        final WorkspaceExecutionCoordinator coordinator =
                new DefaultWorkspaceExecutionCoordinator(executions, resources);
        // Delegating wrapper with a prepareRun hook so a test can land a
        // user cancel inside the dispatch window deterministically.
        WorkspaceExecutionCoordinator hooked = new WorkspaceExecutionCoordinator() {
            @Override public WorkspaceExecutionCoordinator.WorkspaceHandle prepareRun(
                    WorkspaceExecutionCoordinator.RunWorkspaceRequest request) {
                WorkspaceExecutionCoordinator.WorkspaceHandle handle = coordinator.prepareRun(request);
                java.util.function.Consumer<WorkspaceExecutionCoordinator.RunWorkspaceRequest> hook = prepareHook;
                if (hook != null) hook.accept(request);
                return handle;
            }
            @Override public WorkspaceExecutionCoordinator.WorkspaceHandle resumeRun(
                    WorkspaceExecutionCoordinator.RunWorkspaceRequest request, String workspaceExecutionId) {
                return coordinator.resumeRun(request, workspaceExecutionId);
            }
            @Override public void suspendRun(String workspaceExecutionId, String runId) {
                coordinator.suspendRun(workspaceExecutionId, runId);
            }
            @Override public WorkspaceExecutionCoordinator.WorkspaceHandle renew(
                    WorkspaceExecutionCoordinator.WorkspaceHandle handle) {
                return coordinator.renew(handle);
            }
            @Override public WorkspaceExecutionResponse handoff(
                    WorkspaceExecutionCoordinator.WorkspaceHandle handle) {
                return coordinator.handoff(handle);
            }
            @Override public WorkspaceExecutionResponse reclaim(
                    WorkspaceExecutionCoordinator.WorkspaceHandle handle) {
                return coordinator.reclaim(handle);
            }
            @Override public void assertCurrent(WorkspaceExecutionCoordinator.WorkspaceHandle handle) {
                coordinator.assertCurrent(handle);
            }
            @Override public WorkspaceResourceAllocator.Result acquireResource(
                    WorkspaceResourceAllocator.Request request) {
                return coordinator.acquireResource(request);
            }
            @Override public void releaseResource(WorkspaceResourceAllocator.Allocation allocation) {
                coordinator.releaseResource(allocation);
            }
        };
        BusinessCodeExecutor fake = new BusinessCodeExecutor() {
            @Override
            public BusinessExecutionResult execute(ApprovedBusinessTask task,
                    BusinessRunContext context) {
                modelCalls.incrementAndGet();
                executedRoot.set(task.workspace().getAbsolutePath());
                List<String> changed = new ArrayList<String>();
                try {
                    // The agent modifies the SAME file the user already modified.
                    Path app = task.workspace().toPath().resolve("src/main/java/App.java");
                    String current = new String(Files.readAllBytes(app), StandardCharsets.UTF_8);
                    Files.write(app, (current + "// RUN_DELTA\n").getBytes(StandardCharsets.UTF_8));
                    changed.add("src/main/java/App.java");
                    Path untracked = task.workspace().toPath().resolve("src/main/java/New.java");
                    if (Files.exists(untracked)) {
                        String body = new String(Files.readAllBytes(untracked), StandardCharsets.UTF_8);
                        Files.write(untracked, (body + "// RUN_DELTA\n").getBytes(StandardCharsets.UTF_8));
                        changed.add("src/main/java/New.java");
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                return new BusinessExecutionResult(RunStatus.SUCCEEDED,
                        "product-" + context.getRunId(), "ok", changed, null, null, null);
            }
        };
        tasks = new TaskApplicationService(store, new TaskCanonicalizer(registry, MAPPER),
                registry, fake, MAPPER, temp.getRoot().toPath().resolve("locks"),
                null, null, 1, 20, null, null, null, executions, hooked);
        tasks.setBaselineContentStore(contents);
        // Same evidence wiring as BusinessServiceFactory: the terminal change
        // artifact carries the pre-existing/overlap attribution (DW §6.5).
        tasks.addRunStateListener(new com.sage.business.code.application.run.RunChangeArtifactService(
                store, tasks,
                new com.sage.business.code.adapter.gui.WorkspaceDiffService(registry),
                registry, contents));
    }

    @After
    public void tearDown() {
        if (tasks != null) tasks.close();
    }

    @Test
    public void dw001DirtyTrackedFileCanBeModifiedAgain() throws Exception {
        String indexBefore = indexBlob("src/main/java/App.java");
        String headBefore = gitOut("rev-parse", "HEAD").trim();
        TaskRecord task = createAndApprove();
        RunRecord run = start(task);
        awaitTerminal(run.runId);
        RunRecord finished = tasks.run(run.runId);
        Assert.assertEquals(RunStatus.SUCCEEDED, finished.status);
        Assert.assertEquals(1, modelCalls.get());
        // The model executed against the materialized execution root, not the
        // user's checkout.
        Assert.assertTrue(executedRoot.get().replace('\\', '/')
                .contains("/worktree-executions/"));
        // The authoritative checkout composes pre-existing WIP with the delta.
        Assert.assertEquals("class App { int a = 1; }\n// WIP\n// RUN_DELTA\n",
                read(workspace.resolve("src/main/java/App.java")));
        // HEAD and index are untouched (INV-DW-06).
        Assert.assertEquals(headBefore, gitOut("rev-parse", "HEAD").trim());
        Assert.assertEquals(indexBefore, indexBlob("src/main/java/App.java"));
        // Run bookkeeping: changedFiles is the run delta, not the user's WIP.
        Assert.assertEquals(Collections.singletonList("src/main/java/App.java"),
                finished.changedFiles);
        // The execution record carries the authoritative commit evidence.
        Map<String, Object> record = stateStore.read(
                "workspace-executions/" + finished.workspaceExecutionId + ".json", Map.class);
        Assert.assertEquals("APPLIED", String.valueOf(record.get("sideEffectState")));
        // The change artifact separates pre-existing WIP from run delta (DW §6.5).
        Map<String, Object> artifact = stateStore.read(
                "runs/" + run.runId + "/change-artifact.json", Map.class);
        Assert.assertNotNull(artifact);
        Assert.assertTrue(((List<?>) artifact.get("preExistingFiles"))
                .contains("src/main/java/App.java"));
        Assert.assertTrue(((List<?>) artifact.get("runChangedFiles"))
                .contains("src/main/java/App.java"));
        Assert.assertTrue(((List<?>) artifact.get("overlapFiles"))
                .contains("src/main/java/App.java"));
        Assert.assertEquals("APPLIED", artifact.get("sideEffectState"));
    }

    @Test
    public void resourceBusyIsRetryableAndDoesNotInvokeProduct() throws Exception {
        TaskRecord task = createAndApproveCheckout("resource-busy");
        WorkspaceResourceAllocator.Result held = resources.acquire(
                new WorkspaceResourceAllocator.Request(WorkspaceResourceKind.PROJECT,
                        task.workspaceAlias, task.workspaceAlias, "TEST", "blocking-run", "blocking-run"));
        Assert.assertTrue(held.isAcquired());
        try {
            RunRecord run = start(task, "start-resource-busy");
            for (int i = 0; i < 300; i++) {
                if (tasks.run(run.runId).status == RunStatus.PAUSED_RETRYABLE) break;
                Thread.sleep(20);
            }
            RunRecord paused = tasks.run(run.runId);
            Assert.assertEquals(RunStatus.PAUSED_RETRYABLE, paused.status);
            Assert.assertEquals("RESOURCE_BUSY", paused.failureCode);
            Assert.assertEquals("WORKSPACE_RESOURCE_UNAVAILABLE", paused.recoveryReason);
            Assert.assertEquals(0, modelCalls.get());
        } finally {
            resources.release(held.getAllocation());
        }
    }

    @Test
    public void cancelDuringPrepareRunReleasesWorkspaceResource() throws Exception {
        TaskRecord task = createAndApproveCheckout("cancel-during-prepare");
        // Deterministic dispatch race: the user's cancel lands while the
        // dispatched worker is inside prepareRun, after the workspace
        // resource reservation but before the post-prepare status re-check.
        final AtomicBoolean cancelAttempted = new AtomicBoolean();
        prepareHook = new java.util.function.Consumer<WorkspaceExecutionCoordinator.RunWorkspaceRequest>() {
            @Override public void accept(WorkspaceExecutionCoordinator.RunWorkspaceRequest request) {
                cancelAttempted.set(true);
                try {
                    tasks.cancel(request.runId, "test-cancel-during-prepare");
                } catch (RuntimeException ignored) { }
            }
        };
        RunRecord run = start(task, "start-cancel-during-prepare");
        awaitTerminal(run.runId);
        RunRecord finished = tasks.run(run.runId);
        Assert.assertTrue("the cancel hook never ran", cancelAttempted.get());
        Assert.assertEquals(RunStatus.CANCELLED, finished.status);
        Assert.assertEquals("USER_CANCELLED", finished.terminationCode);
        Assert.assertEquals("a cancelled queued run must never reach the model",
                0, modelCalls.get());
        // The workspace resource must be free again; before the fix the
        // leaked handle kept the allocation ACTIVE and every later run on
        // this project paused on RESOURCE_BUSY.  The release runs on the
        // dispatched worker right after the status re-check, so poll until
        // it lands.
        WorkspaceResourceAllocator.Result next = null;
        for (int i = 0; i < 200; i++) {
            next = resources.acquire(
                    new WorkspaceResourceAllocator.Request(WorkspaceResourceKind.PROJECT,
                            task.workspaceAlias, task.workspaceAlias, "TEST", "next-run",
                            "next-run-" + i));
            if (next.isAcquired()) break;
            Thread.sleep(20);
        }
        Assert.assertNotNull(next);
        Assert.assertTrue("the cancelled run must release the project resource",
                next.isAcquired());
        resources.release(next.getAllocation());
    }

    @Test
    public void dw006OutOfScopeWipDoesNotBlockOrLeak() throws Exception {
        Files.write(workspace.resolve("docs/a.md"),
                "doc\n// user WIP\n".getBytes(StandardCharsets.UTF_8));
        TaskRecord task = createAndApprove();
        RunRecord run = start(task);
        awaitTerminal(run.runId);
        Assert.assertEquals(RunStatus.SUCCEEDED, tasks.run(run.runId).status);
        // The out-of-scope dirty file keeps its exact bytes; the run did not
        // fail because of unrelated WIP and did not write back that file.
        Assert.assertEquals("doc\n// user WIP\n", read(workspace.resolve("docs/a.md")));
        Assert.assertEquals("class App { int a = 1; }\n// WIP\n// RUN_DELTA\n",
                read(workspace.resolve("src/main/java/App.java")));
    }

    @Test
    public void dw003UntrackedFileStaysUntracked() throws Exception {
        Files.write(workspace.resolve("src/main/java/New.java"),
                "class New { int n = 0; }\n// WIP\n".getBytes(StandardCharsets.UTF_8));
        TaskRecord task = createAndApprove();
        RunRecord run = start(task);
        awaitTerminal(run.runId);
        Assert.assertEquals(RunStatus.SUCCEEDED, tasks.run(run.runId).status);
        Assert.assertEquals("class New { int n = 0; }\n// WIP\n// RUN_DELTA\n",
                read(workspace.resolve("src/main/java/New.java")));
        boolean untracked = false;
        for (String line : porcelain()) {
            if (line.startsWith("?? ") && line.endsWith("src/main/java/New.java")) untracked = true;
        }
        Assert.assertTrue("the run must never git add the user's untracked file", untracked);
    }

    @Test
    public void dw002SecondRunContinuesOnFirstRunResult() throws Exception {
        TaskRecord firstTask = createAndApprove();
        RunRecord first = start(firstTask);
        awaitTerminal(first.runId);
        Assert.assertEquals(RunStatus.SUCCEEDED, tasks.run(first.runId).status);
        Assert.assertEquals("class App { int a = 1; }\n// WIP\n// RUN_DELTA\n",
                read(workspace.resolve("src/main/java/App.java")));
        RunRecord firstFinished = tasks.run(first.runId);

        // Run-2 modifies the same file again and must see Run-1's result.
        TaskRecord secondTask = createAndApprove("create-dw002-2");
        RunRecord second = start(secondTask, "start-dw002-2");
        awaitTerminal(second.runId);
        RunRecord secondFinished = tasks.run(second.runId);
        Assert.assertEquals(RunStatus.SUCCEEDED, secondFinished.status);
        Assert.assertEquals("class App { int a = 1; }\n// WIP\n// RUN_DELTA\n// RUN_DELTA\n",
                read(workspace.resolve("src/main/java/App.java")));
        // Run-2's start fingerprint equals Run-1's end fingerprint (DW-002).
        String firstEnd = stateStore.read("runs/" + first.runId + "/workspace-snapshot.json",
                com.sage.business.code.infrastructure.WorkspaceSnapshot.class).fingerprint;
        String secondStart = stateStore.read("runs/" + second.runId + "/workspace-start-snapshot.json",
                com.sage.business.code.infrastructure.WorkspaceSnapshot.class).fingerprint;
        Assert.assertEquals(firstEnd, secondStart);
        // The workspace lineage tip points at Run-2 (DW-002).
        String workspaceId = firstFinished.workspaceId;
        Map<String, Object> lineage = stateStore.read(
                "workspaces/" + workspaceId + "/lineage.json", Map.class);
        Assert.assertEquals(second.runId, String.valueOf(lineage.get("tipRunId")));
    }

    @Test
    public void dw005ExternalConcurrentModificationYieldsTypedConflict() throws Exception {
        executions.setCommitExternalMutator(new WorkspaceExecutionService.CommitExternalMutator() {
            @Override
            public void mutate(Path checkout, List<String> candidatePaths) {
                try {
                    Files.write(checkout.resolve("src/main/java/App.java"),
                            "class App { int a = 1; }\n// WIP\n// EXTERNAL\n".getBytes(StandardCharsets.UTF_8));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        });
        TaskRecord task = createAndApprove();
        RunRecord run = start(task);
        awaitTerminal(run.runId);
        RunRecord finished = tasks.run(run.runId);
        Assert.assertEquals(RunStatus.NEEDS_ATTENTION, finished.status);
        Assert.assertEquals("WORKSPACE_CAS_CONFLICT", finished.failureCode);
        Assert.assertEquals(1, modelCalls.get());
        // The external content wins; the run delta was NOT applied.
        Assert.assertEquals("class App { int a = 1; }\n// WIP\n// EXTERNAL\n",
                read(workspace.resolve("src/main/java/App.java")));
        // The candidate and its receipt stay queryable (INV-DW-08).
        Assert.assertTrue(Files.exists(temp.getRoot().toPath().resolve("state")
                .resolve("runs").resolve(run.runId).resolve("workspace-candidate-snapshot.json")));
        Map<String, Object> receipt = stateStore.read(
                "workspace-executions/" + finished.workspaceExecutionId + "/commit-receipt.json",
                Map.class);
        Assert.assertEquals("NOT_APPLIED", receipt.get("state"));
        Assert.assertTrue(((List<?>) receipt.get("conflictedFiles"))
                .contains("src/main/java/App.java"));
    }

    @Test
    public void dw011UnresolvedMergeRejectsModifyBeforeModel() throws Exception {
        // A real unresolved merge (porcelain UU), not just conflict markers.
        String defaultBranch = gitOut("rev-parse", "--abbrev-ref", "HEAD").trim();
        gitOut("checkout", "-b", "other");
        Files.write(workspace.resolve("src/main/java/App.java"),
                "class App { int a = 2; }\n".getBytes(StandardCharsets.UTF_8));
        gitOut("commit", "-am", "other");
        gitOut("checkout", defaultBranch);
        Files.write(workspace.resolve("src/main/java/App.java"),
                "class App { int a = 3; }\n".getBytes(StandardCharsets.UTF_8));
        gitOut("commit", "-am", "main-advance");
        try {
            gitOut("merge", "--no-edit", "other");
            Assert.fail("fixture merge must conflict");
        } catch (IOException expectedConflict) {
            // expected: the merge leaves UU entries in the index
        }
        int callsBefore = modelCalls.get();
        // MODIFY is rejected with a typed conflict BEFORE any model call
        // (INV-DW-10); the canonicalizer fails the task at creation time and
        // the run path re-checks before provisioning.
        try {
            createAndApprove("create-dw011");
            Assert.fail("conflicted MODIFY must be rejected");
        } catch (BusinessException expected) {
            Assert.assertEquals("WORKSPACE_CONFLICTED", expected.getCode());
        }
        Assert.assertEquals("a conflicted MODIFY must never reach the model",
                callsBefore, modelCalls.get());
    }

    // ---- helpers ----

    private TaskRecord createAndApprove() {
        return createAndApprove("create-" + System.nanoTime());
    }

    private TaskRecord createAndApprove(String key) {
        return createAndApprove(key, null);
    }

    private TaskRecord createAndApproveCheckout(String key) {
        return createAndApprove(key, "LOCAL_CHECKOUT");
    }

    private TaskRecord createAndApprove(String key, String workspaceMode) {
        BusinessCodeTaskContract contract = new BusinessCodeTaskContract();
        contract.setSchemaVersion(BusinessCodeTaskContract.SCHEMA_VERSION);
        contract.setWorkspace("sample");
        contract.setMode("MODIFY");
        contract.setWorkspaceMode(workspaceMode);
        contract.setGoal("Modify the already dirty App.java deterministically");
        BusinessCodeTaskContract.AcceptanceCriterion criterion =
                new BusinessCodeTaskContract.AcceptanceCriterion();
        criterion.setId("AC-1");
        criterion.setGiven("a dirty workspace");
        criterion.setWhen("the run modifies App.java");
        criterion.setThen(Collections.singletonList("both WIP and delta are present"));
        criterion.setObservable("file bytes");
        contract.setAcceptanceCriteria(Collections.singletonList(criterion));
        BusinessCodeTaskContract.WriteScope scope = new BusinessCodeTaskContract.WriteScope();
        scope.setAllow(Collections.singletonList("src/main/java"));
        contract.setWriteScope(scope);
        BusinessCodeTaskContract.Verification verification = new BusinessCodeTaskContract.Verification();
        verification.setLevel("VERIFY");
        verification.setOffline(false);
        contract.setVerification(verification);
        TaskRecord task = tasks.create(contract, key);
        tasks.approve(task.taskId, approval(task.revision, task.fingerprint), key + "-approve");
        return task;
    }

    private RunRecord start(TaskRecord task) {
        return start(task, "start-" + System.nanoTime());
    }

    private RunRecord start(TaskRecord task, String key) {
        RunRequest request = new RunRequest();
        request.setRevision(task.revision);
        request.setFingerprint(task.fingerprint);
        return tasks.start(task.taskId, request, key);
    }

    private ApprovalRequest approval(int revision, String fingerprint) {
        ApprovalRequest request = new ApprovalRequest();
        request.setRevision(revision);
        request.setFingerprint(fingerprint);
        return request;
    }

    private void awaitTerminal(String runId) throws Exception {
        for (int i = 0; i < 300; i++) {
            RunStatus status = tasks.run(runId).status;
            if (status != RunStatus.QUEUED && status != RunStatus.RUNNING
                    && status != RunStatus.WAITING_APPROVAL
                    && status != RunStatus.WAITING_BUDGET_APPROVAL
                    && status != RunStatus.PAUSED_RETRYABLE) {
                return;
            }
            Thread.sleep(50);
        }
        Assert.fail("run did not reach a terminal state: " + tasks.run(runId).status);
    }

    private String read(Path file) throws Exception {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private String indexBlob(String path) throws Exception {
        return gitOut("ls-files", "-s", path).trim();
    }

    private List<String> porcelain() {
        try {
            String out = gitOut("status", "--porcelain", "--untracked-files=all");
            List<String> lines = new ArrayList<String>();
            for (String line : out.split("\r?\n")) {
                if (!line.trim().isEmpty()) lines.add(line);
            }
            return lines;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String gitOut(String... args) throws Exception {
        List<String> command = new ArrayList<String>();
        command.add("git");
        command.add("-C");
        command.add(workspace.toString());
        for (String arg : args) command.add(arg);
        Process process = new ProcessBuilder(command).start();
        InputStream stdout = process.getInputStream();
        byte[] buffer = new byte[8192];
        int n = stdout.read(buffer);
        int exit = process.waitFor();
        if (exit != 0) throw new IOException("git fixture command failed: " + args[0]);
        return n <= 0 ? "" : new String(buffer, 0, n, StandardCharsets.UTF_8);
    }
}
