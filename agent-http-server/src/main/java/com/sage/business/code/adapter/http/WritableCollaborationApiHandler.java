package com.sage.business.code.adapter.http;
import com.fasterxml.jackson.databind.*;
import com.sage.business.code.application.collaboration.*;
import com.sage.business.code.contract.v2.collaboration.*;
import com.sage.business.code.domain.BusinessException;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;

/** Fixed routes and strict typed bodies; filesystem authority is never client supplied. */
public final class WritableCollaborationApiHandler extends V2HttpSupport implements V2RouteGroup {
    private final WritableCollaborationService service;private final ObjectMapper strict;
    public WritableCollaborationApiHandler(ObjectMapper mapper,String token,WritableCollaborationService service){super(mapper,token);this.service=service;this.strict=mapper.copy().configure(MapperFeature.ALLOW_COERCION_OF_SCALARS,false).configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,true).configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,true);}
    public boolean handleGroup(HttpExchange x,String path,String method)throws IOException{
        if(path.matches("/api/v2/runs/[^/]+/collaboration-evidence(?:/[^/]+(?:/(?:bytes|upload-ack))?)?"))return evidence(x,path,method);
        if(!path.startsWith("/api/v2/runs/")||(!path.contains("/collaboration-groups")&&!path.endsWith("/collaboration-activity")))return false;
        requireAccess(x);String actor=(String)x.getAttribute(V2ApiDispatcher.CLIENT_SESSION_ID_ATTRIBUTE);if(actor==null)throw new BusinessException("CLIENT_REGISTRATION_REQUIRED",428,"Registered client identity is required");String[] p=path.split("/");String rid=p[4];safeEntityId(rid,"run");
        if("GET".equals(method)){
            if(p.length==6&&"collaboration-activity".equals(p[5]))sendJson(x,200,service.activity(rid));
            else if(p.length>=6&&"collaboration-groups".equals(p[5])){
                if(p.length==6)sendJson(x,200,CollaborationStore.map("items",service.list(rid)));
                else if(p.length==7)sendJson(x,200,service.get(rid,p[6]));
                else if(p.length==8&&"executions".equals(p[7]))sendJson(x,200,CollaborationStore.map("items",service.executions(rid,p[6])));
                else if(p.length==9&&"executions".equals(p[7]))sendJson(x,200,service.execution(rid,p[6],p[8]));
                else if(p.length==9&&"candidates".equals(p[7]))sendJson(x,200,service.candidate(rid,p[6],p[8]));
                else if(p.length==10&&"candidates".equals(p[7])&&"files".equals(p[9]))sendJson(x,200,service.candidateFiles(rid,p[6],p[8],queryParam(x,"path"),queryParam(x,"offset"),queryParam(x,"limit")));
                else if(p.length==10&&"previews".equals(p[7])&&"files".equals(p[9]))sendJson(x,200,service.previewFiles(rid,p[6],p[8],queryParam(x,"side"),queryParam(x,"unitId"),queryParam(x,"path"),queryParam(x,"offset"),queryParam(x,"limit")));
                else if(p.length==8&&"adoptions".equals(p[7]))sendJson(x,200,CollaborationStore.map("items",service.adoptions(rid,p[6])));
                else if(p.length==9&&"reviews".equals(p[7]))sendJson(x,200,service.reviewResult(rid,p[6],p[8]));
                else if(p.length==9&&"adoptions".equals(p[7]))sendJson(x,200,service.adoption(rid,p[6],p[8]));else throw methodNotAllowed();
            }else throw methodNotAllowed();return true;
        }
        if(!"POST".equals(method)||p.length<6||!"collaboration-groups".equals(p[5]))throw methodNotAllowed();String key=mutationKey(x);Object result;
        if(p.length==6)result=service.create(rid,key,body(x,WriteGroupCreateRequest.class),actor);
        else if(p.length==8){safeEntityId(p[6],"collaboration group");switch(p[7]){
            case "authorizations":result=service.authorize(rid,p[6],key,body(x,WriteGroupAuthorizeRequest.class),actor);break;
            case "start":result=service.start(rid,p[6],key,body(x,WriteGroupStartRequest.class));break;
            case "cancel":result=service.cancel(rid,p[6],key,body(x,WriteActionRequest.class));break;
            case "selections":result=service.select(rid,p[6],key,body(x,WriteSelectionRequest.class));break;
            case "adoptions":result=service.adopt(rid,p[6],key,body(x,WriteAdoptionRequest.class),actor);break;
            default:throw methodNotAllowed();
        }}else if(p.length==10){switch(p[7]+"/"+p[9]){
            case "selections/previews":{WritePreviewRequest request=body(x,WritePreviewRequest.class);if(request.selectionId!=null&&!p[8].equals(request.selectionId))throw new BusinessException("INVALID_REQUEST",400,"Selection identity differs from route");request.selectionId=p[8];result=service.preview(rid,p[6],key,request);break;}
            case "previews/reviews":result=service.review(rid,p[6],p[8],key,body(x,WriteReviewRequest.class),actor);break;
            case "previews/revisions":result=service.revisePreview(rid,p[6],p[8],key,body(x,WritePreviewRevisionRequest.class));break;
            case "executions/rework":result=service.rework(rid,p[6],p[8],key,body(x,WriteActionRequest.class));break;
            case "executions/cancel":result=service.cancelExecution(rid,p[6],p[8],key,body(x,WriteActionRequest.class));break;
            case "adoptions/cancel":result=service.cancelAdoption(rid,p[6],p[8],key,body(x,WriteActionRequest.class));break;
            case "repairs/recover":result=service.recoverRepair(rid,p[6],p[8],key,body(x,WriteActionRequest.class));break;
            case "adoptions/repairs":result=service.repair(rid,p[6],p[8],key,body(x,WriteRepairRequest.class),actor);break;
            case "adoptions/recover":result=service.recoverAdoption(rid,p[6],p[8],key,body(x,WriteActionRequest.class));break;
            default:throw methodNotAllowed();
        }}else throw methodNotAllowed();sendJson(x,202,result);return true;
    }
    private boolean evidence(HttpExchange x,String path,String method)throws IOException{
        requireAccess(x);if(x.getAttribute(V2ApiDispatcher.CLIENT_SESSION_ID_ATTRIBUTE)==null)throw new BusinessException("CLIENT_REGISTRATION_REQUIRED",428,"Registered client identity required");String[] p=path.split("/");String rid=p[4];safeEntityId(rid,"run");
        if("GET".equals(method)&&p.length==7){sendJson(x,200,service.evidence(rid,p[6]));return true;}
        if(!"WORKER".equals(x.getAttribute(V2ApiDispatcher.CLIENT_KIND_ATTRIBUTE)))throw new BusinessException("ACCESS_DENIED",403,"Only the registered Worker can export or acknowledge final attempt evidence");
        if("GET".equals(method)&&p.length==8&&"bytes".equals(p[7])){sendJson(x,200,service.evidenceBytes(rid,p[6],queryParam(x,"offset")));return true;}
        if(!"POST".equals(method))throw methodNotAllowed();String key=mutationKey(x);
        if(p.length==6){CollaborationEvidenceExportRequest request=body(x,CollaborationEvidenceExportRequest.class);if(!"collaboration-evidence-export-request/1".equals(request.schemaVersion)||!key.equals(request.exportId))throw new BusinessException("INVALID_REQUEST",400,"Stable exact export identity required");sendJson(x,200,service.exportEvidence(rid,request.exportId));return true;}
        if(p.length==8&&"upload-ack".equals(p[7])){CollaborationEvidenceUploadAck request=body(x,CollaborationEvidenceUploadAck.class);if(!"collaboration-evidence-upload-ack/1".equals(request.schemaVersion)||!p[6].equals(request.exportId)||!key.equals("ack-"+request.exportId))throw new BusinessException("INVALID_REQUEST",400,"Exact original evidence acknowledgement required");sendJson(x,200,service.acknowledgeEvidence(rid,request.exportId,request.digest,request.manifestDigest,request.sizeBytes,request.artifactId,request.receiptRef));return true;}
        throw methodNotAllowed();
    }
    @Override protected <T>T body(HttpExchange x,Class<T> type)throws IOException{try{byte[] raw=readLimited(x.getRequestBody());JsonNode tree=strict.readTree(raw);if(tree==null||!tree.isObject())throw new IllegalArgumentException();return strict.readValue(raw,type);}catch(Exception invalid){throw new BusinessException("INVALID_REQUEST",400,"Invalid strict writable collaboration request");}}
}
