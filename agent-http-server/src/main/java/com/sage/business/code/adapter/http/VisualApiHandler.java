package com.sage.business.code.adapter.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sage.business.code.contract.v2.visual.CandidateDecisionRequest;
import com.sage.business.code.contract.v2.visual.VisualChangeRequest;
import com.sage.business.code.contract.v2.visual.VisualCheckpointRequest;
import com.sage.business.code.contract.v2.visual.VisualControlRequest;
import com.sage.business.code.contract.v2.visual.VisualDraftOpenRequest;
import com.sage.business.code.contract.v2.visual.VisualExecutionStartRequest;
import com.sage.business.code.contract.v2.visual.VisualExportRequest;
import com.sage.business.code.contract.v2.visual.VisualInputRequest;
import com.sage.business.code.contract.v2.visual.VisualLockRequest;
import com.sage.business.code.contract.v2.visual.VisualProblemCodes;
import com.sage.business.code.contract.v2.visual.VisualProjectCreateRequest;
import com.sage.business.code.contract.v2.visual.VisualReleaseRequest;
import com.sage.business.code.contract.v2.visual.VisualRollbackRequest;
import com.sage.business.code.contract.v2.visual.VisualSessionDto;
import com.sage.business.code.producthost.visual.VisualService;
import com.sage.business.code.producthost.visual.VisualStoreException;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code /api/v2/visual} route group (design 01 &sect;16). Thin adapter:
 * every verb delegates to {@link VisualService}; {@link VisualStoreException}
 * codes map straight onto problem responses — the wire never invents its
 * own failure vocabulary. Mutations are idempotent on clientOpId at the
 * store, so the adapter only forwards.
 */
public final class VisualApiHandler extends V2HttpSupport
        implements V2RouteGroup {

    public static final String BASE = "/api/v2/visual";

    private final VisualService service;

    public VisualApiHandler(ObjectMapper mapper, String token,
            VisualService service) {
        super(mapper, token);
        if (service == null) {
            throw new IllegalArgumentException("visual service required");
        }
        this.service = service;
    }

    @Override
    public boolean handleGroup(HttpExchange exchange, String path,
            String method) throws IOException {
        if (!path.equals(BASE) && !path.startsWith(BASE + "/")) {
            return false;
        }
        requireAccess(exchange);
        String tail = path.length() > BASE.length()
                ? path.substring(BASE.length() + 1) : "";
        try {
            route(exchange, method, tail);
        } catch (VisualStoreException failure) {
            sendProblem(exchange, failure.code(),
                    httpStatus(failure.code()), failure.getMessage(), null);
        }
        return true;
    }

    private void route(HttpExchange exchange, String method, String tail)
            throws IOException {
        String[] seg = tail.isEmpty() ? new String[0] : tail.split("/");

        // /api/v2/visual/projects
        if (seg.length == 1 && "projects".equals(seg[0])) {
            if ("POST".equals(method)) {
                sendJson(exchange, 201, service.createProject(
                        body(exchange, VisualProjectCreateRequest.class)));
                return;
            }
            if ("GET".equals(method)) {
                sendJson(exchange, 200, service.listProjects());
                return;
            }
            throw methodNotAllowed();
        }

        // Everything below is /projects/{pid}/...
        if (seg.length < 2 || !"projects".equals(seg[0])) {
            throw new VisualStoreException(
                    VisualProblemCodes.PROJECT_NOT_FOUND, "bad route");
        }
        String pid = seg[1];
        safeEntityId(pid, "project");

        if (seg.length == 2 && "GET".equals(method)) {
            sendJson(exchange, 200, service.getProject(pid));
            return;
        }

        String sub = seg.length > 2 ? seg[2] : "";

        if ("capabilities".equals(sub) && seg.length == 3
                && "GET".equals(method)) {
            sendJson(exchange, 200, capabilities(pid));
            return;
        }
        if ("revisions".equals(sub)) {
            if (seg.length == 3 && "GET".equals(method)) {
                sendJson(exchange, 200, service.listRevisions(pid));
                return;
            }
            if (seg.length == 4 && "GET".equals(method)) {
                sendJson(exchange, 200,
                        service.getRevision(pid, seg[3]));
                return;
            }
        }
        if ("intents".equals(sub) && seg.length == 3
                && "GET".equals(method)) {
            sendJson(exchange, 200, service.listIntents(pid));
            return;
        }
        if ("drafts".equals(sub)) {
            if (seg.length == 3 && "POST".equals(method)) {
                sendJson(exchange, 201, service.openDraft(pid,
                        body(exchange, VisualDraftOpenRequest.class)));
                return;
            }
            if (seg.length == 4 && "GET".equals(method)) {
                sendJson(exchange, 200, service.getDraft(pid, seg[3]));
                return;
            }
            if (seg.length == 5 && "changes".equals(seg[4])
                    && "POST".equals(method)) {
                sendJson(exchange, 200, service.applyChange(pid, seg[3],
                        body(exchange, VisualChangeRequest.class)));
                return;
            }
            if (seg.length == 5 && "checkpoint".equals(seg[4])
                    && "POST".equals(method)) {
                VisualCheckpointRequest req = body(exchange,
                        VisualCheckpointRequest.class);
                // Materials arrive as a CAS ref or inline payload written
                // by the Appsmith adapter; for the API surface pass null —
                // the adapter route that carries native bytes is internal.
                sendJson(exchange, 201,
                        service.checkpoint(pid, seg[3], req, null));
                return;
            }
        }
        if ("sessions".equals(sub)) {
            if (seg.length == 3 && "POST".equals(method)) {
                sendJson(exchange, 202, service.startSession(pid,
                        body(exchange, VisualExecutionStartRequest.class)));
                return;
            }
            if (seg.length == 4 && "GET".equals(method)) {
                sendJson(exchange, 200, service.getSession(pid, seg[3]));
                return;
            }
            if (seg.length == 5 && "control".equals(seg[4])
                    && "POST".equals(method)) {
                VisualControlRequest req = body(exchange,
                        VisualControlRequest.class);
                sendJson(exchange, 200, service.control(pid, seg[3],
                        req.getAction(), req.getReason()));
                return;
            }
            if (seg.length == 5 && "inputs".equals(seg[4])
                    && "POST".equals(method)) {
                // User input is journaled; the worker consumes it on the
                // next poll (P1-02 wire-up).
                VisualInputRequest req = body(exchange,
                        VisualInputRequest.class);
                service.store().appendEvent(pid, seg[3], "USER_INPUT",
                        mapper.valueToTree(req));
                sendJson(exchange, 202, service.getSession(pid, seg[3]));
                return;
            }
        }
        if ("candidates".equals(sub)) {
            if (seg.length == 3 && "GET".equals(method)) {
                sendJson(exchange, 200, service.listCandidates(pid));
                return;
            }
            if (seg.length == 4 && "GET".equals(method)) {
                sendJson(exchange, 200,
                        service.getCandidate(pid, seg[3]));
                return;
            }
            if (seg.length == 5 && "decision".equals(seg[4])
                    && "POST".equals(method)) {
                CandidateDecisionRequest req = body(exchange,
                        CandidateDecisionRequest.class);
                // ADOPT requires the Appsmith adapter to supply applied
                // materials; until P1-04 wires it, the API accepts the
                // decision only with an explicit materials payload.
                Object materials = req == null ? null : req.getRationale();
                sendJson(exchange, 200, service.decide(pid, seg[3], req,
                        materials == null ? null : materials.toString()));
                return;
            }
        }
        if ("locks".equals(sub) && seg.length == 3) {
            if ("GET".equals(method)) {
                sendJson(exchange, 200, service.getLocks(pid));
                return;
            }
            if ("POST".equals(method)) {
                sendJson(exchange, 200, service.applyLocks(pid,
                        body(exchange, VisualLockRequest.class)));
                return;
            }
        }
        if ("builds".equals(sub) && seg.length == 3
                && "GET".equals(method)) {
            sendJson(exchange, 200, service.listBuilds(pid));
            return;
        }
        if ("verifications".equals(sub) && seg.length == 3
                && "GET".equals(method)) {
            sendJson(exchange, 200, service.listVerifications(pid));
            return;
        }
        if ("releases".equals(sub)) {
            if (seg.length == 3) {
                if ("GET".equals(method)) {
                    sendJson(exchange, 200, service.listReleases(pid));
                    return;
                }
                if ("POST".equals(method)) {
                    sendJson(exchange, 202, service.requestRelease(pid,
                            body(exchange, VisualReleaseRequest.class)));
                    return;
                }
            }
            if (seg.length == 5 && "rollback".equals(seg[4])
                    && "POST".equals(method)) {
                sendJson(exchange, 202, service.rollback(pid, seg[3],
                        body(exchange, VisualRollbackRequest.class)));
                return;
            }
        }
        if ("exports".equals(sub) && seg.length == 3
                && "POST".equals(method)) {
            // Exporter lands in P1-11; the API records the intent now and
            // reports honestly that the artifact is queued, not built.
            VisualExportRequest req = body(exchange,
                    VisualExportRequest.class);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("state", "QUEUED");
            out.put("revisionId", req == null ? null : req.getRevisionId());
            out.put("note", "exporter capability not yet wired (P1-11)");
            sendJson(exchange, 202, out);
            return;
        }
        throw new VisualStoreException(
                VisualProblemCodes.PROJECT_NOT_FOUND,
                "no such route: " + tail);
    }

    /** Honest capability statement — gates widen only as qualification
     *  evidence lands (G-05/G-06), never by declaration. */
    private Map<String, Object> capabilities(String pid) {
        service.getProject(pid);
        Map<String, Object> caps = new LinkedHashMap<>();
        caps.put("taskKinds", Arrays.asList(
                VisualSessionDto.TASK_BUILD,
                VisualSessionDto.TASK_DESIGN,
                VisualSessionDto.TASK_FIX));
        caps.put("releaseModes", Arrays.asList(
                "LOCAL_CONTAINER", "EXPORT_ARCHIVE", "NAS_OPTIONAL"));
        caps.put("qualifiedReleaseModes",
                java.util.Collections.singletonList("LOCAL_CONTAINER"));
        caps.put("flowNodeKinds",
                com.sage.business.code.contract.v2.visual
                        .FlowDefinitionDto.NODE_KINDS);
        caps.put("componentCatalogRef",
                "visual-product-20261008/p1-00-base-qualification/"
                        + "component-catalog-mapping.md");
        caps.put("exporter", false);
        caps.putAll(service.driver().facts());
        return caps;
    }

    private static int httpStatus(String code) {
        if (code == null) {
            return 500;
        }
        if (code.endsWith("NOT_FOUND")) {
            return 404;
        }
        if (code.endsWith("CONFLICT") || code.endsWith("DIGEST_MISMATCH")
                || code.endsWith("LOCKED_PATH")) {
            return 409;
        }
        if (code.endsWith("INVALID") || code.endsWith("UNSUPPORTED")) {
            return 422;
        }
        if (code.endsWith("UNAVAILABLE") || code.endsWith("TIMEOUT")
                || code.endsWith("UNREACHABLE")) {
            return 503;
        }
        return 500;
    }
}
