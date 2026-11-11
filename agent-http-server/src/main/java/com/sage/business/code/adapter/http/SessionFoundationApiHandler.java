package com.sage.business.code.adapter.http;

import com.fasterxml.jackson.databind.*;
import com.sage.business.code.application.session.ObservationSnapshotService;
import com.sage.business.code.contract.v2.session.ObservationSnapshotRequest;
import com.sage.business.code.domain.BusinessException;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.Map;

/** Shared-owner inspection; registration and bearer are checked before object lookup. */
public final class SessionFoundationApiHandler extends V2HttpSupport implements V2RouteGroup {
    private final ObservationSnapshotService snapshots;
    private final boolean enabled;
    public SessionFoundationApiHandler(ObjectMapper mapper,String token,ObservationSnapshotService snapshots,boolean enabled){
        super(mapper,token);this.snapshots=snapshots;this.enabled=enabled;
    }
    public boolean handleGroup(HttpExchange exchange,String path,String method)throws IOException {
        if(!path.matches("/api/v2/sessions/[^/]+/(foundation|observation-snapshots(?:/[^/]+(?:/items)?)?)"))return false;
        requireAccess(exchange);
        if(exchange.getAttribute(V2ApiDispatcher.CLIENT_SESSION_ID_ATTRIBUTE)==null)
            throw new BusinessException("CLIENT_REGISTRATION_REQUIRED",428,"Registered client identity is required");
        String[] parts=path.split("/");String sid=parts[4];safeEntityId(sid,"session");
        if("foundation".equals(parts[5])){
            if(!"GET".equals(method))throw methodNotAllowed();
            Map<String,Object> view=snapshots.foundation(sid);
            if(!enabled){@SuppressWarnings("unchecked") Map<String,Object> caps=(Map<String,Object>)view.get("capabilities");
                caps.put("sharedObserveV1","DISABLED");caps.put("commandReceiptV1","DISABLED");caps.put("observationSnapshotV1","DISABLED");}
            sendJson(exchange,200,view);return true;
        }
        if(parts.length==6){
            if(!"POST".equals(method))throw methodNotAllowed();
            if(!enabled)throw new BusinessException("CAPABILITY_UNAVAILABLE",409,"Observation snapshot admission is disabled");
            String key=mutationKey(exchange);ObservationSnapshotRequest request;
            try{
                byte[] raw=readLimited(exchange.getRequestBody());JsonNode tree=mapper.readTree(raw);
                if(tree==null||!tree.isObject())throw new IllegalArgumentException();
                for(String field:new String[]{"schemaVersion","profile"}){JsonNode v=tree.get(field);if(v!=null&&!v.isTextual())throw new IllegalArgumentException();}
                for(String field:new String[]{"messageLimit","activityLimit"}){JsonNode v=tree.get(field);if(v!=null&&(!v.isIntegralNumber()||!v.canConvertToInt()))throw new IllegalArgumentException();}
                JsonNode partial=tree.get("allowPartial");if(partial!=null&&!partial.isBoolean())throw new IllegalArgumentException();
                request=mapper.readerFor(ObservationSnapshotRequest.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(raw);
            }catch(Exception invalid){throw new BusinessException("INVALID_REQUEST",400,"Invalid bounded snapshot request");}
            sendJson(exchange,201,snapshots.create(sid,key,request));return true;
        }
        if(!"GET".equals(method))throw methodNotAllowed();
        if(parts.length==7){sendJson(exchange,200,snapshots.manifest(sid,parts[6]));return true;}
        long limit=longParam(exchange,"limit",30L);
        if(limit<1||limit>100)throw new BusinessException("INVALID_REQUEST",400,"limit must be 1..100");
        sendJson(exchange,200,snapshots.items(sid,parts[6],queryParam(exchange,"cursor"),(int)limit));return true;
    }
}
