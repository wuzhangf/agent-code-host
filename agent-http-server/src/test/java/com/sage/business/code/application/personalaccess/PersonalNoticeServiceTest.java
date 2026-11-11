package com.sage.business.code.application.personalaccess;

import com.sage.business.code.bootstrap.PersonalNoticeSettings;
import org.junit.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.Assert.*;

public class PersonalNoticeServiceTest {
    @Test public void settingsAreDisabledAndRequireHttpsWhenEnabled() {
        PersonalNoticeSettings disabled = PersonalNoticeSettings.from(new Properties());
        assertFalse(disabled.enabled);
        Properties bad = new Properties();
        bad.setProperty("personal.notice.enabled", "true");
        bad.setProperty("personal.notice.server", "http://ntfy.example");
        try { PersonalNoticeSettings.from(bad); fail("expected HTTPS validation"); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("HTTPS")); }
    }

    @Test public void devicePreferencesCannotChangeOwnerRouteAndRequestIsIdempotent() {
        PersonalNoticeSettings settings = settings();
        MemoryRepository store = new MemoryRepository();
        NtfyNoticeAdapter adapter = new NtfyNoticeAdapter(settings,
                name -> "token", 1000, 1000);
        PersonalNoticeService service = new PersonalNoticeService(settings, store, adapter,
                PersonalNoticeService.ACL_UNSUPPORTED,
                Clock.fixed(Instant.ofEpochMilli(1000000L), ZoneOffset.UTC));
        PersonalNoticeService.Route route = service.registerRoute("phone-1", "grant-7", "topic-1", "host-1");
        assertEquals("ntfy", route.providerRef);
        assertEquals("agentcontrol://inbox?host=host-1", route.deepLink);
        assertEquals("topic-1", service.subscription("phone-1").topicRef);
        assertEquals("NTFY_SUBSCRIBE_TOKEN", service.subscription("phone-1").readCredentialEnv);
        PersonalNoticeService.Preferences first = service.updatePreferences(
                "phone-1", "req-1", true, "23:00", "07:00");
        assertTrue(first.enabled);
        assertTrue(service.updatePreferences("phone-1", "req-1", true, "23:00", "07:00").enabled);
        try { service.updatePreferences("phone-1", "req-1", false, "xx", "yy"); fail("request id reuse"); }
        catch (IllegalStateException expected) { assertEquals("REQUEST_ID_REUSE", expected.getMessage()); }
        assertTrue(store.get(PersonalNoticeService.ROUTE_KIND, "phone-1",
                PersonalNoticeService.Route.class).enabled);
    }

    @Test public void revokeDisablesLocallyAndReportsUnsupportedRemoteCleanup() {
        PersonalNoticeSettings settings = settings();
        MemoryRepository store = new MemoryRepository();
        NtfyNoticeAdapter adapter = new NtfyNoticeAdapter(settings, name -> "token", 1000, 1000);
        PersonalNoticeService service = new PersonalNoticeService(settings, store, adapter);
        service.registerRoute("phone-1", "grant-1", "topic-1", "host-1");
        service.updatePreferences("phone-1", "req-1", true, null, null);
        PersonalNoticeService.Delivery queued = service.enqueue("phone-1", "item-1", "rev-1");
        assertNotNull(queued);
        PersonalNoticeService.RevokeResult result = service.revokeDevice("phone-1");
        assertTrue(result.localDisabled);
        assertTrue(result.cleanupPending);
        assertEquals("DEVICE_REVOKED", store.get(PersonalNoticeService.DELIVERY_KIND,
                queued.deliveryId, PersonalNoticeService.Delivery.class).state);
        assertFalse(store.get(PersonalNoticeService.ROUTE_KIND, "phone-1",
                PersonalNoticeService.Route.class).enabled);
    }

    @Test public void pendingRemoteCleanupCanBeRetriedWithoutReenablingRoute() {
        PersonalNoticeSettings settings = settings();
        MemoryRepository store = new MemoryRepository();
        NtfyNoticeAdapter adapter = new NtfyNoticeAdapter(settings, name -> "token", 1000, 1000);
        PersonalNoticeService.RemoteAclCleaner cleaner = new PersonalNoticeService.RemoteAclCleaner() {
            private int calls;
            @Override public PersonalNoticeService.CleanupResult clear(PersonalNoticeService.Route route) {
                return ++calls == 1 ? PersonalNoticeService.CleanupResult.failed("offline")
                        : PersonalNoticeService.CleanupResult.cleared();
            }
        };
        PersonalNoticeService service = new PersonalNoticeService(settings, store, adapter, cleaner,
                Clock.fixed(Instant.ofEpochMilli(1000000L), ZoneOffset.UTC));
        service.registerRoute("phone-1", "grant-1", "topic-1", "host-1");
        assertTrue(service.revokeDevice("phone-1").cleanupPending);
        PersonalNoticeService.Route pending = store.get(PersonalNoticeService.ROUTE_KIND,
                "phone-1", PersonalNoticeService.Route.class);
        pending.cleanupNextAttemptAt = 0L;
        store.put(PersonalNoticeService.ROUTE_KIND, "phone-1", pending);
        assertEquals(1, service.retryPendingCleanup(10));
        pending = store.get(PersonalNoticeService.ROUTE_KIND, "phone-1", PersonalNoticeService.Route.class);
        pending.cleanupNextAttemptAt = 0L; store.put(PersonalNoticeService.ROUTE_KIND, "phone-1", pending);
        assertEquals(1, service.retryPendingCleanup(10));
        assertFalse(store.get(PersonalNoticeService.ROUTE_KIND, "phone-1",
                PersonalNoticeService.Route.class).cleanupPending);
        assertFalse(store.get(PersonalNoticeService.ROUTE_KIND, "phone-1",
                PersonalNoticeService.Route.class).enabled);
    }

    @Test public void sameRevisionDeduplicatesAndNewBusinessRevisionNotifiesAgain() {
        MemoryRepository store = new MemoryRepository();
        Clock clock = Clock.fixed(Instant.ofEpochMilli(1000000L), ZoneOffset.UTC);
        List<String[]> published = new ArrayList<String[]>();
        PersonalNoticeService service = service(store, clock, published);
        service.subscription("phone-1", "1", "host-1");
        service.updatePreferences("phone-1", "req-1", true, null, null);
        PersonalNoticeService.Delivery first = service.enqueue("phone-1", "item-1", "rev-1");
        assertNotNull(first); assertEquals("QUEUED", first.state);
        // A replay before publish returns the stored delivery without a new ref.
        assertEquals(first.deliveryId, service.enqueue("phone-1", "item-1", "rev-1").deliveryId);
        assertEquals(1, service.processDue(4, probe(true, false)));
        assertEquals(1, published.size());
        // After acceptance, same business revision replays the dedupe marker.
        PersonalNoticeService.Delivery replay = service.enqueue("phone-1", "item-1", "rev-1");
        assertEquals(first.deliveryId, replay.deliveryId); assertEquals("ACCEPTED", replay.state);
        assertEquals(0, service.processDue(4, probe(true, false)));
        // A changed source revision is a new business event and may remind again.
        PersonalNoticeService.Delivery next = service.enqueue("phone-1", "item-1", "rev-2");
        assertNotNull(next); assertNotEquals(first.deliveryId, next.deliveryId);
        assertEquals(1, service.processDue(4, probe(true, false)));
        assertEquals(2, published.size());
    }

    @Test public void notificationTagsFollowItemKindAndTestPrefix() {
        MemoryRepository store = new MemoryRepository();
        Clock clock = Clock.fixed(Instant.ofEpochMilli(1000000L), ZoneOffset.UTC);
        List<String[]> published = new ArrayList<String[]>();
        PersonalNoticeService service = service(store, clock, published);
        service.subscription("phone-1", "1", "host-1");
        service.updatePreferences("phone-1", "req-1", true, null, null);
        enqueue(service, "item-in", "NEEDS_INPUT", "r1");
        enqueue(service, "item-att", "ATTENTION_REQUIRED", "r1");
        enqueue(service, "item-res", "RESULT_READY", "r1");
        service.enqueue("phone-1", new PersonalNoticeService.NoticeTarget(
                PersonalNoticeService.TEST_ITEM_PREFIX + "req-9", ""));
        service.processDue(8, probe(true, false));
        assertEquals(4, published.size());
        Map<String, String> tags = new HashMap<String, String>();
        for (String[] call : published) tags.put(call[2], call[1]);
        assertTrue(tags.get("needs_action") != null);
        assertTrue(tags.get("result_ready") != null);
        assertTrue(tags.get("test") != null);
        for (String[] call : published) {
            assertTrue(call[1].startsWith("agentcontrol://inbox?host=host-1&notice="));
        }
    }

    @Test public void subscriptionProvisionsServerTopicAndKeepsItAcrossGrantRebind() {
        MemoryRepository store = new MemoryRepository();
        Clock clock = Clock.fixed(Instant.ofEpochMilli(1000000L), ZoneOffset.UTC);
        PersonalNoticeService service = service(store, clock, new ArrayList<String[]>());
        PersonalNoticeService.Subscription first = service.subscription("phone-1", "1", "host-1");
        assertTrue(first.topic.startsWith("pac-"));
        assertEquals("https://ntfy.example", first.server);
        assertEquals("agentcontrol://inbox?host=host-1", first.clickBase);
        PersonalNoticeService.Subscription rebound = service.subscription("phone-1", "2", "host-1");
        assertEquals(first.topic, rebound.topic);
        assertEquals(1, service.routes(10).size());
        assertEquals("2", service.routeInfo("phone-1").grantRevision);
        // A second device gets an independent route and topic.
        assertNotEquals(first.topic, service.subscription("phone-2", "1", "host-1").topic);
        assertEquals(2, service.routes(10).size());
    }

    @Test public void enqueueNeedsEnabledRouteAndPreferences() {
        MemoryRepository store = new MemoryRepository();
        Clock clock = Clock.fixed(Instant.ofEpochMilli(1000000L), ZoneOffset.UTC);
        PersonalNoticeService service = service(store, clock, new ArrayList<String[]>());
        assertNull("no route means nothing enqueued", service.enqueue("phone-1", "item-1", "rev-1"));
        service.subscription("phone-1", "1", "host-1");
        assertNull("preferences stay disabled by default", service.enqueue("phone-1", "item-1", "rev-1"));
        service.updatePreferences("phone-1", "req-1", true, null, null);
        assertNotNull(service.enqueue("phone-1", "item-1", "rev-1"));
    }

    @Test public void noticeRefsResolveOnlyForOwningDevice() {
        MemoryRepository store = new MemoryRepository();
        Clock clock = Clock.fixed(Instant.ofEpochMilli(1000000L), ZoneOffset.UTC);
        PersonalNoticeService service = service(store, clock, new ArrayList<String[]>());
        service.subscription("phone-1", "1", "host-1");
        service.updatePreferences("phone-1", "req-1", true, null, null);
        PersonalNoticeService.Delivery delivery = service.enqueue("phone-1", "item-1", "rev-3");
        assertNotNull(delivery.ref);
        PersonalNoticeService.NoticeRef ref = service.ref("phone-1", delivery.ref);
        assertNotNull(ref); assertEquals("item-1", ref.itemId); assertEquals("rev-3", ref.businessRevision);
        assertNull("foreign device must not resolve the ref", service.ref("phone-2", delivery.ref));
        assertNull(service.ref("phone-1", "AAAAAAAAAAAAAAAAAAAAAA"));
        try { service.ref("phone-1", "../escape"); fail("malformed ref"); }
        catch (IllegalArgumentException expected) { }
    }

    @Test public void subscriptionAuthReflectsConfiguredReadCredential() {
        PersonalNoticeSettings settings = settings();
        MemoryRepository store = new MemoryRepository();
        NtfyNoticeAdapter basic = new NtfyNoticeAdapter(settings,
                name -> "NTFY_SUBSCRIBE_TOKEN".equals(name) ? "reader:s3cret" : "publish", 1000, 1000);
        PersonalNoticeService service = new PersonalNoticeService(settings, store, basic);
        service.registerRoute("phone-1", "g1", "topic-1", "host-1");
        PersonalNoticeService.Subscription view = service.subscription("phone-1");
        assertEquals("basic", view.authType); assertEquals("reader", view.authUsername);
        assertEquals("s3cret", view.authPassword); assertNull(view.authToken);
        NtfyNoticeAdapter bearer = new NtfyNoticeAdapter(settings,
                name -> "NTFY_SUBSCRIBE_TOKEN".equals(name) ? "read-token" : "publish", 1000, 1000);
        PersonalNoticeService.Subscription bearerView =
                new PersonalNoticeService(settings, store, bearer).subscription("phone-1");
        assertEquals("bearer", bearerView.authType); assertEquals("read-token", bearerView.authToken);
        NtfyNoticeAdapter missing = new NtfyNoticeAdapter(settings,
                name -> { throw new IllegalArgumentException("unset"); }, 1000, 1000);
        assertEquals("none", new PersonalNoticeService(settings, store, missing)
                .subscription("phone-1").authType);
        // Pointing both names at the same variable must never hand the publish credential to a device.
        Properties same = new Properties();
        same.setProperty("personal.notice.enabled", "true");
        same.setProperty("personal.notice.server", "https://ntfy.example");
        same.setProperty("personal.notice.subscribe-credential.env", "NTFY_PUBLISH_TOKEN");
        PersonalNoticeSettings shared = PersonalNoticeSettings.from(same);
        NtfyNoticeAdapter sharedAdapter = new NtfyNoticeAdapter(shared, name -> "publish-secret", 1000, 1000);
        assertEquals("none", new PersonalNoticeService(shared, store, sharedAdapter)
                .subscription("phone-1").authType);
    }

    @Test public void probeStatesMapToTerminalSkips() {
        MemoryRepository store = new MemoryRepository();
        Clock clock = Clock.fixed(Instant.ofEpochMilli(1000000L), ZoneOffset.UTC);
        List<String[]> published = new ArrayList<String[]>();
        PersonalNoticeService service = service(store, clock, published);
        service.subscription("phone-1", "1", "host-1");
        service.updatePreferences("phone-1", "req-1", true, null, null);
        PersonalNoticeService.Delivery resolved = service.enqueue("phone-1", "item-1", "rev-1");
        service.processDue(4, probe(true, true));
        assertEquals("SKIPPED_RESOLVED", store.get(PersonalNoticeService.DELIVERY_KIND,
                resolved.deliveryId, PersonalNoticeService.Delivery.class).state);
        PersonalNoticeService.Delivery denied = service.enqueue("phone-1", "item-2", "rev-1");
        service.processDue(4, probe(false, false));
        assertEquals("SKIPPED_UNAUTHORIZED", store.get(PersonalNoticeService.DELIVERY_KIND,
                denied.deliveryId, PersonalNoticeService.Delivery.class).state);
        assertTrue(published.isEmpty());
    }

    private static PersonalNoticeService service(MemoryRepository store, Clock clock,
                                                 final List<String[]> published) {
        PersonalNoticeService.Publisher publisher = new PersonalNoticeService.Publisher() {
            @Override public NtfyNoticeAdapter.Result publish(String topicRef, String clickUrl, String tag) {
                published.add(new String[]{topicRef, clickUrl, tag});
                return NtfyNoticeAdapter.Result.accepted(200, clock.millis());
            }
        };
        return new PersonalNoticeService(settings(), store, publisher,
                PersonalNoticeService.ACL_UNSUPPORTED, clock);
    }

    private static PersonalNoticeService.ItemProbe probe(final boolean authorized, final boolean resolved) {
        return new PersonalNoticeService.ItemProbe() {
            @Override public PersonalNoticeService.Decision inspect(String deviceId, String itemId) {
                if (!authorized) return PersonalNoticeService.Decision.unauthorized();
                return resolved ? PersonalNoticeService.Decision.resolved()
                        : PersonalNoticeService.Decision.publishable();
            }
        };
    }

    private static void enqueue(PersonalNoticeService service, String itemId, String kind, String revision) {
        PersonalNoticeService.NoticeTarget target = new PersonalNoticeService.NoticeTarget(itemId, revision);
        target.kind = kind;
        assertNotNull(service.enqueue("phone-1", target));
    }

    private static PersonalNoticeSettings settings() {
        Properties p = new Properties();
        p.setProperty("personal.notice.enabled", "true");
        p.setProperty("personal.notice.server", "https://ntfy.example");
        return PersonalNoticeSettings.from(p);
    }

    private static final class MemoryRepository implements PersonalNoticeService.Repository {
        private final Map<String, Object> values = new HashMap<String, Object>();
        @Override public <T> T get(String kind, String id, Class<T> type) {
            Object value = values.get(kind + " " + id);
            return value == null ? null : type.cast(value);
        }
        @Override public <T> List<T> list(String kind, Class<T> type, int limit) {
            List<T> result = new ArrayList<T>();
            String prefix = kind + " ";
            for (Map.Entry<String, Object> e : values.entrySet()) {
                if (e.getKey().startsWith(prefix) && result.size() < limit) result.add(type.cast(e.getValue()));
            }
            return result;
        }
        @Override public void put(String kind, String id, Object value) { values.put(kind + " " + id, value); }
        @Override public void delete(String kind, String id) { values.remove(kind + " " + id); }
    }
}
