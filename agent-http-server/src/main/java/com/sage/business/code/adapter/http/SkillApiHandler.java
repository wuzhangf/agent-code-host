package com.sage.business.code.adapter.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sage.business.code.application.extension.ExtensionPlane;
import com.sage.business.code.application.skill.SkillCatalogService;
import com.sage.business.code.application.skill.SkillPackageImportService;
import com.sage.business.code.contract.v2.skill.SkillImportPreviewRequest;
import com.sage.business.code.contract.v2.skill.SkillImportRequest;
import com.sage.business.code.contract.v2.skill.SkillImportSourceRequest;
import com.sage.business.code.contract.v2.skill.SkillRegisterRequest;
import com.sage.business.code.contract.v2.skill.SkillStatusRequest;
import com.sage.business.code.contract.v2.skill.SkillVersionSelectRequest;
import com.sage.business.code.domain.BusinessException;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;

/**
 * Skill routes (design §9.1). The legacy catalog surface stays intact;
 * the governed import surface (sources → preview → import → versions →
 * manifest → current-version) delegates to {@link SkillPackageImportService}
 * inside the extension plane and fails closed when it is not assembled.
 * All mutations require an Idempotency-Key.
 */
public final class SkillApiHandler extends V2HttpSupport implements V2RouteGroup {

    private static final String PRINCIPAL = "local-owner";

    private final SkillCatalogService skills;
    private final ExtensionPlane extension;
    private final SkillPackageImportService importer;

    public SkillApiHandler(ObjectMapper mapper, String token, SkillCatalogService skills,
            ExtensionPlane extension, SkillPackageImportService importer) {
        super(mapper, token);
        if (skills == null) throw new IllegalArgumentException("skills is required");
        this.skills = skills;
        this.extension = extension;
        this.importer = importer;
    }

    /** Legacy constructor: catalog-only surface. */
    public SkillApiHandler(ObjectMapper mapper, String token, SkillCatalogService skills) {
        this(mapper, token, skills, null, null);
    }

    @Override public boolean handleGroup(HttpExchange exchange, String path, String method)
            throws IOException {
        if (!path.startsWith("/api/v2/skills")) return false;
        String[] parts = path.split("/");
        // Governed import surface (§9.1):
        if (parts.length == 5 && "skills".equals(parts[3])
                && "import-sources".equals(parts[4])) {
            requireAccess(exchange);
            requireImporter();
            if (!"POST".equals(method)) throw methodNotAllowed();
            mutationKey(exchange);
            SkillImportSourceRequest request = body(exchange, SkillImportSourceRequest.class);
            sendJson(exchange, 201, importer.createSource(
                    request.getRootHint(), request.getDisplayName()));
            return true;
        }
        if (parts.length == 5 && "skills".equals(parts[3])
                && "import-previews".equals(parts[4])) {
            requireAccess(exchange);
            requireImporter();
            if (!"POST".equals(method)) throw methodNotAllowed();
            mutationKey(exchange);
            SkillImportPreviewRequest request = body(exchange, SkillImportPreviewRequest.class);
            sendJson(exchange, 200, importer.preview(request.getSourceHandle()));
            return true;
        }
        if (parts.length == 5 && "skills".equals(parts[3])
                && "imports".equals(parts[4])) {
            requireAccess(exchange);
            requireImporter();
            if (!"POST".equals(method)) throw methodNotAllowed();
            mutationKey(exchange);
            SkillImportRequest request = body(exchange, SkillImportRequest.class);
            sendJson(exchange, 201, importer.importPackage(PRINCIPAL,
                    request.getPreviewId(), request.getExpectedSourceDigest(),
                    request.getScope(), request.getProjectAlias(), request.getName()));
            return true;
        }
        if (parts.length == 4 && "skills".equals(parts[3])) {
            requireAccess(exchange);
            if ("GET".equals(method)) {
                String scope = queryParam(exchange, "scope");
                String view = queryParam(exchange, "view");
                if (importer != null && "governed".equals(view)) {
                    sendJson(exchange, 200, importer.list(PRINCIPAL, scope));
                } else {
                    sendJson(exchange, 200, skills.list());
                }
                return true;
            }
            if ("POST".equals(method)) {
                String key = mutationKey(exchange);
                SkillRegisterRequest request = body(exchange, SkillRegisterRequest.class);
                sendJson(exchange, 201, skills.register(request, key));
                return true;
            }
            throw methodNotAllowed();
        }
        if (parts.length == 5 && "skills".equals(parts[3])) {
            // GET /api/v2/skills/{skillId} — governed detail.
            requireAccess(exchange);
            if (!"GET".equals(method)) return false;
            requireImporter();
            sendJson(exchange, 200, importer.detail(PRINCIPAL, parts[4]));
            return true;
        }
        if (parts.length == 6 && "skills".equals(parts[3]) && "status".equals(parts[5])) {
            requireAccess(exchange);
            if (!"POST".equals(method)) throw methodNotAllowed();
            String key = mutationKey(exchange);
            SkillStatusRequest request = body(exchange, SkillStatusRequest.class);
            sendJson(exchange, 200, skills.changeStatus(parts[4], request, key));
            return true;
        }
        if (parts.length == 6 && "skills".equals(parts[3]) && "versions".equals(parts[5])) {
            requireAccess(exchange);
            requireImporter();
            if (!"GET".equals(method)) throw methodNotAllowed();
            sendJson(exchange, 200, importer.versions(PRINCIPAL, parts[4]));
            return true;
        }
        if (parts.length == 8 && "skills".equals(parts[3]) && "versions".equals(parts[5])
                && "manifest".equals(parts[7])) {
            requireAccess(exchange);
            requireImporter();
            if (!"GET".equals(method)) throw methodNotAllowed();
            sendJson(exchange, 200,
                    importer.manifest(PRINCIPAL, parts[4], parts[6]));
            return true;
        }
        if (parts.length == 6 && "skills".equals(parts[3])
                && "current-version".equals(parts[5])) {
            requireAccess(exchange);
            requireImporter();
            if (!"POST".equals(method)) throw methodNotAllowed();
            mutationKey(exchange);
            SkillVersionSelectRequest request =
                    body(exchange, SkillVersionSelectRequest.class);
            sendJson(exchange, 200, importer.selectVersion(PRINCIPAL, parts[4],
                    request.getVersionDigest(), request.getExpectedRevision()));
            return true;
        }
        return false;
    }

    private void requireImporter() {
        if (importer == null) {
            throw new BusinessException("EXT_HOST_NOT_WIRED", 503,
                    "the extension host is not assembled on this service");
        }
    }
}
