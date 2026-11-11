package com.sage.business.code.bootstrap;

import com.agent.product.code.writeapproval.WriteScopeApprovalDecision;
import com.agent.product.code.writeapproval.WriteScopeApprovalRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sage.business.code.application.WriteScopeApprovalCoordinator;
import com.sage.business.code.application.executionpolicy.PermissionApprovalRecord;
import com.sage.business.code.infrastructure.AtomicJsonStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/** Regression for the PC inbox approval lane: a pending write-approval
 * waiter and its common-plane record share only runId + effectFingerprint
 * (the fingerprint deliberately excludes the request's own approvalId, so a
 * same-effect replacement matches).  Before the fix,
 * {@link BusinessServiceFactory#reconcileLegacyDecision} replayed EVERY
 * non-APPROVED terminal record — including the adapter's own bridge-timeout
 * denial (idempotency key {@code legacy-write-*}) and EXPIRY/STALE
 * transitions — onto whichever same-fingerprint request was pending, so a
 * replacement parked right after a timed-out request was killed within one
 * scheduler poll (~250ms) and the denial was memoized for the rest of the
 * run.  Only an operator decision may release a live waiter now; a dead
 * record's own waiter still resolves at its bridge deadline. */
public class WriteApprovalReconcileTest {
    private Path state;
    private WriteScopeApprovalCoordinator coordinator;

    @Before public void setUp() throws Exception {
        state = Files.createTempDirectory("write-reconcile-");
        ObjectMapper mapper = BusinessServiceFactory.mapper();
        coordinator = new WriteScopeApprovalCoordinator(
                new AtomicJsonStore(state, mapper), mapper);
    }

    @After public void tearDown() throws Exception {
        if (coordinator != null) coordinator.interruptAll();
        if (state != null && Files.exists(state)) {
            java.util.stream.Stream<Path> all = Files.walk(state);
            try {
                all.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            } finally {
                all.close();
            }
        }
    }

    @Test public void automaticDenialDoesNotKillReplacementWaiter() throws Exception {
        String runId = "run-1";
        WriteScopeApprovalRequest first = request("wsa-1", runId, "x.txt");
        // The first request resolves by its own bridge deadline — what the
        // lane produced before a human could answer.
        WriteScopeApprovalDecision timedOut = coordinator.handlerFor(runId)
                .awaitDecision(first, nowPlus(60));
        Assert.assertEquals(WriteScopeApprovalDecision.Decision.DENY,
                timedOut.getDecision());
        Assert.assertEquals("wsa-1", timedOut.getApprovalId());

        WriteScopeApprovalRequest replacement = request("wsa-2", runId, "x.txt");
        Assert.assertEquals(first.getFingerprint(), replacement.getFingerprint());
        AtomicReference<WriteScopeApprovalDecision> resumed = new AtomicReference<>();
        CountDownLatch resumedLatch = new CountDownLatch(1);
        Thread waiter = park(runId, replacement, resumed, resumedLatch);
        Assert.assertSame(replacement,
                awaitPending(runId, first.getFingerprint()));

        // The dead record's non-operator terminal states must not touch the
        // live replacement — the observed wsa-2 kill ~229ms after parking.
        reconcile(record(runId, first.getFingerprint(), "DENIED",
                "HUMAN", "legacy-write-0123456789abcdef012345"));
        reconcile(record(runId, first.getFingerprint(), "EXPIRED", "EXPIRY", null));
        reconcile(record(runId, first.getFingerprint(), "STALE", "HARD_POLICY", null));
        Assert.assertFalse(
                "Automatic deaths must not release a replacement waiter",
                resumedLatch.await(300, TimeUnit.MILLISECONDS));
        Assert.assertSame(replacement,
                coordinator.pending(runId, first.getFingerprint()));

        // The fingerprint memo was never poisoned: a fresh same-effect
        // request still parks and resolves by its own deadline rather than
        // instantly replaying a foreign denial.
        WriteScopeApprovalRequest third = request("wsa-3", runId, "x.txt");
        WriteScopeApprovalDecision thirdOutcome = coordinator.handlerFor(runId)
                .awaitDecision(third, nowPlus(60));
        Assert.assertEquals(WriteScopeApprovalDecision.Decision.DENY,
                thirdOutcome.getDecision());
        Assert.assertEquals("wsa-3", thirdOutcome.getApprovalId());

        // A real decision still resolves the waiting replacement normally.
        coordinator.decide(runId, "wsa-2", replacement.getFingerprint(), "APPROVE_ONCE");
        Assert.assertTrue(resumedLatch.await(10, TimeUnit.SECONDS));
        Assert.assertEquals(WriteScopeApprovalDecision.Decision.APPROVE_ONCE,
                resumed.get().getDecision());
        Assert.assertEquals("wsa-2", resumed.get().getApprovalId());
        waiter.join(10_000L);
    }

    @Test public void operatorDenialReleasesMatchingWaiter() throws Exception {
        String runId = "run-2";
        WriteScopeApprovalRequest pending = request("wsa-9", runId, "y.txt");
        AtomicReference<WriteScopeApprovalDecision> resumed = new AtomicReference<>();
        CountDownLatch resumedLatch = new CountDownLatch(1);
        Thread waiter = park(runId, pending, resumed, resumedLatch);
        Assert.assertSame(pending,
                awaitPending(runId, pending.getFingerprint()));

        // An operator denial for the same effect releases the live waiter —
        // the contract the reconcile exists for.
        reconcile(record(runId, pending.getFingerprint(), "DENIED",
                "HUMAN", "device-decision-4f3a"));
        Assert.assertTrue("Operator denial must release the parked waiter",
                resumedLatch.await(10, TimeUnit.SECONDS));
        Assert.assertEquals(WriteScopeApprovalDecision.Decision.DENY,
                resumed.get().getDecision());
        Assert.assertEquals("wsa-9", resumed.get().getApprovalId());
        waiter.join(10_000L);
    }

    private void reconcile(PermissionApprovalRecord record) {
        // No command/sandbox/budget coordinators: a write.* record only
        // needs the write waiter map, matching the factory's composition.
        // The method is package-visible for exactly this regression drive.
        BusinessServiceFactory.reconcileLegacyDecision(record, null, coordinator, null, null);
    }

    private static WriteScopeApprovalRequest request(
            String approvalId, String runId, String path) {
        long now = System.currentTimeMillis();
        return new WriteScopeApprovalRequest(approvalId, "product-" + runId,
                "inv-" + approvalId, "workspace_edit", path, "edit",
                "write outside scope",
                Collections.<String>singletonList("workspace:**"), now, now + 120_000L);
    }

    private static PermissionApprovalRecord record(
            String runId, String fingerprint, String status,
            String decisionSource, String idempotencyKey) {
        PermissionApprovalRecord record = new PermissionApprovalRecord();
        record.approvalId = UUID.randomUUID().toString();
        record.capabilityId = "write.edit";
        record.runId = runId;
        record.effectFingerprint = fingerprint;
        record.status = status;
        record.decisionSource = decisionSource;
        record.lastDecisionIdempotencyKey = idempotencyKey;
        record.version = 2L;
        return record;
    }

    private Thread park(final String runId, final WriteScopeApprovalRequest request,
            final AtomicReference<WriteScopeApprovalDecision> resumed,
            final CountDownLatch latch) {
        Thread waiter = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    resumed.set(coordinator.handlerFor(runId)
                            .awaitDecision(request, nowPlus(120_000)));
                } finally {
                    latch.countDown();
                }
            }
        }, "write-waiter-" + request.getApprovalId());
        waiter.setDaemon(true);
        waiter.start();
        return waiter;
    }

    /** Bounded wait for the parked thread to register its pending entry —
     * without it the reconcile would run against an empty waiter map. */
    private WriteScopeApprovalRequest awaitPending(String runId, String fingerprint)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000L;
        WriteScopeApprovalRequest pending;
        while ((pending = coordinator.pending(runId, fingerprint)) == null
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        return pending;
    }

    private static long nowPlus(long millis) {
        return System.currentTimeMillis() + millis;
    }
}
