package com.sage.business.code.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sage.business.code.application.personalaccess.*;
import com.sage.business.code.application.client.ClientSessionRegistry;
import com.sage.business.code.adapter.personalaccess.PersonalAccessServer;
import com.sage.business.code.adapter.http.ClientSessionApiHandler;
import com.sage.business.code.infrastructure.controlplane.ControlPlaneDatabase;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Installed Mac PAC companion. Reuses the production authorization and store;
 * no fixture routes, arbitrary upstreams, or second task authority. */
public final class MacPersonalAccessMain {
    public static void main(String[] args) throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("configuration and state directory required");
        Properties p=new Properties();try(java.io.Reader input=Files.newBufferedReader(Paths.get(args[0]))){p.load(input);}
        PersonalAccessSettings settings=PersonalAccessSettings.from(p);
        if(!settings.enabled)throw new IllegalArgumentException("personal access disabled");
        URI publicOrigin=URI.create(settings.origin);
        if(publicOrigin.getHost()==null||InetAddress.getByName(publicOrigin.getHost()).isLoopbackAddress()||publicOrigin.getPort()!=settings.port)throw new IllegalArgumentException("Reachable LAN/VPN HTTPS origin and matching explicit port required");
        InetAddress bind=InetAddress.getByName(settings.bind);
        if(bind.isAnyLocalAddress()||bind.isLoopbackAddress()||NetworkInterface.getByInetAddress(bind)==null||!NetworkInterface.getByInetAddress(bind).isUp())throw new IllegalArgumentException("Specific local LAN/VPN interface required");
        String ownerToken=required("AGENT_MAC_ACCESS_OWNER_TOKEN"),workerToken=required("WORKER_LOCAL_TOKEN");
        if(ownerToken.length()<32)throw new IllegalArgumentException("owner credential too short");
        int ownerPort=Integer.parseInt(p.getProperty("owner.port"));
        if(ownerPort<1024||ownerPort>65535||ownerPort==settings.port)throw new IllegalArgumentException("owner port invalid");
        Path state=Paths.get(args[1]).toAbsolutePath();Files.createDirectories(state);
        // The process lock prevents overlapping upgrades from writing one PAC database.
        java.nio.channels.FileChannel lockFile=java.nio.channels.FileChannel.open(state.resolve("process.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        java.nio.channels.FileLock lock=lockFile.tryLock();if(lock==null)throw new IllegalStateException("PAC_ALREADY_RUNNING");
        ObjectMapper json=ClientAccessWire.JSON;
        String expected=p.getProperty("worker.storeId");PersonalAccessService.id(expected);
        PersonalAccessSettings.Material material=settings.material();
        boolean certificateMatches=false;
        Collection<List<?>> names=material.certificate.getSubjectAlternativeNames();
        if(names!=null)for(List<?> name:names)if((Integer.valueOf(2).equals(name.get(0))||Integer.valueOf(7).equals(name.get(0)))&&publicOrigin.getHost().equalsIgnoreCase(String.valueOf(name.get(1))))certificateMatches=true;
        if(!certificateMatches)throw new IllegalArgumentException("TLS certificate must name exact public origin");
        ControlPlaneDatabase database=new ControlPlaneDatabase(state.resolve("database"),false);
        PersonalAccessStore records=new PersonalAccessStore(database,json);
        // Stable ACS identity derives from original Host identity, never from package version.
        String storeId="mac-pac-"+PersonalAccessService.hash(expected).substring(0,32);
        PersonalAccessService auth=new PersonalAccessService(records,json,material.sealKey,storeId,settings.origin);
        Arrays.fill(material.sealKey,(byte)0);
        ClientSessionRegistry registry=new ClientSessionRegistry(storeId,UUID.randomUUID().toString());
        HttpServer owner=HttpServer.create(new InetSocketAddress("127.0.0.1",ownerPort),8);
        ExecutorService executor=Executors.newFixedThreadPool(2);owner.setExecutor(executor);
        PersonalLocalTaskAccess nativeAccess=new PersonalLocalTaskAccess(p.getProperty("worker.origin"),workerToken,records,auth,json);
        if(!expected.equals(nativeAccess.ownerCatalog().path("backendStoreId").asText()))throw new IllegalStateException("HOST_IDENTITY_CHANGED");
        PersonalAccessServer pac=new PersonalAccessServer(new InetSocketAddress(bind,settings.port),material.tls,material.certificate,auth,registry,json,URI.create("http://127.0.0.1:"+ownerPort),ownerToken).localTasks(nativeAccess)
                .workHandoffs(state.resolve("handoff-materials"),state.resolve("handoff-signing"));
        // Optional owner-managed ntfy channel; an invalid or unavailable notice configuration must not stop the personal endpoint.
        try{
            PersonalNoticeSettings noticeSettings=PersonalNoticeSettings.from(p);
            if(noticeSettings.enabled)pac.notices(new PersonalNoticeService(noticeSettings,new PersonalNoticeRepository(records),new NtfyNoticeAdapter(noticeSettings)));
        }catch(IllegalArgumentException invalid){System.err.println("personal.notice unavailable; notifications stay disabled");}
        ClientSessionApiHandler clients=new ClientSessionApiHandler(json,ownerToken,registry);
        owner.createContext("/api/v2/client-sessions",exchange->{try{if(!clients.handleGroup(exchange,exchange.getRequestURI().getPath(),exchange.getRequestMethod()))exchange.sendResponseHeaders(404,-1);}catch(com.sage.business.code.domain.BusinessException failure){byte[] body=json.writeValueAsBytes(Collections.singletonMap("code",failure.getCode()));exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(failure.getHttpStatus(),body.length);exchange.getResponseBody().write(body);}finally{exchange.close();}});
        owner.createContext("/api/v2/personal-access",pac.ownerHandler());
        Runtime.getRuntime().addShutdownHook(new Thread(()->{pac.close();owner.stop(0);executor.shutdownNow();database.close();try{lock.release();lockFile.close();}catch(Exception ignored){}}));
        owner.start();pac.start();
        // Public readiness contains no bearer, device token, invite or TLS private material.
        byte[] ready=json.writeValueAsBytes(Collections.singletonMap("backendStoreId",expected));
        Path temporary=state.resolve("ready.tmp");Files.write(temporary,ready);Files.move(temporary,state.resolve("ready.json"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        new CountDownLatch(1).await();
    }
    private static String required(String name){String value=System.getenv(name);if(value==null||value.isEmpty())throw new IllegalArgumentException("Approved credential provider unavailable: "+name);return value;}
}
