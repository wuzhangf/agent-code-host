package com.sage.business.code.adapter.http;

import com.agent.framework.capability.git.remote.OperationGrant;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sage.business.code.application.git.ManagedGitWorkspaceService;
import com.sage.business.code.application.git.ManagedRepositoryRegistry;
import com.sage.business.code.contract.v2.git.ChangeSetResponse;
import com.sage.business.code.contract.v2.git.GitCommitResponse;
import com.sage.business.code.contract.v2.git.RemotePublicationResponse;
import com.sage.business.code.contract.v2.workspace.WorkspaceLaneFileResponse;
import com.sage.business.code.contract.v2.workspace.WorkspaceLaneResponse;
import com.sage.business.code.contract.v2.workspace.WorkspaceLaneTreeResponse;
import com.sage.business.code.domain.BusinessException;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Managed remote Git workspace API (Enterprise lane plane).  This handler
 * fronts {@link ManagedGitWorkspaceService}; physical lane paths, cache keys,
 * remote URLs and grant material never appear in requests or responses.
 * Repository registration keeps the remote URL strictly inside runtime state.
 */
public final class ManagedGitWorkspaceApiHandler extends V2HttpSupport
        implements V2RouteGroup {
    private static final String REPO_PREFIX = "/api/v2/managed-repositories";
    private static final String LANE_PREFIX =
            "/api/v2/managed-workspace-lanes";

    private final ManagedRepositoryRegistry repositories;
    private final ManagedGitWorkspaceService workspaces;

    public ManagedGitWorkspaceApiHandler(ObjectMapper mapper, String token,
            ManagedRepositoryRegistry repositories,
            ManagedGitWorkspaceService workspaces) {
        super(mapper, token);
        if (repositories == null || workspaces == null) {
            throw new IllegalArgumentException(
                    "managed workspace dependencies are required");
        }
        this.repositories = repositories;
        this.workspaces = workspaces;
    }

    @Override
    public boolean handleGroup(HttpExchange exchange, String path,
            String method) throws IOException {
        if (path.equals(REPO_PREFIX)
                || path.startsWith(REPO_PREFIX + "/")) {
            return handleRepositories(exchange, path, method);
        }
        if (path.equals(LANE_PREFIX)
                || path.startsWith(LANE_PREFIX + "/")) {
            return handleLanes(exchange, path, method);
        }
        return false;
    }

    private boolean handleRepositories(HttpExchange exchange, String path,
            String method) throws IOException {
        if (REPO_PREFIX.equals(path)) {
            requireAccess(exchange);
            if (!"POST".equals(method)) throw methodNotAllowed();
            RepositoryRegisterBody body =
                    body(exchange, RepositoryRegisterBody.class);
            Map<String, Object> record = repositories.register(
                    body.repositoryId, body.remoteUrl, body.baseRef,
                    body.expectedBaseOid, OperationGrant.anonymous(),
                    mutationKey(exchange), body.provider,
                    body.reviewBaseRefs, body.writeCredentialRef);
            returnJson(exchange, 201, sanitizeRepository(record));
            return true;
        }
        String[] parts = path.split("/");
        // Full path shape is /api/v2/managed-repositories/{id|verify}: the
        // leading empty segment makes the resource index 4, not 3 — the
        // previous length-4 check left verify/lookup permanently unrouted.
        if (parts.length != 5) return false;
        requireAccess(exchange);
        if ("verify".equals(parts[4])) {
            if (!"POST".equals(method)) throw methodNotAllowed();
            VerifyBody body = body(exchange, VerifyBody.class);
            Map<String, Object> result = workspaces.verifyRemote(
                    body.remoteUrl, body.baseRef, mutationKey(exchange));
            returnJson(exchange, 200, result);
            return true;
        }
        if (!"GET".equals(method)) throw methodNotAllowed();
        safeEntityId(parts[4], "repository");
        Map<String, Object> record = repositories.find(parts[4]);
        if (record == null) {
            throw new BusinessException("REPOSITORY_NOT_FOUND", 404,
                    "repository is not registered");
        }
        returnJson(exchange, 200, sanitizeRepository(record));
        return true;
    }

    private boolean handleLanes(HttpExchange exchange, String path,
            String method) throws IOException {
        if (LANE_PREFIX.equals(path)) {
            requireAccess(exchange);
            if (!"POST".equals(method)) throw methodNotAllowed();
            LaneCreateBody body = body(exchange, LaneCreateBody.class);
            WorkspaceLaneResponse lane = workspaces.provisionLane(
                    body.sessionId, body.projectId, body.repositoryId,
                    body.baseRef, body.executionTarget,
                    mutationKey(exchange));
            returnJson(exchange, 201, lane);
            return true;
        }
        String[] parts = path.split("/");
        if (parts.length < 5) return false;
        String laneId = parts[4];
        safeEntityId(laneId, "managed workspace lane");
        if (parts.length == 5) {
            requireAccess(exchange);
            if (!"GET".equals(method)) throw methodNotAllowed();
            returnJson(exchange, 200, workspaces.lane(laneId));
            return true;
        }
        requireAccess(exchange);
        String command = parts[5];
        if (parts.length == 6) {
            if ("bind".equals(command) || "reopen".equals(command)
                    || "release".equals(command)) {
                if (!"POST".equals(method)) throw methodNotAllowed();
                WorkspaceLaneResponse lane = "bind".equals(command)
                        ? workspaces.bind(laneId)
                        : "reopen".equals(command)
                                ? workspaces.reopen(laneId)
                                : workspaces.release(laneId);
                returnJson(exchange, 200, lane);
                return true;
            }
            if ("sync".equals(command)) {
                if (!"POST".equals(method)) throw methodNotAllowed();
                LaneSyncBody body = body(exchange, LaneSyncBody.class);
                WorkspaceLaneResponse lane = workspaces.syncLane(laneId,
                        body == null ? null : body.baseRef,
                        body == null ? null : body.expectedLaneVersion);
                returnJson(exchange, 200, lane);
                return true;
            }
            if ("rebase".equals(command)) {
                // WU-07 设计 §7.2: 分叉后的确定性 squash-rebase —
                // 三方 mergeText 合并 + 受控 rebase 提交；分叉冲突走
                // workspace-conflict/2(origin=LANE_SYNC)。sync 仍 ff-only。
                if (!"POST".equals(method)) throw methodNotAllowed();
                LaneRebaseBody body = body(exchange, LaneRebaseBody.class);
                WorkspaceLaneResponse lane = workspaces.rebase(laneId,
                        body == null ? null : body.expectedLaneVersion,
                        body == null ? null : body.baseRef,
                        body != null && body.operationId != null
                                ? body.operationId : mutationKey(exchange));
                returnJson(exchange, 200, lane);
                return true;
            }
            if ("remote-observation".equals(command)) {
                // U06/N45: pure observation of the lane's remote base ref —
                // detects native pushes the managed lane never sent. Never
                // mutates the lane worktree or record.
                if (!"POST".equals(method)) throw methodNotAllowed();
                RemoteObserveBody body =
                        body(exchange, RemoteObserveBody.class);
                returnJson(exchange, 200, workspaces.observeRemote(laneId,
                        body == null ? null : body.expectedOid,
                        body != null && body.operationId != null
                                ? body.operationId : mutationKey(exchange)));
                return true;
            }
            if ("tree".equals(command)) {
                if (!"GET".equals(method)) throw methodNotAllowed();
                WorkspaceLaneTreeResponse tree = workspaces.tree(laneId,
                        queryParam(exchange, "path"),
                        queryParam(exchange, "cursor"));
                returnJson(exchange, 200, tree);
                return true;
            }
            if ("file".equals(command)) {
                if ("GET".equals(method)) {
                    String fileRef = queryParam(exchange, "path");
                    if (fileRef == null || fileRef.trim().length() == 0) {
                        throw new BusinessException("INVALID_REQUEST", 400,
                                "path query parameter is required");
                    }
                    WorkspaceLaneFileResponse file = "true".equals(queryParam(exchange, "metadataOnly"))
                            ? workspaces.inspectFile(laneId, fileRef)
                            : workspaces.readFile(laneId, fileRef);
                    returnJson(exchange, 200, file);
                    return true;
                }
                if ("PUT".equals(method)) {
                    FileWriteBody body =
                            body(exchange, FileWriteBody.class);
                    WorkspaceLaneFileResponse file = workspaces.writeFile(
                            laneId, body.fileRef, body.baseDigest,
                            body.contentText, body.expectedLaneVersion);
                    returnJson(exchange, 200, file);
                    return true;
                }
                if ("DELETE".equals(method)) {
                    // U13/N90: conditional delete under the same preimage
                    // CAS as write — never removes unobserved content.
                    FileWriteBody body =
                            body(exchange, FileWriteBody.class);
                    returnJson(exchange, 200, workspaces.deleteFile(laneId,
                            body == null ? null : body.fileRef,
                            body == null ? null : body.baseDigest,
                            body == null ? null : body.expectedLaneVersion));
                    return true;
                }
                throw methodNotAllowed();
            }
            if ("blob".equals(command)) {
                // U13/N89: byte-exact file content at a commit for the
                // enterprise writeback merge engine (B/A sides). Read-only.
                if (!"GET".equals(method)) throw methodNotAllowed();
                String oid = queryParam(exchange, "oid");
                String fileRef = queryParam(exchange, "path");
                if (fileRef == null || fileRef.trim().length() == 0
                        || oid == null || oid.trim().length() == 0) {
                    throw new BusinessException("INVALID_REQUEST", 400,
                            "oid and path query parameters are required");
                }
                returnJson(exchange, 200,
                        workspaces.readFileAtCommit(laneId, oid, fileRef));
                return true;
            }
            if ("diff".equals(command)) {
                // U13/N89: changed-path enumeration between two commits —
                // feeds the writeback planner's per-file journal.
                if (!"GET".equals(method)) throw methodNotAllowed();
                String from = queryParam(exchange, "from");
                String to = queryParam(exchange, "to");
                if (from == null || from.trim().length() == 0
                        || to == null || to.trim().length() == 0) {
                    throw new BusinessException("INVALID_REQUEST", 400,
                            "from and to query parameters are required");
                }
                returnJson(exchange, 200,
                        workspaces.diffPaths(laneId, from, to));
                return true;
            }
            if ("changesets".equals(command)) {
                if (!"POST".equals(method)) throw methodNotAllowed();
                ChangeSetCreateBody body =
                        body(exchange, ChangeSetCreateBody.class);
                // U05/N42: the mutation key is the replay identity — a
                // lost response re-sends it and gets the same record.
                ChangeSetResponse changeSet = workspaces.createChangeSet(
                        laneId, body == null
                                ? null : body.expectedLaneVersion,
                        body != null && body.operationId != null
                                ? body.operationId : mutationKey(exchange));
                returnJson(exchange, 201, changeSet);
                return true;
            }
            if ("commits".equals(command)) {
                if (!"POST".equals(method)) throw methodNotAllowed();
                CommitCreateBody body =
                        body(exchange, CommitCreateBody.class);
                GitCommitResponse commit = workspaces.createCommit(laneId,
                        body.changeSetId, body.message,
                        body.expectedLaneVersion,
                        body.operationId == null
                                ? mutationKey(exchange)
                                : body.operationId);
                returnJson(exchange, 201, commit);
                return true;
            }
            if ("publications".equals(command)) {
                if ("GET".equals(method)) {
                    // U09/N67: read-only recovery lookup — a service that
                    // lost the create response re-queries by operationId
                    // instead of re-sending the push.
                    String operationId = queryParam(exchange, "operationId");
                    if (operationId == null
                            || operationId.trim().length() == 0) {
                        throw new BusinessException("INVALID_REQUEST", 400,
                                "operationId query parameter is required");
                    }
                    RemotePublicationResponse found = workspaces
                            .findPublicationByOperation(laneId,
                                    operationId);
                    if (found == null) {
                        throw new BusinessException("PUBLICATION_NOT_FOUND",
                                404, "no publication for operation");
                    }
                    returnJson(exchange, 200, found);
                    return true;
                }
                if (!"POST".equals(method)) throw methodNotAllowed();
                PublicationCreateBody body =
                        body(exchange, PublicationCreateBody.class);
                RemotePublicationResponse publication =
                        workspaces.createPublication(laneId, body.commitId,
                                body.remoteRef, body.expectedRemoteOid,
                                body.operationId == null
                                        ? mutationKey(exchange)
                                        : body.operationId,
                                body.publicationKind,
                                body.credentialRef);
                returnJson(exchange, 201, publication);
                return true;
            }
            return false;
        }
        if (parts.length == 8 && "commits".equals(command)
                && "bundle".equals(parts[7])) {
            // U05/N43–N44: read-only git-object export — the enterprise
            // candidate pipeline durable-finalizes the bundle bytes in the
            // service object store; this endpoint never mutates the lane.
            if (!"GET".equals(method)) throw methodNotAllowed();
            String commitId = parts[6];
            safeEntityId(commitId, "commit");
            returnJson(exchange, 200,
                    workspaces.exportCommitBundle(laneId, commitId));
            return true;
        }
        if (parts.length == 8 && "publications".equals(command)
                && "reconcile".equals(parts[7])) {
            if (!"POST".equals(method)) throw methodNotAllowed();
            String publicationId = parts[6];
            safeEntityId(publicationId, "publication");
            // EN-01: a REVIEW publication reconciles against the
            // concrete change ref the caller observed out-of-band
            // (refs/for/* itself never answers ls-remote). Optional —
            // absent, REVIEW publications stay UNKNOWN honestly.
            String changeRef = queryParam(exchange, "changeRef");
            RemotePublicationResponse publication =
                    workspaces.reconcilePublication(laneId, publicationId,
                            changeRef);
            returnJson(exchange, 200, publication);
            return true;
        }
        return false;
    }

    /** Registration/status view: never includes the remote URL. */
    private static Map<String, Object> sanitizeRepository(
            Map<String, Object> record) {
        Map<String, Object> view =
                new LinkedHashMap<String, Object>(record);
        view.remove("remoteUrl");
        return view;
    }

    private void returnJson(HttpExchange exchange, int status, Object value)
            throws IOException {
        sendJson(exchange, status, value);
    }

    public static final class VerifyBody {
        public String remoteUrl;
        public String baseRef;
    }

    public static final class RepositoryRegisterBody {
        public String repositoryId;
        public String remoteUrl;
        public String baseRef;
        public String expectedBaseOid;
        /** EN-01 optional provider tag (e.g. "gerrit") — absent on old
         *  registrations; REVIEW publications refuse non-gerrit rows. */
        public String provider;
        /** EN-01 short base-branch allowlist for refs/for/* targets. */
        public java.util.List<String> reviewBaseRefs;
        /** EN-01 opaque credential reference resolved by the grant port. */
        public String writeCredentialRef;
    }

    public static final class LaneCreateBody {
        public String sessionId;
        public String projectId;
        public String repositoryId;
        public String baseRef;
        public String executionTarget;
    }

    public static final class LaneSyncBody {
        public String baseRef;
        public Long expectedLaneVersion;
    }

    public static final class LaneRebaseBody {
        public String baseRef;
        public Long expectedLaneVersion;
        public String operationId;
    }

    public static final class RemoteObserveBody {
        public String expectedOid;
        public String operationId;
    }

    public static final class FileWriteBody {
        public String fileRef;
        public String baseDigest;
        public String contentText;
        public Long expectedLaneVersion;
    }

    public static final class ChangeSetCreateBody {
        public Long expectedLaneVersion;
        public String operationId;
    }

    public static final class CommitCreateBody {
        public String changeSetId;
        public String message;
        public Long expectedLaneVersion;
        public String operationId;
    }

    public static final class PublicationCreateBody {
        public String commitId;
        public String remoteRef;
        public String expectedRemoteOid;
        public String operationId;
        /** EN-01: BRANCH (default) or REVIEW — REVIEW targets
         *  refs/for/<base> and is gated on the registration's
         *  provider/reviewBaseRefs allowlist. */
        public String publicationKind;
        /** EN-01: opaque credential ref; default = registration's
         *  writeCredentialRef. Resolved by the grant port only. */
        public String credentialRef;
    }
}
