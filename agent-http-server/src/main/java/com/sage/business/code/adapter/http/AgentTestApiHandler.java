package com.sage.business.code.adapter.http;

import com.agent.product.design.api.DesignErrorCode;
import com.agent.product.design.api.DesignException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sage.business.code.application.agenttest.AgentTestService;
import com.sage.business.code.domain.BusinessException;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Standalone agent-test HTTP surface (console-recovery 01 §5,
 * AT-01..AT-03), registered only when the runtime is started with
 * {@code -Dacs.agent.tests.enabled=true}. Serves the read-only
 * capability view, real input registration, run records and bounded
 * native product actions for independent Design testing — it never
 * touches Work, Flow or Worker state.
 */
public final class AgentTestApiHandler extends V2HttpSupport
        implements V2RouteGroup {

    private static final String BASE = "/api/v2/agent-tests";

    private final AgentTestService service;

    public AgentTestApiHandler(ObjectMapper mapper, String token,
                               AgentTestService service) {
        super(mapper, token);
        if (service == null) {
            throw new IllegalArgumentException(
                    "agent-test service is required");
        }
        this.service = service;
    }

    @Override
    public boolean handleGroup(HttpExchange exchange, String path,
                               String method) throws IOException {
        if (!BASE.equals(path) && !path.startsWith(BASE + "/")) {
            return false;
        }
        try {
            return route(exchange, path, method);
        } catch (DesignException failure) {
            sendProblem(exchange, failure.getCode().name(),
                    statusFor(failure.getCode()), failure.getMessage(),
                    Collections.<String, Object>singletonMap("retryable",
                            failure.isRetryable()));
            return true;
        } catch (IllegalArgumentException failure) {
            sendProblem(exchange, "INVALID_REQUEST", 400,
                    failure.getMessage(), null);
            return true;
        } catch (BusinessException failure) {
            sendProblem(exchange, failure.getCode(),
                    failure.getHttpStatus(), failure.getMessage(),
                    failure.getDetails());
            return true;
        }
    }

    private boolean route(HttpExchange exchange, String path,
                          String method) throws IOException {
        requireAccess(exchange);
        String rest = path.substring(BASE.length());
        String[] parts = rest.isEmpty()
                ? new String[0]
                : rest.substring(1).split("/");

        if (parts.length == 1 && "capabilities".equals(parts[0])) {
            requireGet(method);
            sendJson(exchange, 200, service.capabilities());
            return true;
        }
        if (parts.length == 1 && "cases".equals(parts[0])) {
            requireGet(method);
            // Declared recipe catalog arrives with AT-04/06/08; an empty
            // list is the honest state, not a hidden capability.
            sendJson(exchange, 200, map("schemaVersion",
                    "agent-test-cases/v1", "items",
                    Collections.emptyList()));
            return true;
        }
        if (parts.length == 1 && "inputs".equals(parts[0])) {
            if ("POST".equals(method)) {
                String key = mutationKey(exchange);
                Map<String, Object> request = body(exchange, Map.class);
                sendJson(exchange, 201,
                        service.registerInput(request, key));
                return true;
            }
            if ("GET".equals(method)) {
                sendJson(exchange, 200,
                        map("items", service.listInputs()));
                return true;
            }
            throw methodNotAllowed();
        }
        if (parts.length >= 2 && "inputs".equals(parts[0])) {
            String inputSetId = parts[1];
            safeEntityId(inputSetId, "input set");
            if (parts.length == 2) {
                requireGet(method);
                Map<String, Object> view = service.inputView(inputSetId);
                if (view == null) {
                    throw new BusinessException("NOT_FOUND", 404,
                            "input set " + inputSetId + " does not exist");
                }
                sendJson(exchange, 200, view);
                return true;
            }
            if (parts.length == 3 && "content".equals(parts[2])) {
                requireGet(method);
                String doc = queryParam(exchange, "doc");
                Map<String, Object> view = service.inputDocument(
                        inputSetId, doc == null ? "requirement" : doc);
                if (view == null) {
                    throw new BusinessException("NOT_FOUND", 404,
                            "input document is not available");
                }
                sendJson(exchange, 200, view);
                return true;
            }
            return false;
        }
        if (parts.length == 1 && "runs".equals(parts[0])) {
            if ("POST".equals(method)) {
                String key = mutationKey(exchange);
                Map<String, Object> request = body(exchange, Map.class);
                sendJson(exchange, 201,
                        service.createRun(request, key));
                return true;
            }
            if ("GET".equals(method)) {
                sendJson(exchange, 200,
                        map("items", service.listRuns()));
                return true;
            }
            throw methodNotAllowed();
        }
        if (parts.length >= 2 && "runs".equals(parts[0])) {
            String runId = parts[1];
            safeEntityId(runId, "run");
            if (parts.length == 2) {
                requireGet(method);
                Map<String, Object> view = service.runView(runId);
                if (view == null) {
                    throw new BusinessException("NOT_FOUND", 404,
                            "run " + runId + " does not exist");
                }
                sendJson(exchange, 200, view);
                return true;
            }
            if (parts.length == 3 && "actions".equals(parts[2])) {
                requirePost(method);
                mutationKey(exchange);
                Map<String, Object> request = body(exchange, Map.class);
                sendJson(exchange, 202,
                        service.submitAction(runId, request));
                return true;
            }
            if (parts.length == 3 && "events".equals(parts[2])) {
                requireGet(method);
                long after = longParam(exchange, "after", 0L);
                int limit = (int) longParam(exchange, "limit", 200L);
                sendJson(exchange, 200,
                        service.events(runId, after, limit));
                return true;
            }
            if (parts.length == 3 && "export".equals(parts[2])) {
                requireGet(method);
                byte[] bundle = service.exportRun(runId);
                if (bundle == null) {
                    throw new BusinessException("NOT_FOUND", 404,
                            "run " + runId + " does not exist");
                }
                exchange.getResponseHeaders().set("Content-Type",
                        "application/json");
                exchange.getResponseHeaders().set("Content-Disposition",
                        "attachment; filename=\"agent-test-" + runId
                                + ".json\"");
                sendBytes(exchange, 200, bundle);
                return true;
            }
            if (parts.length == 4 && "artifacts".equals(parts[2])) {
                requireGet(method);
                String artifactId = parts[3];
                safeEntityId(artifactId, "artifact");
                Map<String, Object> row =
                        service.artifact(runId, artifactId);
                if (row == null) {
                    throw new BusinessException("NOT_FOUND", 404,
                            "artifact " + artifactId + " does not exist");
                }
                if ("1".equals(queryParam(exchange, "preview"))) {
                    Map<String, Object> preview =
                            new LinkedHashMap<String, Object>(row);
                    byte[] bytes =
                            service.artifactBytes(runId, artifactId);
                    if (bytes != null) {
                        int cap = 32 * 1024;
                        boolean truncated = bytes.length > cap;
                        String text = new String(bytes, 0,
                                truncated ? cap : bytes.length,
                                java.nio.charset.StandardCharsets.UTF_8);
                        preview.put("text", text);
                        preview.put("truncated",
                                Boolean.valueOf(truncated));
                    }
                    sendJson(exchange, 200, preview);
                    return true;
                }
                byte[] bytes = service.artifactBytes(runId, artifactId);
                if (bytes == null) {
                    throw new BusinessException("NOT_FOUND", 404,
                            "artifact bytes are not available");
                }
                Object sha = row.get("sha256");
                if (sha != null) {
                    exchange.getResponseHeaders().set("X-Artifact-Sha256",
                            sha.toString());
                }
                exchange.getResponseHeaders().set("Content-Type",
                        "application/octet-stream");
                sendBytes(exchange, 200, bytes);
                return true;
            }
            return false;
        }
        return false;
    }

    private static void requireGet(String method) {
        if (!"GET".equals(method)) {
            throw methodNotAllowed();
        }
    }

    private static void requirePost(String method) {
        if (!"POST".equals(method)) {
            throw methodNotAllowed();
        }
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            out.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return out;
    }

    /** Same product-code → HTTP table as {@link DesignApiHandler}. */
    private static int statusFor(DesignErrorCode code) {
        switch (code) {
            case INPUT_INVALID:
            case INVALID_REQUEST:
            case NEEDS_METADATA:
                return 400;
            case FORBIDDEN:
                return 403;
            case NOT_FOUND:
                return 404;
            case VERSION_CONFLICT:
            case COMMAND_CONFLICT:
            case INPUT_CHANGED:
            case SCOPE_CHANGE_REQUIRED:
            case ANSWER_STALE:
            case REVIEW_MISMATCH:
            case REVISION_SUPERSEDED:
            case CONCURRENT_MODIFICATION:
            case CANCEL_NOT_CONFIRMED:
            case DESIGN_NOT_READY:
                return 409;
            case MATERIAL_REJECTED:
                return 422;
            case RESEARCH_OUTPUT_INVALID:
            case MODEL_OUTPUT_INVALID:
                return 502;
            case BASELINE_UNAVAILABLE:
            case CAPABILITY_UNAVAILABLE:
            case DISPATCH_REJECTED:
                return 503;
            case OUTCOME_UNKNOWN:
            default:
                return 500;
        }
    }
}
