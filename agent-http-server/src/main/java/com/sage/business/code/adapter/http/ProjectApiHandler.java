package com.sage.business.code.adapter.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sage.business.code.application.project.NativeOpenIntentService;
import com.sage.business.code.application.project.ProjectBuildPlanService;
import com.sage.business.code.application.project.VerificationProfileService;
import com.sage.business.code.application.project.ProjectQueryService;
import com.sage.business.code.application.project.ProjectFileReadService;
import com.sage.business.code.application.project.ProjectRegistrationService;
import com.sage.business.code.contract.v2.project.BuildPlanOverrideRequest;
import com.sage.business.code.contract.v2.project.NativeOpenIntentResponse;
import com.sage.business.code.contract.v2.project.ProjectRegistrationRequest;
import com.sage.business.code.contract.v2.project.VerificationProfileRequest;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.net.URLDecoder;

/**
 * /api/v2/projects route group (desktop design §9.3). Registrations accept
 * an absolute path only from the authenticated main; every other route is
 * projectId-only.
 */
public final class ProjectApiHandler extends V2HttpSupport implements V2RouteGroup {
    private final ProjectQueryService projects;
    private final ProjectRegistrationService registrations;
    private final NativeOpenIntentService nativeOpen;
    private final ProjectFileReadService files;
    private final ProjectBuildPlanService buildPlans;
    private final VerificationProfileService verificationProfiles;
    private final com.sage.business.code.application.project
            .WorkspaceEvidenceService workspaceEvidence;

    public ProjectApiHandler(ObjectMapper mapper, String token, ProjectQueryService projects,
            ProjectRegistrationService registrations, NativeOpenIntentService nativeOpen) {
        this(mapper, token, projects, registrations, nativeOpen, null, null);
    }

    public ProjectApiHandler(ObjectMapper mapper, String token, ProjectQueryService projects,
            ProjectRegistrationService registrations, NativeOpenIntentService nativeOpen,
            ProjectFileReadService files) {
        this(mapper, token, projects, registrations, nativeOpen, files, null);
    }

    public ProjectApiHandler(ObjectMapper mapper, String token, ProjectQueryService projects,
            ProjectRegistrationService registrations, NativeOpenIntentService nativeOpen,
            ProjectFileReadService files, ProjectBuildPlanService buildPlans) {
        this(mapper, token, projects, registrations, nativeOpen, files,
                buildPlans, null);
    }

    public ProjectApiHandler(ObjectMapper mapper, String token, ProjectQueryService projects,
            ProjectRegistrationService registrations, NativeOpenIntentService nativeOpen,
            ProjectFileReadService files, ProjectBuildPlanService buildPlans,
            VerificationProfileService verificationProfiles) {
        this(mapper, token, projects, registrations, nativeOpen, files,
                buildPlans, verificationProfiles, null);
    }

    public ProjectApiHandler(ObjectMapper mapper, String token, ProjectQueryService projects,
            ProjectRegistrationService registrations, NativeOpenIntentService nativeOpen,
            ProjectFileReadService files, ProjectBuildPlanService buildPlans,
            VerificationProfileService verificationProfiles,
            com.sage.business.code.application.project
                    .WorkspaceEvidenceService workspaceEvidence) {
        super(mapper, token);
        if (projects == null || registrations == null || nativeOpen == null) {
            throw new IllegalArgumentException("project services are required");
        }
        this.projects = projects;
        this.registrations = registrations;
        this.nativeOpen = nativeOpen;
        this.files = files;
        this.buildPlans = buildPlans;
        this.verificationProfiles = verificationProfiles;
        this.workspaceEvidence = workspaceEvidence;
    }

    @Override
    public boolean handleGroup(HttpExchange exchange, String path, String method)
            throws IOException {
        if (!path.equals("/api/v2/projects") && !path.startsWith("/api/v2/projects/")) {
            return false;
        }
        // File refs may contain encoded slashes. Parse the raw URI here so a
        // workspace-relative path remains one server-owned value instead of
        // being mistaken for extra route segments.
        String rawPath = exchange.getRequestURI().getRawPath();
        String fileMarker = "/api/v2/projects/";
        int filesAt = rawPath == null ? -1 : rawPath.indexOf("/files/", fileMarker.length());
        if (filesAt >= fileMarker.length()) {
            requireAccess(exchange);
            if (!"GET".equals(method)) throw methodNotAllowed();
            if (files == null) throw new com.sage.business.code.domain.BusinessException(
                    "FEATURE_DISABLED", 503, "file read is not enabled on this service");
            String projectId = rawPath.substring(fileMarker.length(), filesAt);
            safeEntityId(projectId, "project");
            String encodedFile = rawPath.substring(filesAt + "/files/".length());
            if (encodedFile.length() == 0) {
                throw new com.sage.business.code.domain.BusinessException(
                        "FILE_REF_INVALID", 400, "fileRef is required");
            }
            final String fileRef;
            try {
                fileRef = URLDecoder.decode(encodedFile, "UTF-8");
            } catch (java.io.UnsupportedEncodingException impossible) {
                throw new IllegalStateException("UTF-8 is unavailable", impossible);
            }
            sendJson(exchange, 200, files.read(projectId, fileRef,
                    queryParam(exchange, "revision")));
            return true;
        }
        String[] parts = path.split("/");
        if ("/api/v2/projects".equals(path)) {
            requireAccess(exchange);
            if (!"GET".equals(method)) throw methodNotAllowed();
            sendJson(exchange, 200, projects.list());
            return true;
        }
        if (parts.length < 5 || !"projects".equals(parts[3])) return false;
        if (parts.length == 5 && "registrations".equals(parts[4])) {
            requireAccess(exchange);
            if (!"POST".equals(method)) throw methodNotAllowed();
            ProjectRegistrationRequest request = body(exchange, ProjectRegistrationRequest.class);
            sendJson(exchange, 201, registrations.register(request, mutationKey(exchange)));
            return true;
        }
        String projectId = parts[4];
        safeEntityId(projectId, "project");
        if (parts.length == 5) {
            requireAccess(exchange);
            if (!"GET".equals(method)) throw methodNotAllowed();
            sendJson(exchange, 200, projects.summary(projectId));
            return true;
        }
        if (parts.length == 6 && "status".equals(parts[5])) {
            requireAccess(exchange);
            if (!"GET".equals(method)) throw methodNotAllowed();
            sendJson(exchange, 200, projects.status(projectId));
            return true;
        }
        if (parts.length == 6 && "session-preflight".equals(parts[5])) {
            requireAccess(exchange);
            if (!"GET".equals(method)) throw methodNotAllowed();
            sendJson(exchange, 200, projects.sessionPreflight(projectId));
            return true;
        }
        // Design 11-01 §10.3: neutral observed evidence. The runtime reads
        // the registered directory itself; the caller supplies nothing but
        // the alias it already owns.
        if (parts.length == 6 && "workspace-evidence".equals(parts[5])) {
            requireAccess(exchange);
            if (!"GET".equals(method)) throw methodNotAllowed();
            requireWorkspaceEvidence();
            sendJson(exchange, 200, workspaceEvidence.evidence(projectId));
            return true;
        }
        if (parts.length == 6 && "effective-tools".equals(parts[5])) {
            requireAccess(exchange);
            if (!"GET".equals(method)) throw methodNotAllowed();
            // WU-09: probe=live binds every declaration to a real target
            // workspace probe; probe=static keeps the legacy projection.
            String probeMode = queryParam(exchange, "probe");
            sendJson(exchange, 200, projects.effectiveTools(projectId, probeMode));
            return true;
        }
        // Design 2026-09-26 §7.2: build-plan surface. Plan views render
        // argv through the shared resolver dry run — no process ever
        // spawns from these routes.
        if (parts.length == 6 && "build-plan".equals(parts[5])) {
            requireAccess(exchange);
            if (!"GET".equals(method)) throw methodNotAllowed();
            requireBuildPlans();
            sendJson(exchange, 200, buildPlans.buildPlan(projectId));
            return true;
        }
        if (parts.length == 7 && "build-plan".equals(parts[5])
                && "overrides".equals(parts[6])) {
            requireAccess(exchange);
            if (!"PUT".equals(method)) throw methodNotAllowed();
            requireBuildPlans();
            mutationKey(exchange);
            BuildPlanOverrideRequest request =
                    body(exchange, BuildPlanOverrideRequest.class);
            sendJson(exchange, 200,
                    buildPlans.putOverride(projectId, request));
            return true;
        }
        if (parts.length == 7 && "build-plan".equals(parts[5])
                && "preview".equals(parts[6])) {
            requireAccess(exchange);
            if (!"POST".equals(method)) throw methodNotAllowed();
            requireBuildPlans();
            mutationKey(exchange);
            BuildPlanOverrideRequest request =
                    body(exchange, BuildPlanOverrideRequest.class);
            sendJson(exchange, 200,
                    buildPlans.preview(projectId, request));
            return true;
        }
        // Design 2026-09-26 §10.2: verification-profile surface.
        // Writes are optimistic — If-Match carries the client's known
        // revision; a stale write fails instead of silently losing
        // someone else's profile edit.
        if (parts.length == 6 && "verification-profiles".equals(parts[5])) {
            requireAccess(exchange);
            if (!"GET".equals(method)) throw methodNotAllowed();
            requireVerificationProfiles();
            sendJson(exchange, 200,
                    verificationProfiles.list(projectId));
            return true;
        }
        if (parts.length == 7 && "verification-profiles".equals(parts[5])) {
            requireAccess(exchange);
            requireVerificationProfiles();
            String profileId = parts[6];
            if ("GET".equals(method)) {
                sendJson(exchange, 200,
                        verificationProfiles.get(projectId, profileId));
                return true;
            }
            if ("PUT".equals(method)) {
                mutationKey(exchange);
                VerificationProfileRequest request =
                        body(exchange, VerificationProfileRequest.class);
                sendJson(exchange, 200,
                        verificationProfiles.put(projectId, profileId,
                                request, ifMatchRevision(exchange)));
                return true;
            }
            throw methodNotAllowed();
        }
        if (parts.length == 6 && "native-open-intents".equals(parts[5])) {
            requireAccess(exchange);
            if (!"POST".equals(method)) throw methodNotAllowed();
            // a mutation like any other: single-use identity, bounded key
            mutationKey(exchange);
            NativeOpenIntentService.ActionBody body = readAction(exchange);
            NativeOpenIntentResponse intent = nativeOpen.issue(projectId, body.action, body.fileRef);
            sendJson(exchange, 201, intent);
            return true;
        }
        return false;
    }

    private void requireBuildPlans() {
        if (buildPlans == null) {
            throw new com.sage.business.code.domain.BusinessException(
                    "FEATURE_DISABLED", 503,
                    "build-plan service is not enabled on this service");
        }
    }

    private void requireVerificationProfiles() {
        if (verificationProfiles == null) {
            throw new com.sage.business.code.domain.BusinessException(
                    "FEATURE_DISABLED", 503,
                    "verification-profile service is not enabled on this service");
        }
    }

    private void requireWorkspaceEvidence() {
        if (workspaceEvidence == null) {
            throw new com.sage.business.code.domain.BusinessException(
                    "FEATURE_DISABLED", 503,
                    "workspace-evidence service is not enabled on this service");
        }
    }

    /** If-Match revision: header wins; a bare number or "N" etag form. */
    private static Long ifMatchRevision(HttpExchange exchange) {
        String header = exchange.getRequestHeaders() == null
                ? null
                : exchange.getRequestHeaders().getFirst("If-Match");
        if (header == null) {
            return null;
        }
        String trimmed = header.trim();
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"")
                && trimmed.length() > 1) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        if (trimmed.isEmpty() || "*".equals(trimmed)) {
            return null;
        }
        try {
            return Long.valueOf(Long.parseLong(trimmed));
        } catch (NumberFormatException bad) {
            throw new com.sage.business.code.domain.BusinessException(
                    "INVALID_REQUEST", 400,
                    "If-Match must carry the profile revision number");
        }
    }

    /** Body is optional; absent body defaults to REVEAL. */
    private NativeOpenIntentService.ActionBody readAction(HttpExchange exchange) throws IOException {
        byte[] raw = readLimited(exchange.getRequestBody());
        if (raw.length == 0) {
            NativeOpenIntentService.ActionBody empty = new NativeOpenIntentService.ActionBody();
            empty.action = NativeOpenIntentService.ACTION_REVEAL;
            return empty;
        }
        try {
            return mapper.readValue(raw, NativeOpenIntentService.ActionBody.class);
        } catch (Exception failure) {
            throw new com.sage.business.code.domain.BusinessException("INVALID_REQUEST", 400,
                    "Request body cannot be parsed");
        }
    }
}
