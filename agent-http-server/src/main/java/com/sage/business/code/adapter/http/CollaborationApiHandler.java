package com.sage.business.code.adapter.http;

import com.fasterxml.jackson.databind.*;
import com.sage.business.code.application.collaboration.*;
import com.sage.business.code.contract.v2.session.*;
import com.sage.business.code.domain.BusinessException;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.*;

/** Authentication and strict typed admission precede any scoped lookup. */
public final class CollaborationApiHandler extends V2HttpSupport implements V2RouteGroup {
    private final ContextPackageService packages;private final AuxiliarySessionService auxiliary;
    public CollaborationApiHandler(ObjectMapper mapper,String token,ContextPackageService packages,AuxiliarySessionService auxiliary){super(mapper,token);this.packages=packages;this.auxiliary=auxiliary;}
    public boolean handleGroup(HttpExchange x,String path,String method)throws IOException {
        if(!path.matches("/api/v2/sessions/[^/]+/(context-packages|auxiliary-sessions|auxiliary-turns)(?:/[^/]+(?:/(items|cancel))?)?"))return false;
        requireAccess(x);String actor=(String)x.getAttribute(V2ApiDispatcher.CLIENT_SESSION_ID_ATTRIBUTE);
        if(actor==null)throw new BusinessException("CLIENT_REGISTRATION_REQUIRED",428,"Registered client identity is required");
        String[] p=path.split("/");String sid=p[4];safeEntityId(sid,"session");String resource=p[5];
        if("context-packages".equals(resource)){
            if(p.length==6&&"POST".equals(method)){sendJson(x,201,packages.create(sid,mutationKey(x),strict(x,ContextPackageRequest.class)));return true;}
            if(!"GET".equals(method)||p.length<7)throw methodNotAllowed();safeEntityId(p[6],"package");
            if(p.length==7)sendJson(x,200,packages.require(sid,p[6]));
            else if("items".equals(p[7])){
                List<ObservationSnapshotItem> items=packages.items(sid,p[6]);long limit=longParam(x,"limit",30);if(limit<1||limit>100)throw new BusinessException("INVALID_REQUEST",400,"limit must be 1..100");
                String cursor=queryParam(x,"cursor");int offset=0;
                if(cursor!=null)try{if(cursor.length()>512)throw new IllegalArgumentException();String decoded=new String(Base64.getUrlDecoder().decode(cursor),java.nio.charset.StandardCharsets.UTF_8);String prefix=sid+"\n"+p[6]+"\n";
                    if(!decoded.startsWith(prefix)||cursor.length()>512)throw new IllegalArgumentException();offset=Integer.parseInt(decoded.substring(prefix.length()));
                    if(offset<0||offset>=items.size())throw new IllegalArgumentException();
                }catch(RuntimeException invalid){throw new BusinessException("INVALID_REQUEST",400,"Cursor does not belong to this frozen package");}
                int end=Math.min(items.size(),offset+(int)limit);sendJson(x,200,CollaborationStore.map("packageId",p[6],"items",items.subList(offset,end),"nextCursor",
                        end<items.size()?Base64.getUrlEncoder().withoutPadding().encodeToString((sid+"\n"+p[6]+"\n"+end).getBytes(java.nio.charset.StandardCharsets.UTF_8)):null));
            }else throw methodNotAllowed();return true;
        }
        if("auxiliary-sessions".equals(resource)&&p.length==6){
            if("POST".equals(method))sendJson(x,201,auxiliary.create(sid,mutationKey(x),strict(x,AuxiliarySessionRequest.class),actor));
            else if("GET".equals(method))sendJson(x,200,CollaborationStore.map("items",auxiliary.list(sid)));else throw methodNotAllowed();return true;
        }
        if("auxiliary-turns".equals(resource)){
            if(p.length==6&&"GET".equals(method)){sendJson(x,200,CollaborationStore.map("items",auxiliary.turns(sid)));return true;}
            if(p.length==6&&"POST".equals(method))sendJson(x,202,auxiliary.turn(sid,mutationKey(x),strict(x,AuxiliaryTurnRequest.class),actor));
            else if(p.length==7&&"GET".equals(method))sendJson(x,200,auxiliary.get(sid,p[6]));
            else if(p.length==8&&"cancel".equals(p[7])&&"POST".equals(method))sendJson(x,200,auxiliary.cancel(sid,p[6],mutationKey(x)));
            else throw methodNotAllowed();return true;
        }
        throw methodNotAllowed();
    }
    private <T>T strict(HttpExchange x,Class<T> type)throws IOException {
        try{byte[] raw=readLimited(x.getRequestBody());JsonNode tree=mapper.readTree(raw);if(tree==null||!tree.isObject())throw new IllegalArgumentException();
            Iterator<Map.Entry<String,JsonNode>> fields=tree.fields();while(fields.hasNext()){Map.Entry<String,JsonNode> e=fields.next();
                if("textOnlyResearch".equals(e.getKey())&&!e.getValue().isBoolean())throw new IllegalArgumentException();
                if(!"researchTasks".equals(e.getKey())&&!"textOnlyResearch".equals(e.getKey())&&!e.getValue().isNull()&&!e.getValue().isTextual())throw new IllegalArgumentException();}
            if(tree.has("researchTasks")){if(!tree.get("researchTasks").isArray())throw new IllegalArgumentException();for(JsonNode task:tree.get("researchTasks")){
                if(!task.isObject())throw new IllegalArgumentException();Iterator<JsonNode> values=task.elements();while(values.hasNext())if(!values.next().isTextual())throw new IllegalArgumentException();}}
            return mapper.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(raw);
        }catch(Exception invalid){throw new BusinessException("INVALID_REQUEST",400,"Invalid strict collaboration request");}
    }
}
