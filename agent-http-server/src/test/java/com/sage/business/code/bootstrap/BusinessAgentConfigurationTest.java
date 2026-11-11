package com.sage.business.code.bootstrap;

import com.sage.business.code.adapter.product.ProcessExecutionSettings;
import com.sage.business.code.adapter.product.TraceExportSettings;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import static org.junit.Assert.*;

public class BusinessAgentConfigurationTest {
    @Test public void defaultsKeepProcessAndTraceDisabled() throws Exception {
        BusinessAgentServiceMain.Config config=load("auth.token=0123456789abcdef\n");
        assertFalse(config.process.isEnabled());
        assertFalse(config.trace.isEnabled());
        assertEquals("50MB", config.maxFileSize);
        assertEquals(14, config.maxHistory);
        assertFalse(config.memory.isEnabled());
    }

    @Test public void serviceConfigCarriesValidatedMemoryBinding() throws Exception {
        ServiceConfig config=ServiceConfig.load(new String[]{"--config", write(
                "auth.token=0123456789abcdef\n"
                +"memory.enabled=true\nmemory.provider=tencentdb\n"
                +"memory.single-user=true\nmemory.endpoint=http://127.0.0.1:8420\n"
                +"memory.api-key-env=PATH\nmemory.project-namespace=business-memory\n"
                +"memory.user-id=operator\n")});
        assertTrue(config.memory.isEnabled());
        assertEquals("business-memory:sample", config.memory.projectId("sample"));
    }

    @Test public void unifiedServiceConfigCarriesLoggingAndAdapterSettings() throws Exception {
        ServiceConfig config=ServiceConfig.load(new String[]{"--config", write(
                "auth.token=0123456789abcdef\n"
                +"process.enabled=true\nprocess.denied-executables=rm,notepad\ntrace.enabled=true\n"
                +"trace.otlp.endpoint=http://127.0.0.1:6006/v1/traces\n"
                +"logging.max-file-size=2MB\nlogging.max-history=3\n"
                +"logging.total-size-cap=8MB\n")});
        assertTrue(config.process.isEnabled());
        assertTrue(config.trace.isEnabled());
        assertEquals("2MB", config.maxFileSize);
        assertEquals(3, config.maxHistory);
        assertEquals("8MB", config.totalSizeCap);
    }

    @Test public void parsesExplicitProcessAndTraceConfiguration() throws Exception {
        BusinessAgentServiceMain.Config config=load(
                "auth.token=0123456789abcdef\n"
                +"process.enabled=true\nprocess.denied-executables=rm,del\n"
                +"process.allow-shell=false\nprocess.allow-network-tools=false\n"
                +"process.approval-mode=ALWAYS\ntrace.enabled=true\n"
                +"trace.otlp.endpoint=http://127.0.0.1:6006/v1/traces\n"
                +"trace.otlp.protocol=http/protobuf\ntrace.project=business-agent-code\n"
                +"trace.timeout-millis=5000\n");
        assertTrue(config.process.isEnabled());
        assertTrue(config.process.getDeniedExecutables().contains("rm"));
        assertTrue(config.trace.isEnabled());
        assertEquals("http://127.0.0.1:6006/v1/traces",config.trace.getEndpoint());
    }

    @Test public void resolvesTokenFromNamedEnvironmentVariable() throws Exception {
        Map.Entry<String,String> candidate=null;
        for(Map.Entry<String,String> entry:System.getenv().entrySet()){
            if(entry.getValue()!=null&&entry.getValue().trim().length()>=16){candidate=entry;break;}
        }
        if(candidate==null)return;
        BusinessAgentServiceMain.Config config=load("auth.token-env="+candidate.getKey()+"\n");
        assertEquals(candidate.getValue().trim(),config.token);
    }

    @Test public void rejectsInlineAndEnvironmentTokenTogether() throws Exception {
        try{load("auth.token=0123456789abcdef\nauth.token-env=PATH\n");fail("expected conflict");}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("only one"));}
    }

    @Test public void rejectsUnsafeProcessAndIncompleteTraceConfiguration(){
        Properties process=new Properties();process.setProperty("process.allow-shell","true");
        try{ProcessExecutionSettings.fromProperties(process);fail("expected shell rejection");}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("allow-shell"));}
        Properties trace=new Properties();trace.setProperty("trace.enabled","true");
        try{TraceExportSettings.fromProperties(trace);fail("expected endpoint rejection");}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("trace.otlp.endpoint"));}
        Properties invalidEndpoint=new Properties();
        invalidEndpoint.setProperty("trace.otlp.endpoint", "ftp://127.0.0.1:6006/v1/traces");
        try{TraceExportSettings.fromProperties(invalidEndpoint);fail("expected endpoint protocol rejection");}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("http(s)"));}
    }

    @Test public void rejectsLegacyProcessAllowlistConfiguration(){
        Properties legacy=new Properties();
        legacy.setProperty("process.enabled", "true");
        legacy.setProperty("process.allowed-executables", "java");
        try{ProcessExecutionSettings.fromProperties(legacy);fail("expected legacy allowlist rejection");}
        catch(IllegalArgumentException expected){
            assertTrue(expected.getMessage().contains("process.denied-executables"));
        }
    }

    private static BusinessAgentServiceMain.Config load(String authentication)throws Exception{
        return BusinessAgentServiceMain.Config.load(new String[]{"--config", write(authentication)});
    }

    private static String write(String authentication)throws Exception{
        Path config=Files.createTempFile("business-agent-service-",".properties");
        // state.dir must stay inside the git-ignored target/ tree:
        // ServiceConfig.load runs EnvironmentConfigOverlay.read against it,
        // and the old relative "state" resolved under the module working
        // directory, dirtying the tracked gui-sessions.db fixture.
        String stateDir="target/config-test-state-"+System.nanoTime();
        String body="server.host=127.0.0.1\nserver.port=8765\n"+authentication
                +"state.dir="+stateDir+"\nmodel.config=model-providers.properties\nworkspace.sample=.\n";
        Files.write(config,body.getBytes(StandardCharsets.ISO_8859_1));
        return config.toString();
    }
}
