package com.sage.business.code.adapter.http.stream;

import com.sage.business.code.domain.BusinessException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Atomic admission for HTTP requests whose handler may remain active for a
 * long time.  This class only owns counters; transport, IO and cancellation
 * remain with the HTTP adapter.
 */
public final class LongRequestAdmission {
    public static final int MAX_IDENTITY_LENGTH = 128;

    public enum Kind {
        CONTROL_STREAM,
        TERMINAL_STREAM,
        LONG_POLL
    }

    /** A reservation that is safe to close from any success or failure path. */
    public interface Handle extends AutoCloseable {
        @Override
        void close();
    }

    /** Immutable capacity configuration. A zero identity limit means unlimited. */
    public static final class Limits {
        private final int maxTotal;
        private final EnumMap<Kind, Integer> maxByKind;
        private final int maxPerPrincipal;
        private final int maxPerSession;

        public Limits(int maxTotal, int maxControl, int maxTerminal,
                int maxLongPoll) {
            this(maxTotal, maxControl, maxTerminal, maxLongPoll, 0, 0);
        }

        public Limits(int maxTotal, int maxControl, int maxTerminal,
                int maxLongPoll, int maxPerPrincipal, int maxPerSession) {
            if (maxTotal < 1) throw new IllegalArgumentException("maxTotal must be positive");
            requireKindLimit(maxTotal, maxControl, "maxControl");
            requireKindLimit(maxTotal, maxTerminal, "maxTerminal");
            requireKindLimit(maxTotal, maxLongPoll, "maxLongPoll");
            requireIdentityLimit(maxTotal, maxPerPrincipal, "maxPerPrincipal");
            requireIdentityLimit(maxTotal, maxPerSession, "maxPerSession");
            this.maxTotal = maxTotal;
            this.maxByKind = new EnumMap<Kind, Integer>(Kind.class);
            this.maxByKind.put(Kind.CONTROL_STREAM, Integer.valueOf(maxControl));
            this.maxByKind.put(Kind.TERMINAL_STREAM, Integer.valueOf(maxTerminal));
            this.maxByKind.put(Kind.LONG_POLL, Integer.valueOf(maxLongPoll));
            this.maxPerPrincipal = maxPerPrincipal;
            this.maxPerSession = maxPerSession;
        }

        public Limits(int maxTotal, Map<Kind, Integer> maxByKind,
                int maxPerPrincipal, int maxPerSession) {
            if (maxTotal < 1) throw new IllegalArgumentException("maxTotal must be positive");
            if (maxByKind == null) throw new IllegalArgumentException("maxByKind is required");
            EnumMap<Kind, Integer> copy = new EnumMap<Kind, Integer>(Kind.class);
            for (Kind kind : Kind.values()) {
                Integer value = maxByKind.get(kind);
                if (value == null) throw new IllegalArgumentException("missing kind limit");
                requireKindLimit(maxTotal, value.intValue(), kind.name());
                copy.put(kind, value);
            }
            if (copy.size() != maxByKind.size()) {
                throw new IllegalArgumentException("unknown kind limit");
            }
            requireIdentityLimit(maxTotal, maxPerPrincipal, "maxPerPrincipal");
            requireIdentityLimit(maxTotal, maxPerSession, "maxPerSession");
            this.maxTotal = maxTotal;
            this.maxByKind = copy;
            this.maxPerPrincipal = maxPerPrincipal;
            this.maxPerSession = maxPerSession;
        }

        public int getMaxTotal() { return maxTotal; }
        public int getMaxFor(Kind kind) { return maxByKind.get(kind).intValue(); }
        public int getMaxPerPrincipal() { return maxPerPrincipal; }
        public int getMaxPerSession() { return maxPerSession; }

        private static void requireKindLimit(int total, int value, String name) {
            if (value < 1 || value > total) {
                throw new IllegalArgumentException(name + " must be between 1 and maxTotal");
            }
        }

        private static void requireIdentityLimit(int total, int value, String name) {
            if (value < 0 || value > total) {
                throw new IllegalArgumentException(name + " must be between 0 and maxTotal");
            }
        }
    }

    /** Read-only, identity-free capacity view. */
    public static final class Snapshot {
        private final int activeTotal;
        private final int availableTotal;
        private final Map<Kind, Integer> activeByKind;
        private final Map<Kind, Integer> availableByKind;

        private Snapshot(int activeTotal, int availableTotal,
                Map<Kind, Integer> activeByKind,
                Map<Kind, Integer> availableByKind) {
            this.activeTotal = activeTotal;
            this.availableTotal = availableTotal;
            this.activeByKind = immutableKindMap(activeByKind);
            this.availableByKind = immutableKindMap(availableByKind);
        }

        public int getActiveTotal() { return activeTotal; }
        public int getAvailableTotal() { return availableTotal; }
        public Map<Kind, Integer> getActiveByKind() { return activeByKind; }
        public Map<Kind, Integer> getAvailableByKind() { return availableByKind; }
    }

    private final Limits limits;
    private final EnumMap<Kind, Integer> activeByKind = new EnumMap<Kind, Integer>(Kind.class);
    private final Map<String, Integer> activeByPrincipal = new HashMap<String, Integer>();
    private final Map<String, Integer> activeBySession = new HashMap<String, Integer>();
    private int activeTotal;

    public LongRequestAdmission() {
        this(new Limits(32, 16, 8, 8));
    }

    public LongRequestAdmission(int maxTotal, int maxControl, int maxTerminal,
            int maxLongPoll) {
        this(new Limits(maxTotal, maxControl, maxTerminal, maxLongPoll));
    }

    public LongRequestAdmission(int maxTotal, int maxControl, int maxTerminal,
            int maxLongPoll, int maxPerPrincipal, int maxPerSession) {
        this(new Limits(maxTotal, maxControl, maxTerminal, maxLongPoll,
                maxPerPrincipal, maxPerSession));
    }

    public LongRequestAdmission(Limits limits) {
        if (limits == null) throw new IllegalArgumentException("limits are required");
        this.limits = limits;
        for (Kind kind : Kind.values()) activeByKind.put(kind, Integer.valueOf(0));
    }

    /** Reserves one slot atomically, or fails without changing any counter. */
    public synchronized Handle reserve(Kind kind, String principal, String session) {
        requireKind(kind);
        requireIdentity(principal, "principal");
        requireIdentity(session, "session");

        if (activeTotal >= limits.getMaxTotal()) {
            throw limitReached("total", kind, limits.getMaxTotal(), activeTotal);
        }
        int kindActive = activeByKind.get(kind).intValue();
        if (kindActive >= limits.getMaxFor(kind)) {
            throw limitReached("kind", kind, limits.getMaxFor(kind), kindActive);
        }
        if (limits.getMaxPerPrincipal() > 0) {
            int principalActive = count(activeByPrincipal, principal);
            if (principalActive >= limits.getMaxPerPrincipal()) {
                throw limitReached("principal", kind, limits.getMaxPerPrincipal(), principalActive);
            }
        }
        if (limits.getMaxPerSession() > 0) {
            int sessionActive = count(activeBySession, session);
            if (sessionActive >= limits.getMaxPerSession()) {
                throw limitReached("session", kind, limits.getMaxPerSession(), sessionActive);
            }
        }

        activeTotal++;
        activeByKind.put(kind, Integer.valueOf(kindActive + 1));
        increment(activeByPrincipal, principal);
        increment(activeBySession, session);
        return new Reservation(kind, principal, session);
    }

    /** String boundary for HTTP adapters; unknown kinds fail closed. */
    public Handle reserve(String kind, String principal, String session) {
        Kind parsed = parseKind(kind);
        return reserve(parsed, principal, session);
    }

    /** Idempotently releases a handle created by this admission instance. */
    public void release(Handle handle) {
        if (handle == null) return;
        if (!(handle instanceof Reservation)
                || ((Reservation) handle).owner != this) {
            throw new IllegalArgumentException("handle does not belong to this admission");
        }
        handle.close();
    }

    /** Returns an immutable map containing every kind and no identity data. */
    public synchronized Map<Kind, Integer> activeCounts() {
        return immutableKindMap(activeByKind);
    }

    /** Returns remaining slots by kind; no token, principal or session is exposed. */
    public synchronized Map<Kind, Integer> available() {
        EnumMap<Kind, Integer> result = new EnumMap<Kind, Integer>(Kind.class);
        for (Kind kind : Kind.values()) {
            result.put(kind, Integer.valueOf(limits.getMaxFor(kind)
                    - activeByKind.get(kind).intValue()));
        }
        return immutableKindMap(result);
    }

    public synchronized int activeTotal() { return activeTotal; }
    public synchronized int availableTotal() { return limits.getMaxTotal() - activeTotal; }

    public synchronized Snapshot snapshot() {
        return new Snapshot(activeTotal, limits.getMaxTotal() - activeTotal,
                activeByKind, available());
    }

    private final class Reservation implements Handle {
        private final LongRequestAdmission owner = LongRequestAdmission.this;
        private final Kind kind;
        private final String principal;
        private final String session;
        private boolean released;

        private Reservation(Kind kind, String principal, String session) {
            this.kind = kind;
            this.principal = principal;
            this.session = session;
        }

        @Override
        public void close() {
            synchronized (LongRequestAdmission.this) {
                if (released) return;
                released = true;
                activeTotal--;
                activeByKind.put(kind, Integer.valueOf(
                        activeByKind.get(kind).intValue() - 1));
                decrement(activeByPrincipal, principal);
                decrement(activeBySession, session);
            }
        }
    }

    private static Kind parseKind(String kind) {
        if (kind == null) throw invalid("kind");
        try {
            return Kind.valueOf(kind);
        } catch (IllegalArgumentException e) {
            throw invalid("kind");
        }
    }

    private static void requireKind(Kind kind) {
        if (kind == null) throw invalid("kind");
    }

    private static void requireIdentity(String value, String field) {
        if (value == null || value.trim().isEmpty()
                || value.codePointCount(0, value.length()) > MAX_IDENTITY_LENGTH) {
            throw invalid(field);
        }
    }

    private static BusinessException invalid(String field) {
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("field", field);
        return new BusinessException("INVALID_REQUEST", 400,
                "Long request admission input is invalid", details);
    }

    private static BusinessException limitReached(String scope, Kind kind,
            int limit, int active) {
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("scope", scope);
        details.put("kind", kind.name());
        details.put("limit", Integer.valueOf(limit));
        details.put("active", Integer.valueOf(active));
        return new BusinessException("STREAM_LIMIT_REACHED", 429,
                "Long request capacity is exhausted", details);
    }

    private static int count(Map<String, Integer> counts, String key) {
        Integer value = counts.get(key);
        return value == null ? 0 : value.intValue();
    }

    private static void increment(Map<String, Integer> counts, String key) {
        counts.put(key, Integer.valueOf(count(counts, key) + 1));
    }

    private static void decrement(Map<String, Integer> counts, String key) {
        int next = count(counts, key) - 1;
        if (next <= 0) counts.remove(key);
        else counts.put(key, Integer.valueOf(next));
    }

    private static Map<Kind, Integer> immutableKindMap(Map<Kind, Integer> source) {
        EnumMap<Kind, Integer> copy = new EnumMap<Kind, Integer>(Kind.class);
        copy.putAll(source);
        return Collections.unmodifiableMap(copy);
    }
}
