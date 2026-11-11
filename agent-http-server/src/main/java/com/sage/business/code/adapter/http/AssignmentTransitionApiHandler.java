package com.sage.business.code.adapter.http;

import com.fasterxml.jackson.databind.*;
import com.sage.business.code.application.collaboration.*;
import com.sage.business.code.contract.v2.run.AssignmentTransitionRequest;
import com.sage.business.code.domain.BusinessException;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;

public final class AssignmentTransitionApiHandler extends V2HttpSupport implements V2RouteGroup {
    private final AssignmentTransitionService transitions;
    public AssignmentTransitionApiHandler(ObjectMapper mapper,String token,AssignmentTransitionService transitions){super(mapper,token);this.transitions=transitions;}
    public boolean handleGroup(HttpExchange x,String path,String method)throws IOException {
        if(!path.matches("/api/v2/runs/[^/]+/assignment-transitions(?:/[^/]+(?:/cancel)?)?"))return false;
        requireAccess(x);String actor=(String)x.getAttribute(V2ApiDispatcher.CLIENT_SESSION_ID_ATTRIBUTE);
        if(actor==null)throw new BusinessException("CLIENT_REGISTRATION_REQUIRED",428,"Registered client identity is required");
        String[] p=path.split("/");String rid=p[4];safeEntityId(rid,"run");
        if("GET".equals(method)){sendJson(x,200,p.length==6?CollaborationStore.map("items",transitions.list(rid)):transitions.get(rid,p[6]));return true;}
        if(!"POST".equals(method))throw methodNotAllowed();
        if(p.length==8&&"cancel".equals(p[7])){sendJson(x,200,transitions.cancel(rid,p[6],mutationKey(x)));return true;}
        AssignmentTransitionRequest request;
        try {byte[] raw=readLimited(x.getRequestBody());JsonNode tree=mapper.readTree(raw);if(tree==null||!tree.isObject())throw new IllegalArgumentException();
            for(String field:new String[]{"schemaVersion","transitionId","expectedRunStateVersion","expectedExecutionEpoch","expectedModelSettingsRevision","preflightDigest","pendingInputPolicy"})
                if(tree.has(field)&&!tree.get(field).isTextual())throw new IllegalArgumentException();
            JsonNode target=tree.path("target");if(!target.isObject())throw new IllegalArgumentException();
            for(String f:new String[]{"harnessId","agentProfileId","agentProfileRevision"})if(target.has(f)&&!target.get(f).isTextual())throw new IllegalArgumentException();
            JsonNode selection=target.get("modelSelection");if(selection!=null){if(!selection.isObject()||selection.has("mode")&&!selection.get("mode").isTextual())throw new IllegalArgumentException();
                JsonNode settings=selection.get("settings");if(settings!=null&&!settings.isNull()){if(!settings.isObject())throw new IllegalArgumentException();java.util.Iterator<java.util.Map.Entry<String,JsonNode>> entries=settings.fields();while(entries.hasNext()){
                    java.util.Map.Entry<String,JsonNode> entry=entries.next();if(entry.getValue().isNull())continue;
                    if(java.util.Arrays.asList("reasoningBudgetTokens","contextMaxTokens","outputMaxTokens").contains(entry.getKey())){if(!entry.getValue().isIntegralNumber())throw new IllegalArgumentException();}
                    else if(!entry.getValue().isTextual())throw new IllegalArgumentException();
                }}
            }
            request=mapper.readerFor(AssignmentTransitionRequest.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(raw);
        }catch(Exception invalid){throw new BusinessException("INVALID_REQUEST",400,"Invalid strict assignment transition request");}
        if(p.length==7&&"preflight".equals(p[6]))sendJson(x,200,transitions.preflight(rid,request));
        else if(p.length==6)sendJson(x,202,transitions.submit(rid,mutationKey(x),request,actor));else throw methodNotAllowed();return true;
    }
}
