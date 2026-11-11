package com.sage.business.code.adapter.http;

import com.fasterxml.jackson.databind.*;
import com.sage.business.code.application.collaboration.WorkerExecutionWindowService;
import com.sage.business.code.contract.v2.session.WorkerExecutionWindowRequest;
import com.sage.business.code.domain.BusinessException;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;

public final class WorkerExecutionWindowApiHandler extends V2HttpSupport implements V2RouteGroup {
    private final WorkerExecutionWindowService windows;
    public WorkerExecutionWindowApiHandler(ObjectMapper mapper,String token,WorkerExecutionWindowService windows){super(mapper,token);this.windows=windows;}
    public boolean handleGroup(HttpExchange x,String path,String method)throws IOException {
        if(!path.matches("/api/v2/sessions/[^/]+/worker-execution-window(?:/close)?"))return false;
        requireAccess(x);String actor=(String)x.getAttribute(V2ApiDispatcher.CLIENT_SESSION_ID_ATTRIBUTE);
        if(actor==null)throw new BusinessException("CLIENT_REGISTRATION_REQUIRED",428,"Registered client required");
        String sid=path.split("/")[4];safeEntityId(sid,"session");
        boolean closing=path.endsWith("/close");
        if("GET".equals(method)&&!closing){sendJson(x,200,windows.get(sid));return true;}
        if(!"POST".equals(method))throw methodNotAllowed();
        if(!"WORKER".equals(x.getAttribute(V2ApiDispatcher.CLIENT_KIND_ATTRIBUTE)))throw new BusinessException("ACCESS_DENIED",403,"Only the registered Worker can publish execution window evidence");
        WorkerExecutionWindowRequest request;
        try{byte[] raw=readLimited(x.getRequestBody());JsonNode body=mapper.readTree(raw);if(body==null||!body.isObject()||!body.path("deadlineEpochMillis").isTextual())throw new IllegalArgumentException();
            request=mapper.readerFor(WorkerExecutionWindowRequest.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(raw);
        }catch(Exception invalid){throw new BusinessException("INVALID_REQUEST",400,"Invalid Worker execution window");}
        sendJson(x,200,closing?windows.close(sid,mutationKey(x),request,actor):windows.publish(sid,mutationKey(x),request,actor));return true;
    }
}
