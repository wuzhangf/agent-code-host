package com.sage.business.code.adapter.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sage.business.code.application.stagehost.ReleaseSourceExportService;
import com.sage.business.code.domain.BusinessException;
import com.sage.business.code.producthost.source.CodeSourceExportOperations;
import com.sage.business.code.producthost.source.SourceException;
import com.sun.net.httpserver.HttpExchange;
import java.io.*;
import java.nio.file.Files;
import java.util.Map;

/** Narrow authenticated export create/query/object routes, independent from immutable parent stage outputs. */
public final class ReleaseSourceExportApiHandler extends V2HttpSupport implements V2RouteGroup {
    private static final String PREFIX="/api/v2/release-source-exports";
    private final ReleaseSourceExportService service;
    public ReleaseSourceExportApiHandler(ObjectMapper mapper,String token,ReleaseSourceExportService service){super(mapper,token);this.service=service;}
    @Override public boolean handleGroup(HttpExchange exchange,String path,String method)throws IOException {
        if(!path.equals(PREFIX)&&!path.startsWith(PREFIX+"/"))return false;requireAccess(exchange);
        try {
            String rest=path.substring(PREFIX.length());
            if(rest.isEmpty()){
                if(!"POST".equals(method))throw methodNotAllowed();String key=mutationKey(exchange);byte[] raw=exchange.getRequestBody().readNBytes(1024*1024+1);if(raw.length==0||raw.length>1024*1024)throw new BusinessException("INVALID_REQUEST",413,"source export metadata exceeds 1 MiB");Map<String,Object> input=mapper.readValue(raw,Map.class);
                if(!key.equals(input.get("requestKey")))throw new BusinessException("INVALID_REQUEST",409,"body request key differs from Idempotency-Key");sendJson(exchange,200,service.create(input));return true;
            }
            if(!"GET".equals(method))throw methodNotAllowed();String[] parts=rest.substring(1).split("/",-1);
            if(parts.length==2&&"requests".equals(parts[0])){sendJson(exchange,200,service.queryRequest(parts[1]));return true;}
            if(parts.length==1){sendJson(exchange,200,service.queryExport(parts[0]));return true;}
            if(parts.length==3&&"objects".equals(parts[1])){
                CodeSourceExportOperations.ObjectBody object=service.object(parts[0],parts[2]);exchange.getResponseHeaders().set("Content-Type","application/octet-stream");exchange.getResponseHeaders().set("X-Content-SHA256",object.digest);
                exchange.sendResponseHeaders(200,object.size);try(InputStream in=Files.newInputStream(object.path);OutputStream out=exchange.getResponseBody()){in.transferTo(out);}return true;
            }throw new BusinessException("NOT_FOUND",404,"source export route not found");
        }catch(BusinessException bad){throw bad;}
        catch(SourceException bad){throw new BusinessException(bad.code(),"EXPORT_NOT_FOUND".equals(bad.code())?404:"EXPORT_BUSY".equals(bad.code())?409:422,bad.getMessage());}
        catch(com.agent.product.code.implementation.source.SourceOperationException bad){throw new BusinessException(bad.getCode(),"OWNER_SCOPE_DENIED".equals(bad.getCode())?403:422,bad.getMessage());}
        catch(Exception bad){throw new BusinessException("SOURCE_EXPORT_UNAVAILABLE",503,"source export is unavailable");}
    }
}
