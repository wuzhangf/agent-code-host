package com.sage.business.code.bootstrap;
import org.junit.*; import java.nio.file.*; import java.util.*;
public class BusinessAgentServiceMainTest {
 @Test public void configRequiresLoopbackAndWorkspace() throws Exception {Path f=Files.createTempFile("business-agent-config",".properties");Files.write(f,Arrays.asList("server.host=0.0.0.0","server.port=8765","auth.token=1234567890123456","state.dir=target/config-test-state-"+System.nanoTime(),"model.config=model-providers.properties","workspace.sample=work"));BusinessAgentServiceMain.Config c=BusinessAgentServiceMain.Config.load(new String[]{"--config",f.toString()});Assert.assertEquals("0.0.0.0",c.host);Assert.assertEquals("work",c.workspaces.get("sample"));Files.deleteIfExists(f);}
 @Test(expected=IllegalArgumentException.class) public void configRejectsMissingRequiredValues() throws Exception {Path f=Files.createTempFile("business-agent-empty",".properties");try{BusinessAgentServiceMain.Config.load(new String[]{"--config",f.toString()});}finally{Files.deleteIfExists(f);}}
}
