package com.sage.business.code.adapter.http.stream;

import com.sage.business.code.domain.BusinessException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assert;
import org.junit.Test;

public class LongRequestAdmissionTest {
    @Test
    public void enforcesTotalAndPerKindLimitsWithoutPartialReservation() {
        LongRequestAdmission admission = new LongRequestAdmission(3, 2, 2, 2);
        LongRequestAdmission.Handle first = admission.reserve(
                LongRequestAdmission.Kind.CONTROL_STREAM, "principal-a", "session-a");
        LongRequestAdmission.Handle second = admission.reserve(
                LongRequestAdmission.Kind.CONTROL_STREAM, "principal-b", "session-b");
        try {
            try {
                admission.reserve(LongRequestAdmission.Kind.CONTROL_STREAM,
                        "principal-c", "session-c");
                Assert.fail("expected kind limit");
            } catch (BusinessException failure) {
                Assert.assertEquals("STREAM_LIMIT_REACHED", failure.getCode());
                Assert.assertEquals(429, failure.getHttpStatus());
                Assert.assertEquals("kind", failure.getDetails().get("scope"));
            }
            LongRequestAdmission.Handle third = admission.reserve(
                    LongRequestAdmission.Kind.LONG_POLL, "principal-c", "session-c");
            try {
                Assert.assertEquals(3, admission.activeTotal());
                try {
                    admission.reserve(LongRequestAdmission.Kind.TERMINAL_STREAM,
                            "principal-d", "session-d");
                    Assert.fail("expected total limit");
                } catch (BusinessException failure) {
                    Assert.assertEquals("STREAM_LIMIT_REACHED", failure.getCode());
                    Assert.assertEquals("total", failure.getDetails().get("scope"));
                }
            } finally {
                third.close();
            }
        } finally {
            first.close();
            second.close();
        }
        Assert.assertEquals(0, admission.activeTotal());
        Assert.assertEquals(Integer.valueOf(2),
                admission.available().get(LongRequestAdmission.Kind.CONTROL_STREAM));
    }

    @Test
    public void enforcesPrincipalAndSessionLimits() {
        LongRequestAdmission admission = new LongRequestAdmission(8, 8, 8, 8, 2, 1);
        LongRequestAdmission.Handle first = admission.reserve(
                LongRequestAdmission.Kind.CONTROL_STREAM, "principal-a", "session-a");
        try {
            try {
                admission.reserve(LongRequestAdmission.Kind.TERMINAL_STREAM,
                        "principal-a", "session-a");
                Assert.fail("expected per-session limit");
            } catch (BusinessException failure) {
                Assert.assertEquals("STREAM_LIMIT_REACHED", failure.getCode());
                Assert.assertEquals("session", failure.getDetails().get("scope"));
            }
            LongRequestAdmission.Handle second = admission.reserve(
                    LongRequestAdmission.Kind.TERMINAL_STREAM, "principal-a", "session-b");
            try {
                try {
                    admission.reserve(LongRequestAdmission.Kind.LONG_POLL,
                            "principal-a", "session-c");
                    Assert.fail("expected per-principal limit");
                } catch (BusinessException failure) {
                    Assert.assertEquals("STREAM_LIMIT_REACHED", failure.getCode());
                    Assert.assertEquals("principal", failure.getDetails().get("scope"));
                }
            } finally {
                second.close();
            }
        } finally {
            first.close();
        }
    }

    @Test
    public void releaseIsIdempotentAndTryWithResourcesReleasesOnException() {
        LongRequestAdmission admission = new LongRequestAdmission(2, 2, 2, 2);
        LongRequestAdmission.Handle handle = admission.reserve(
                LongRequestAdmission.Kind.LONG_POLL, "principal", "session");
        admission.release(handle);
        admission.release(handle);
        handle.close();
        Assert.assertEquals(0, admission.activeTotal());

        try {
            try (LongRequestAdmission.Handle ignored = admission.reserve(
                    LongRequestAdmission.Kind.CONTROL_STREAM, "principal", "session")) {
                throw new IllegalStateException("simulated handler failure");
            }
        } catch (IllegalStateException expected) {
            // The try-with-resources close is the exception-path release proof.
        }
        Assert.assertEquals(0, admission.activeTotal());
    }

    @Test
    public void rejectsInvalidIdentitiesAndUnknownKinds() {
        LongRequestAdmission admission = new LongRequestAdmission(2, 1, 1, 1);
        assertInvalid(new Runnable() {
            public void run() {
                admission.reserve(LongRequestAdmission.Kind.CONTROL_STREAM, "", "session");
            }
        }, "principal");
        assertInvalid(new Runnable() {
            public void run() {
                admission.reserve(LongRequestAdmission.Kind.CONTROL_STREAM, "principal", " ");
            }
        }, "session");
        final StringBuilder tooLong = new StringBuilder();
        for (int i = 0; i < LongRequestAdmission.MAX_IDENTITY_LENGTH + 1; i++) {
            tooLong.append('x');
        }
        assertInvalid(new Runnable() {
            public void run() {
                admission.reserve(LongRequestAdmission.Kind.CONTROL_STREAM,
                        tooLong.toString(), "session");
            }
        }, "principal");
        assertInvalid(new Runnable() {
            public void run() {
                admission.reserve("NOT_A_KIND", "principal", "session");
            }
        }, "kind");
    }

    @Test
    public void snapshotsAreImmutableAndDoNotExposeIdentityOrHandleData() {
        LongRequestAdmission admission = new LongRequestAdmission(4, 2, 1, 1);
        LongRequestAdmission.Handle handle = admission.reserve(
                LongRequestAdmission.Kind.CONTROL_STREAM, "secret-principal", "secret-session");
        try {
            Map<LongRequestAdmission.Kind, Integer> active = admission.activeCounts();
            Map<LongRequestAdmission.Kind, Integer> available = admission.available();
            Assert.assertEquals(Integer.valueOf(1),
                    active.get(LongRequestAdmission.Kind.CONTROL_STREAM));
            Assert.assertEquals(Integer.valueOf(1),
                    available.get(LongRequestAdmission.Kind.CONTROL_STREAM));
            Assert.assertEquals(3, admission.snapshot().getAvailableTotal());
            try {
                active.put(LongRequestAdmission.Kind.LONG_POLL, Integer.valueOf(99));
                Assert.fail("snapshot must be immutable");
            } catch (UnsupportedOperationException expected) {
                // expected
            }
            Assert.assertFalse(active.toString().contains("secret"));
            Assert.assertFalse(available.toString().contains("secret"));
            Assert.assertFalse(handle.toString().contains("secret"));
        } finally {
            handle.close();
        }
    }

    @Test
    public void concurrentReserveAndReleaseNeverOversells() throws Exception {
        final LongRequestAdmission admission = new LongRequestAdmission(3, 3, 3, 3);
        final int workers = 12;
        final CountDownLatch ready = new CountDownLatch(workers);
        final CountDownLatch start = new CountDownLatch(1);
        final AtomicInteger held = new AtomicInteger();
        final AtomicInteger maximumHeld = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        List<Future<?>> futures = new ArrayList<Future<?>>();
        try {
            for (int worker = 0; worker < workers; worker++) {
                final int id = worker;
                futures.add(executor.submit(new Runnable() {
                    public void run() {
                        ready.countDown();
                        try {
                            start.await();
                            for (int attempt = 0; attempt < 150; attempt++) {
                                LongRequestAdmission.Handle handle = null;
                                try {
                                    handle = admission.reserve(
                                            LongRequestAdmission.Kind.values()[attempt % 3],
                                            "principal-" + id, "session-" + id);
                                    int current = held.incrementAndGet();
                                    maximumHeld.accumulateAndGet(current, Math::max);
                                    Thread.yield();
                                } catch (BusinessException failure) {
                                    Assert.assertEquals("STREAM_LIMIT_REACHED", failure.getCode());
                                    Assert.assertEquals(429, failure.getHttpStatus());
                                } finally {
                                    if (handle != null) {
                                        held.decrementAndGet();
                                        handle.close();
                                    }
                                }
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(interrupted);
                        }
                    }
                }));
            }
            Assert.assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            for (Future<?> future : futures) future.get(20, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        Assert.assertTrue("admission oversold", maximumHeld.get() <= 3);
        Assert.assertEquals(0, held.get());
        Assert.assertEquals(0, admission.activeTotal());
    }

    private static void assertInvalid(Runnable action, String field) {
        try {
            action.run();
            Assert.fail("expected invalid request");
        } catch (BusinessException failure) {
            Assert.assertEquals("INVALID_REQUEST", failure.getCode());
            Assert.assertEquals(400, failure.getHttpStatus());
            Assert.assertEquals(field, failure.getDetails().get("field"));
        }
    }
}
