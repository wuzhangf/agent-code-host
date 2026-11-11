package com.sage.business.code.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sage.business.code.adapter.http.StageHostApiHandler;
import com.sage.business.code.application.stagehost.*;
import com.sage.business.code.domain.BusinessException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Release-only composition root. No model catalog, Design/Code assembly or S7 product is constructed. */
public final class CoreReleaseHostMain {
    private CoreReleaseHostMain(){}
    public static void main(String[] args) throws Exception {
        System.setProperty("acs.release.mode","CORE_RELEASE_ONLY");
        String directory=System.getProperty("acs.release.core.hostRoot",System.getenv("ACS_RELEASE_CORE_HOST_ROOT"));
        String token=System.getenv("ACS_RELEASE_CORE_HOST_TOKEN");
        if(directory==null||token==null||token.trim().isEmpty())throw new IllegalArgumentException("CORE host root and central ACS_RELEASE_CORE_HOST_TOKEN required");
        Path root=Paths.get(directory);if(!root.isAbsolute())throw new IllegalArgumentException("absolute CORE host root required");
        EacExecutionGrantStageTrust trust=EacExecutionGrantStageTrust.configured();if(trust==null)throw new IllegalArgumentException("live EAC execution grant authority required");
        StageHostStore store=new StageHostStore(root);StageHostCas cas=new StageHostCas(root);Map<String,StageProductLeaf> leaves=new LinkedHashMap<>();
        for(StageLeafProvider provider:ServiceLoader.load(StageLeafProvider.class))if("RELEASE".equals(provider.stageKind())) {
            if(leaves.containsKey("RELEASE"))throw new IllegalStateException("ambiguous RELEASE provider");
            leaves.put("RELEASE",provider.create(new StageLeafProvider.Context(store,cas,root)));
        }
        if(!leaves.containsKey("RELEASE")||!leaves.get("RELEASE").capabilities().coreReleaseInputV1)throw new IllegalStateException("CORE release provider unavailable");
        StageHostService service=new StageHostService(store,cas,leaves,trust,"core-host-"+UUID.randomUUID());service.recoverAll();
        ObjectMapper json=new ObjectMapper();StageHostApiHandler handler=new StageHostApiHandler(json,token,service);
        int port=Integer.parseInt(System.getProperty("acs.release.core.hostPort","18085"));
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",port),32);ExecutorService http=Executors.newFixedThreadPool(8);
        server.setExecutor(http);server.createContext("/",exchange->{
            try {
                if(!handler.handleGroup(exchange,exchange.getRequestURI().getPath(),exchange.getRequestMethod())){exchange.sendResponseHeaders(404,-1);}
            }catch(BusinessException problem){byte[] bytes=json.writeValueAsBytes(problem(problem.getCode(),problem.getMessage()));exchange.getResponseHeaders().set("Content-Type","application/problem+json");exchange.sendResponseHeaders(problem.getHttpStatus(),bytes.length);exchange.getResponseBody().write(bytes);}
            catch(Exception unavailable){byte[] bytes=json.writeValueAsBytes(problem("HOST_UNAVAILABLE","CORE host request unavailable"));exchange.sendResponseHeaders(503,bytes.length);exchange.getResponseBody().write(bytes);}
            finally{exchange.close();}
        });
        Runtime.getRuntime().addShutdownHook(new Thread(()->{server.stop(1);http.shutdownNow();}));server.start();new CountDownLatch(1).await();
    }
    private static Map<String,Object> problem(String code,String message){Map<String,Object> result=new LinkedHashMap<>();result.put("code",code);result.put("message",message);return result;}
}
