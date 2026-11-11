package com.sage.business.code.adapter.gui;
import com.fasterxml.jackson.databind.*;
import com.sage.business.code.adapter.http.BusinessHttpServer;
import com.sage.business.code.application.*;
import com.sage.business.code.application.gui.*;
import com.sage.business.code.bootstrap.ServiceJsonMapper;
import com.sage.business.code.domain.*;
import com.sage.business.code.infrastructure.*;
import com.sage.business.code.infrastructure.gui.GuiSessionStore;
import org.junit.*;
import java.io.*; import java.net.*; import java.nio.file.*; import java.util.*;
/** §14.5 GUI HTTP boundary: auth, CRUD, messages, busy, archive, static files, traversal. */
public class GuiHttpTest {
 private Path root,workspace,state; private ObjectMapper mapper; private TaskApplicationService app; private GuiSessionStore store; private GuiTaskOrchestrator gui; private BusinessHttpServer server; private int port; private static final String TOKEN="1234567890123456";
 @Before public void setUp() throws Exception{
  Path base=Paths.get("target","gui-http-test").toAbsolutePath();Files.createDirectories(base);root=Files.createTempDirectory(base,"case-");workspace=root.resolve("workspace");Files.createDirectories(workspace.resolve("src/main/java"));Files.write(workspace.resolve("README.md"),Collections.singletonList("fixture"));
  git("init");git("config","user.email","test@example.invalid");git("config","user.name","test");git("add",".");git("commit","-m","initial");
  state=root.resolve("state");mapper=ServiceJsonMapper.mapper();
  Map<String,String> aliases=Collections.singletonMap("sample",workspace.toString());
  WorkspaceRegistry registry=new WorkspaceRegistry(aliases);
  BusinessCodeExecutor fake=new BusinessCodeExecutor(){public BusinessExecutionResult execute(ApprovedBusinessTask t,BusinessRunContext c){return new BusinessExecutionResult(RunStatus.SUCCEEDED,"product-"+c.getRunId(),"ok",Collections.<String>emptyList(),Collections.<String>emptyList(),null,null,null,null);}};
  app=new TaskApplicationService(new AtomicJsonStore(state,mapper),new TaskCanonicalizer(registry,mapper),registry,fake,mapper,root.resolve("locks"));
  store=new GuiSessionStore(state,mapper);gui=new GuiTaskOrchestrator(store,app,registry,"sample",Collections.singletonList("src/main/java"));app.addRunStateListener(gui);
  GuiHttpAdapter adapter=new GuiHttpAdapter(gui,new GuiHttpAdapter.GuiRuntimeConfig(Collections.singletonList("sample"),"test-model",true),mapper);
  server=new BusinessHttpServer(new InetSocketAddress("127.0.0.1",0),TOKEN,app,mapper,adapter);server.start();port=serverPort(server);
 }
 @After public void tearDown(){if(server!=null)server.close();if(app!=null)app.close();if(store!=null)store.close();delete(root);}
 @Test public void guiConfigRequiresBearerToken() throws Exception{
  Assert.assertEquals(401,statusNoAuth("GET","/api/v1/gui/config",null));
  @SuppressWarnings("unchecked") Map<String,Object> config=(Map<String,Object>)get("/api/v1/gui/config");
  Assert.assertEquals("0.2.0",config.get("serviceVersion"));
  Assert.assertEquals("v1",config.get("apiVersion"));
  Assert.assertEquals("1.3.0",config.get("productVersion"));
  Assert.assertEquals("test-model",config.get("modelName"));
  Assert.assertEquals(Boolean.TRUE,config.get("modelConfigured"));
  Assert.assertEquals(Collections.singletonList("sample"),config.get("workspaces"));
  // SW1 capability projection: booleans/enums only, defaults until SW5/SW6/SW8 ship.
  @SuppressWarnings("unchecked") Map<String,Object> features=(Map<String,Object>)config.get("features");
  Assert.assertEquals(Boolean.TRUE,features.get("memoryStatus"));
  Assert.assertEquals(Boolean.FALSE,features.get("workspaceDiff"));
  Assert.assertEquals(Boolean.FALSE,features.get("interrupt"));
  Assert.assertEquals(Boolean.FALSE,features.get("sse"));
  @SuppressWarnings("unchecked") Map<String,Object> memory=(Map<String,Object>)config.get("memory");
  Assert.assertEquals(Boolean.FALSE,memory.get("enabled"));
  Assert.assertEquals("none",memory.get("provider"));
  @SuppressWarnings("unchecked") Map<String,Object> trace=(Map<String,Object>)config.get("trace");
  Assert.assertNotNull(trace);
 }
 @Test public void guiConfigProjectionCarriesNoSecretsOrPaths() throws Exception{
  @SuppressWarnings("unchecked") Map<String,Object> config=(Map<String,Object>)get("/api/v1/gui/config");
  String json=mapper.writeValueAsString(config);
  String lower=json.toLowerCase();
  for(String forbidden:new String[]{"apikey","api_key","token","secret","password","endpoint","credential"})Assert.assertTrue("config leaked "+forbidden,!lower.contains(forbidden));
  // Workspace aliases only: absolute paths must never appear in the projection.
  Assert.assertTrue("config must not contain absolute paths",!json.contains("\\")&&!json.contains("/"));
 }
 @Test public void sessionLifecycleOverHttp() throws Exception{
  String body="{\"title\":\"API 测试\",\"workspaceAlias\":\"sample\",\"mode\":\"MODIFY\",\"writeAllow\":[\"src/main/java\"],\"writeForbid\":[],\"verificationLevel\":\"VERIFY\",\"maxModelTurns\":10,\"maxEditAttempts\":3}";
  Map<String,Object> created=call("POST","/api/v1/gui/sessions",body,"key-s1");
  Assert.assertEquals(201,lastCode);
  @SuppressWarnings("unchecked") List<Map<String,Object>> list=(List<Map<String,Object>>)(List<?>)getList("/api/v1/gui/sessions");
  Assert.assertEquals(1,list.size());
  String sessionId=(String)list.get(0).get("id");
  // PATCH rename
  Map<String,Object> patched=call("PATCH","/api/v1/gui/sessions/"+sessionId,"{\"title\":\"renamed\"}",null);
  Assert.assertEquals("renamed",patched.get("title"));
  Assert.assertEquals("sample",patched.get("workspaceAlias"));
  // DELETE archives
  Map<String,Object> archived=call("DELETE","/api/v1/gui/sessions/"+sessionId,null,null);
  Assert.assertEquals("ARCHIVED",archived.get("status"));
  Assert.assertEquals(404,status("GET","/api/v1/gui/sessions/missing",null,null));
 }
 @Test public void emptyBodyCreatesZeroConfigChat() throws Exception{
  // ChatGPT-style: the client sends an empty body; workspace/mode/scope/limits come from server defaults.
  HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+port+"/api/v1/gui/sessions").openConnection();
  c.setRequestMethod("POST");c.setRequestProperty("Authorization","Bearer "+TOKEN);c.setRequestProperty("Idempotency-Key","empty-chat-1");c.setDoOutput(true);
  OutputStream o=c.getOutputStream();o.write(new byte[0]);o.close();
  int code=c.getResponseCode();
  String response=read(c.getInputStream());c.disconnect();
  Assert.assertEquals(201,code);
  Map<String,Object> chat=mapper.readValue(response,Map.class);
  Assert.assertEquals("sample",chat.get("workspaceAlias"));
  Assert.assertEquals("MODIFY",chat.get("mode"));
  Assert.assertEquals("VERIFY",chat.get("verificationLevel"));
  Assert.assertEquals(Collections.singletonList("src/main/java"),chat.get("writeAllow"));
  Map<String,Object> replay=call("POST","/api/v1/gui/sessions","","empty-chat-1");
  Assert.assertEquals("replayed idempotent session must keep its identity",chat.get("id"),replay.get("id"));
 }
 @Test public void firstMessageAutoTitlesTheChat() throws Exception{
  // Start from an untitled zero-config chat; the first command names it.
  Map<String,Object> chat=call("POST","/api/v1/gui/sessions","","key-untitled");
  String sessionId=(String)chat.get("id");
  Assert.assertEquals("",chat.get("title"));
  Map<String,Object> sent=call("POST","/api/v1/gui/sessions/"+sessionId+"/messages","{\"clientMessageId\":\"cm-title\",\"content\":\"梳理当前项目的模块结构并给出说明\"}","key-title");
  awaitRun((String)sent.get("runId"));
  @SuppressWarnings("unchecked") Map<String,Object> view=(Map<String,Object>)get("/api/v1/gui/sessions/"+sessionId);
  @SuppressWarnings("unchecked") Map<String,Object> session=(Map<String,Object>)view.get("session");
  Assert.assertEquals("梳理当前项目的模块结构并给出说明",session.get("title"));
 }
 @Test public void messageFlowAndArchivedRejection() throws Exception{
  String sessionId=createSession();
  Map<String,Object> sent=call("POST","/api/v1/gui/sessions/"+sessionId+"/messages","{\"clientMessageId\":\"cm-1\",\"content\":\"inspect the fixture\"}","key-m1");
  Assert.assertNotNull(sent.get("runId"));
  awaitRun((String)sent.get("runId"));
  @SuppressWarnings("unchecked") Map<String,Object> view=(Map<String,Object>)get("/api/v1/gui/sessions/"+sessionId);
  @SuppressWarnings("unchecked") List<Map<String,Object>> messages=(List<Map<String,Object>>)view.get("messages");
  Assert.assertEquals(2,messages.size());
  Assert.assertEquals("ASSISTANT",messages.get(1).get("role"));
  // Reused clientMessageId with different content -> 409
  Assert.assertEquals(409,status("POST","/api/v1/gui/sessions/"+sessionId+"/messages","{\"clientMessageId\":\"cm-1\",\"content\":\"different\"}","key-m1b"));
  call("DELETE","/api/v1/gui/sessions/"+sessionId,null,null);
  Assert.assertEquals(409,status("POST","/api/v1/gui/sessions/"+sessionId+"/messages","{\"clientMessageId\":\"cm-2\",\"content\":\"after archive\"}","key-m2"));
 }
 @Test public void runEndpointsAndErrors() throws Exception{
  String sessionId=createSession();
  Map<String,Object> sent=call("POST","/api/v1/gui/sessions/"+sessionId+"/messages","{\"clientMessageId\":\"cm-1\",\"content\":\"inspect\"}","key-r1");
  String runId=(String)sent.get("runId");awaitRun(runId);
  Map<String,Object> run=(Map<String,Object>)get("/api/v1/gui/runs/"+runId);
  Assert.assertEquals("SUCCEEDED",run.get("status"));
  Assert.assertEquals(sessionId,run.get("sessionId"));
  // SW1: monotonic state version and update timestamp travel with every snapshot.
  Object stateVersion=run.get("stateVersion");
  Assert.assertTrue("stateVersion must be present",stateVersion instanceof Number);
  Assert.assertTrue("a queued->running->succeeded run persists at least three transitions",((Number)stateVersion).longValue()>=3L);
  Assert.assertNotNull(run.get("updatedAt"));
  Assert.assertEquals(404,status("GET","/api/v1/gui/runs/"+runId+"/command-approval",null,null));
  Assert.assertEquals(409,status("POST","/api/v1/gui/runs/"+runId+"/cancel",null,"cancel-key"));
  Assert.assertEquals(404,status("GET","/api/v1/gui/runs/missing-run",null,null));
  Assert.assertEquals(400,status("POST","/api/v1/gui/sessions/"+sessionId+"/messages","{\"clientMessageId\":\"cm-2\"}","key-r2"));
  Assert.assertEquals(404,status("GET","/api/v1/gui/unknown",null,null));
 }
 @Test public void runStateVersionNeverDecreasesAcrossPolls() throws Exception{
  String sessionId=createSession();
  Map<String,Object> sent=call("POST","/api/v1/gui/sessions/"+sessionId+"/messages","{\"clientMessageId\":\"cm-1\",\"content\":\"inspect\"}","key-sv1");
  String runId=(String)sent.get("runId");
  long highestSeen=0L;
  for(int i=0;i<200;i++){
   Map<String,Object> run=(Map<String,Object>)get("/api/v1/gui/runs/"+runId);
   long version=((Number)run.get("stateVersion")).longValue();
   Assert.assertTrue("observed stateVersion went backwards: "+highestSeen+" -> "+version,version>=highestSeen);
   if(version>highestSeen)highestSeen=version;
   Object statusValue=run.get("status");
   if(!"QUEUED".equals(statusValue)&&!"RUNNING".equals(statusValue)&&!"WAITING_APPROVAL".equals(statusValue))break;
   Thread.sleep(20);
  }
  Assert.assertTrue("terminal run must have recorded at least three persisted transitions",highestSeen>=3L);
 }
 @Test public void staticUiServesIndexAndRejectsTraversal() throws Exception{
  Assert.assertEquals(301,statusNoAuth("GET","/",null));
  Assert.assertEquals(301,statusNoAuth("GET","/ui",null));
  Assert.assertEquals(200,statusNoAuth("GET","/ui/",null));
  Assert.assertEquals(200,statusNoAuth("GET","/ui/index.html",null));
  Assert.assertEquals(200,statusNoAuth("GET","/ui/app.js",null));
  Assert.assertEquals(404,statusNoAuth("GET","/ui/../pom.xml",null));
  Assert.assertEquals(404,statusNoAuth("GET","/ui/../../etc/passwd",null));
  Assert.assertEquals(404,statusNoAuth("GET","/ui/not-registered.txt",null));
  Assert.assertEquals(404,statusNoAuth("GET","/ui/vendor/other.js",null));
 }
 // ---- helpers ----
 private String createSession() throws Exception{Map<String,Object> s=call("POST","/api/v1/gui/sessions","{\"title\":\"t\",\"workspaceAlias\":\"sample\",\"mode\":\"MODIFY\",\"writeAllow\":[\"src/main/java\"],\"writeForbid\":[],\"verificationLevel\":\"VERIFY\",\"maxModelTurns\":10,\"maxEditAttempts\":3}","key-create");return (String)s.get("id");}
 private void awaitRun(String runId)throws Exception{for(int i=0;i<200;i++){Object status=get("/api/v1/gui/runs/"+runId);if(status instanceof Map&&!((Map<?,?>)status).get("status").equals("QUEUED")&&!((Map<?,?>)status).get("status").equals("RUNNING")&&!((Map<?,?>)status).get("status").equals("WAITING_APPROVAL"))return;Thread.sleep(20);}Assert.fail("run did not finish");}
 private Object get(String path)throws Exception{return call("GET",path,null,null);}
 @SuppressWarnings("unchecked") private List<Object> getList(String path)throws Exception{
  HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+port+path).openConnection();
  c.setRequestMethod("GET");c.setRequestProperty("Authorization","Bearer "+TOKEN);
  int code=c.getResponseCode();lastCode=code;
  String response=read(code>=400?c.getErrorStream():c.getInputStream());
  if(code>=400)throw new HttpProblem(code,response);
  return (List<Object>)mapper.readValue(response,List.class);
 }
 @SuppressWarnings("unchecked") private Map<String,Object> patchViaSocket(String path,String body)throws Exception{
  byte[] payload=body==null?new byte[0]:body.getBytes("UTF-8");
  Socket socket=new Socket("127.0.0.1",port);
  try{
    OutputStream out=socket.getOutputStream();
    out.write(("PATCH "+path+" HTTP/1.1\r\nHost: 127.0.0.1:"+port+"\r\nAuthorization: Bearer "+TOKEN+"\r\nContent-Type: application/json\r\nContent-Length: "+payload.length+"\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
    out.write(payload);out.flush();
    String response=read(socket.getInputStream());
    int codeStart=response.indexOf(' ')+1;
    lastCode=Integer.parseInt(response.substring(codeStart,response.indexOf(' ',codeStart)));
    int bodyStart=response.indexOf("\r\n\r\n")+4;
    String json=response.substring(bodyStart);
    if(lastCode>=400)throw new HttpProblem(lastCode,json);
    return json.isEmpty()?Collections.<String,Object>emptyMap():(Map<String,Object>)mapper.readValue(json,Map.class);
  }finally{socket.close();}
 }
 private int lastCode;
 /** Minimal raw-socket HTTP request; used for PATCH which HttpURLConnection cannot send. */
 @SuppressWarnings("unchecked") private Map<String,Object> call(String method,String path,String body,String idempotencyKey)throws Exception{
  if("PATCH".equals(method))return patchViaSocket(path,body);
  HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+port+path).openConnection();
  c.setRequestMethod(method);
  c.setRequestProperty("Authorization","Bearer "+TOKEN);
  if(idempotencyKey!=null)c.setRequestProperty("Idempotency-Key",idempotencyKey);
  if(body!=null){c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");OutputStream out=c.getOutputStream();out.write(body.getBytes("UTF-8"));out.close();}
  int code=c.getResponseCode();lastCode=code;
  InputStream stream=code>=400?c.getErrorStream():c.getInputStream();
  String response=stream==null?"":read(stream);
  if(code>=400)throw new HttpProblem(code,response);
  return response.isEmpty()?Collections.<String,Object>emptyMap():(Map<String,Object>)mapper.readValue(response,Map.class);
 }
 private int status(String method,String path,String body,String idempotencyKey)throws Exception{try{call(method,path,body,idempotencyKey);return -1;}catch(HttpProblem p){return p.code;}}
 private int statusNoAuth(String method,String path,String body)throws Exception{HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+port+path).openConnection();c.setInstanceFollowRedirects(false);c.setRequestMethod(method);if(body!=null){c.setDoOutput(true);OutputStream o=c.getOutputStream();o.write(body.getBytes("UTF-8"));o.close();}int code=c.getResponseCode();c.disconnect();return code;}
 private static String read(InputStream in)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];for(int n;(n=in.read(b))>=0;)out.write(b,0,n);in.close();return out.toString("UTF-8");}
 private static final class HttpProblem extends RuntimeException{final int code;HttpProblem(int code,String body){super(code+": "+body);this.code=code;}}
 private int serverPort(BusinessHttpServer ignored)throws Exception{java.lang.reflect.Field f=BusinessHttpServer.class.getDeclaredField("server");f.setAccessible(true);return ((com.sun.net.httpserver.HttpServer)f.get(ignored)).getAddress().getPort();}
 private void git(String... args)throws Exception{List<String> c=new ArrayList<String>();c.add("git");c.add("-C");c.add(workspace.toString());Collections.addAll(c,args);Process p=new ProcessBuilder(c).start();byte[] buffer=new byte[1024];for(int count;(p.getErrorStream().read(buffer))>=0;);if(p.waitFor()!=0)throw new IOException("git fixture failed");}
 private static void delete(Path p){if(p==null||!Files.exists(p))return;try{Files.walk(p).sorted(Comparator.reverseOrder()).forEach(x->{try{Files.deleteIfExists(x);}catch(IOException e){/* Windows/git may retain a transient handle */}});}catch(IOException ignored){}}
}
