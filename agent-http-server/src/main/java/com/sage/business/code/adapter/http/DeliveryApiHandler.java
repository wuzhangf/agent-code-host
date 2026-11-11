package com.sage.business.code.adapter.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sage.business.code.application.delivery.DeliveryControlService;
import com.sage.business.code.application.delivery.LaneDeliveryCandidateService;
import com.sage.business.code.contract.v2.delivery.DeliveryChangesResponse;
import com.sage.business.code.contract.v2.delivery.DeliveryChangeFile;
import com.sage.business.code.contract.v2.delivery.DeliveryResponse;
import com.sage.business.code.contract.v2.delivery.DeliveryCommandResponse;
import com.sage.business.code.contract.v2.delivery.DeliveryListResponse;
import com.sage.business.code.contract.v2.delivery.DeliveryCreateRequest;
import com.sage.business.code.contract.v2.delivery.DeliveryPrepareRequest;
import com.sage.business.code.contract.v2.delivery.DeliveryDecisionRequest;
import com.sage.business.code.contract.v2.delivery.DeliveryApplyRequest;
import com.sage.business.code.contract.v2.delivery.DeliveryReconcileRequest;
import com.sage.business.code.contract.v2.delivery.DeliveryRebaseRequest;
import com.sage.business.code.domain.BusinessException;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.List;

/** HTTP v2 delivery control-plane. Physical paths and worker leases never cross this boundary. */
public final class DeliveryApiHandler extends V2HttpSupport implements V2RouteGroup {
    private static final String PREFIX = "/api/v2/deliveries";
    private final DeliveryControlService deliveries;
    private final LaneDeliveryCandidateService candidates;
    private final com.sage.business.code.application.sync
            .DeliveryBundleService bundleService;

    public DeliveryApiHandler(ObjectMapper mapper, String token,
            DeliveryControlService deliveries) {
        this(mapper, token, deliveries, null, null);
    }

    public DeliveryApiHandler(ObjectMapper mapper, String token,
            DeliveryControlService deliveries,
            LaneDeliveryCandidateService candidates) {
        this(mapper, token, deliveries, candidates, null);
    }

    public DeliveryApiHandler(ObjectMapper mapper, String token,
            DeliveryControlService deliveries,
            LaneDeliveryCandidateService candidates,
            com.sage.business.code.application.sync.DeliveryBundleService
                    bundleService) {
        super(mapper, token);
        if (deliveries == null) throw new IllegalArgumentException("delivery service is required");
        this.deliveries = deliveries;
        this.candidates = candidates;
        this.bundleService = bundleService;
    }

    @Override
    public boolean handleGroup(HttpExchange exchange, String path, String method)
            throws IOException {
        if (path.matches("/api/v2/projects/[^/]+/deliveries")) {
            requireAccess(exchange);
            if (!"GET".equals(method)) throw methodNotAllowed();
            String projectId = path.substring("/api/v2/projects/".length(),
                    path.length() - "/deliveries".length());
            safeEntityId(projectId, "project");
            DeliveryListResponse page = new DeliveryListResponse();
            page.setItems(deliveries.list(projectId));
            page.setTruncated(false);
            returnJson(exchange, 200, page);
            return true;
        }
        if (!path.equals(PREFIX) && !path.startsWith(PREFIX + "/")) return false;
        requireAccess(exchange);
        if (PREFIX.equals(path)) {
            if (!"POST".equals(method)) throw methodNotAllowed();
            DeliveryCreateRequest input = body(exchange, DeliveryCreateRequest.class);
            DeliveryControlService.CreateRequest request = new DeliveryControlService.CreateRequest();
            request.projectId = input.getProjectId(); request.workspaceLaneId = input.getWorkspaceLaneId();
            request.changeSetFingerprint = input.getChangeSetFingerprint();
            request.baseRevision = input.getBaseRevision();
            request.expectedTargetOid = input.getExpectedTargetOid();
            request.candidateRef = input.getCandidateRef();
            request.targetPolicy = input.getTargetPolicy();
            request.verificationRecipeFingerprint = input.getVerificationRecipeFingerprint();
            request.dependencyClosureFingerprint = input.getDependencyClosureFingerprint();
            DeliveryControlService.CommandResult result = deliveries.create(request,
                    mutationKey(exchange));
            returnJson(exchange, 201, command(result));
        } else {
            String[] parts = path.split("/");
            if (parts.length < 5) return false;
            String deliveryId = parts[4];
            safeEntityId(deliveryId, "delivery");
            if (parts.length == 5) {
                if (!"GET".equals(method)) throw methodNotAllowed();
                returnJson(exchange, 200, deliveries.get(deliveryId));
            } else if (parts.length == 6 && "bundle".equals(parts[5])
                    && "GET".equals(method)) {
                // WU-10 §7.4: signed zip of the frozen candidate —
                // changeset.json + baseline-manifest.json + blobs/ +
                // bundle-manifest.json (HMAC over canonical manifest).
                if (bundleService == null) {
                    throw new BusinessException("DELIVERY_BUNDLE_UNAVAILABLE",
                            503, "Delivery bundle export is not wired");
                }
                byte[] zip = bundleService.export(deliveryId);
                exchange.getResponseHeaders().set("Content-Type",
                        "application/zip");
                exchange.getResponseHeaders().set("Content-Disposition",
                        "attachment; filename=delivery-" + deliveryId
                                + ".bundle.zip");
                exchange.getResponseHeaders().set("Content-Length",
                        String.valueOf(zip.length));
                exchange.sendResponseHeaders(200, zip.length);
                java.io.OutputStream out = exchange.getResponseBody();
                try {
                    out.write(zip);
                } finally {
                    out.close();
                }
            } else if (parts.length == 6 && "changes".equals(parts[5])
                    && "GET".equals(method)) {
                if (candidates == null) {
                    throw new BusinessException("DELIVERY_CHANGES_UNAVAILABLE", 503,
                            "Delivery change preview is not wired");
                }
                returnJson(exchange, 200, changes(candidates.changes(deliveryId)));
            } else {
                if (parts.length != 6 || !"POST".equals(method)) throw methodNotAllowed();
                String command = parts[5];
                String key = mutationKey(exchange);
                if ("prepare".equals(command) || "preview".equals(command)) {
                    DeliveryPrepareRequest input = body(exchange, DeliveryPrepareRequest.class);
                    DeliveryControlService.PrepareRequest request = new DeliveryControlService.PrepareRequest();
                    request.expectedStateVersion = input.getExpectedStateVersion();
                    request.proposalFingerprint = input.getProposalFingerprint();
                    request.changeSetFingerprint = input.getChangeSetFingerprint();
                    request.baseRevision = input.getBaseRevision();
                    request.expectedTargetOid = input.getExpectedTargetOid();
                    request.targetPolicy = input.getTargetPolicy();
                    request.verificationRecipeFingerprint = input.getVerificationRecipeFingerprint();
                    request.candidateRef = input.getCandidateRef();
                    DeliveryControlService.CommandResult result = deliveries.prepare(deliveryId, request, key);
                    returnJson(exchange, 200, command(result));
                } else if ("decisions".equals(command)) {
                    DeliveryDecisionRequest input = body(exchange, DeliveryDecisionRequest.class);
                    DeliveryControlService.DecisionRequest request = new DeliveryControlService.DecisionRequest();
                    request.expectedStateVersion = input.getExpectedStateVersion(); request.decision = input.getDecision();
                    request.approvalFingerprint = input.getApprovalFingerprint(); request.reason = input.getReason();
                    DeliveryControlService.CommandResult result = deliveries.decide(deliveryId, request, key);
                    returnJson(exchange, 200, command(result));
                } else if ("apply".equals(command)) {
                    DeliveryApplyRequest input = body(exchange, DeliveryApplyRequest.class);
                    DeliveryControlService.ApplyRequest request = new DeliveryControlService.ApplyRequest();
                    request.expectedStateVersion = input.getExpectedStateVersion(); request.approvalFingerprint = input.getApprovalFingerprint();
                    returnJson(exchange, 202, command(deliveries.apply(deliveryId, request, key)));
                } else if ("reconcile".equals(command)) {
                    DeliveryReconcileRequest input = body(exchange, DeliveryReconcileRequest.class);
                    DeliveryControlService.ReconcileRequest request = new DeliveryControlService.ReconcileRequest();
                    request.expectedStateVersion = input.getExpectedStateVersion(); request.reconcileRef = input.getReconcileRef();
                    request.outcome = input.getOutcome(); request.journalRef = input.getJournalRef();
                    request.detail = input.getDetail();
                    request.deliveryId = deliveryId;
                    returnJson(exchange, 200, command(deliveries.reconcile(request, key)));
                } else if ("rebase".equals(command)) {
                    DeliveryRebaseRequest input = body(exchange, DeliveryRebaseRequest.class);
                    DeliveryControlService.RebaseRequest request = new DeliveryControlService.RebaseRequest();
                    request.expectedStateVersion = input.getExpectedStateVersion();
                    request.deliveryId = deliveryId;
                    returnJson(exchange, 202, command(deliveries.rebase(request, key)));
                } else {
                    throw new BusinessException("INVALID_REQUEST", 404,
                            "Delivery command was not found");
                }
            }
        }
        return true;
    }

    private void returnJson(HttpExchange exchange, int status, Object value) throws IOException {
        sendJson(exchange, status, value);
    }

    private static DeliveryCommandResponse command(DeliveryControlService.CommandResult result) {
        DeliveryCommandResponse out = new DeliveryCommandResponse();
        out.setDelivery(result.delivery); out.setReceipt(result.receipt);
        return out;
    }

    private static DeliveryChangesResponse changes(
            LaneDeliveryCandidateService.DeliveryChanges input) {
        DeliveryChangesResponse out = new DeliveryChangesResponse();
        out.setDeliveryId(input.deliveryId);
        out.setChangeSetFingerprint(input.changeSetFingerprint);
        out.setBaseRevision(input.baseRevision);
        out.setTargetLabel(input.targetLabel);
        out.setTargetFingerprint(input.targetFingerprint);
        out.setCanApply(input.canApply);
        out.setConflictPaths(input.conflictPaths);
        List<DeliveryChangeFile> files = new java.util.ArrayList<DeliveryChangeFile>();
        for (LaneDeliveryCandidateService.DeliveryChangeFile file : input.files) {
            DeliveryChangeFile outFile = new DeliveryChangeFile();
            outFile.setPath(file.path);
            outFile.setChangeType(file.changeType);
            outFile.setBeforeSha256(file.beforeSha256);
            outFile.setAfterSha256(file.afterSha256);
            outFile.setBeforeText(file.beforeText);
            outFile.setAfterText(file.afterText);
            files.add(outFile);
        }
        out.setFiles(files);
        return out;
    }
}
