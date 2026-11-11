package com.sage.business.code.adapter.http;

import com.fasterxml.jackson.databind.*;
import com.sage.business.code.application.collaboration.BoundaryInputService;
import com.sage.business.code.application.collaboration.CollaborationStore;
import com.sage.business.code.contract.v2.run.BoundaryInputRequest;
import com.sage.business.code.domain.BusinessException;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;

public final class BoundaryInputApiHandler extends V2HttpSupport implements V2RouteGroup {
    private final BoundaryInputService inputs;
    public BoundaryInputApiHandler(ObjectMapper mapper,String token,BoundaryInputService inputs){super(mapper,token);this.inputs=inputs;}
    private static java.util.Map<String,Object> publicAssignment(java.util.Map<String,Object> value){java.util.Map<String,Object> out=new java.util.LinkedHashMap<String,Object>(value);out.remove("instructions");return out;}
    public boolean handleGroup(HttpExchange x,String path,String method)throws IOException{
        if(!path.matches("/api/v2/runs/[^/]+/(boundary-requests(?:/[^/]+(?:/cancel)?)?|execution-segments)"))return false;
        requireAccess(x);String actor=(String)x.getAttribute(V2ApiDispatcher.CLIENT_SESSION_ID_ATTRIBUTE);
        if(actor==null)throw new BusinessException("CLIENT_REGISTRATION_REQUIRED",428,"Registered client identity is required");
        String[] p=path.split("/");String rid=p[4];safeEntityId(rid,"run");
        if("execution-segments".equals(p[5])){if(!"GET".equals(method))throw methodNotAllowed();sendJson(x,200,CollaborationStore.map("current",publicAssignment(inputs.assignment(rid)),"items",inputs.segments(rid)));return true;}
        if("GET".equals(method)){sendJson(x,200,p.length==6?CollaborationStore.map("items",inputs.list(rid)):inputs.get(rid,p[6]));return true;}
        if(!"POST".equals(method))throw methodNotAllowed();
        if(p.length==8&&"cancel".equals(p[7]))sendJson(x,200,inputs.cancel(rid,p[6],mutationKey(x)));
        else if(p.length==6){BoundaryInputRequest request;try{
            byte[] raw=readLimited(x.getRequestBody());JsonNode tree=mapper.readTree(raw);if(tree==null||!tree.isObject())throw new IllegalArgumentException();
            for(String f:new String[]{"schemaVersion","requestId","kind","expectedExecutionEpoch","text"})if(tree.has(f)&&!tree.get(f).isTextual())throw new IllegalArgumentException();
            if(tree.has("sourceRefs")){if(!tree.get("sourceRefs").isArray())throw new IllegalArgumentException();for(JsonNode ref:tree.get("sourceRefs"))if(!ref.isTextual())throw new IllegalArgumentException();}
            request=mapper.readerFor(BoundaryInputRequest.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(raw);
        }catch(Exception e){throw new BusinessException("INVALID_REQUEST",400,"Invalid strict boundary request");}
            sendJson(x,202,inputs.submit(rid,mutationKey(x),request,actor));
        }else throw methodNotAllowed();return true;
    }
}
