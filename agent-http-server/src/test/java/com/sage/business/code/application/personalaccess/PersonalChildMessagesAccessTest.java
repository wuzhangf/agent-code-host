package com.sage.business.code.application.personalaccess;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.*;
import static org.junit.Assert.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * ACS child-messages/1 projection: delegated message kind mapping from
 * re-derived delegated-msg ids, decimal monotonic seq, coverage honesty and
 * run->group->execution->child-session reverse binding before any stream read.
 */
public class PersonalChildMessagesAccessTest {
    final ObjectMapper json=new ObjectMapper();
    PersonalLocalTaskAccessTest f;PersonalChildMessagesAccess access;
    AtomicInteger messageReads=new AtomicInteger();List<String> upstreamPaths=Collections.synchronizedList(new ArrayList<String>());
    volatile boolean wrongParent,wrongGroup,missingSession,bigContent;

    @Before public void setup()throws Exception {
        f=new PersonalLocalTaskAccessTest();f.setup();
        f.auth.updateScopeResources(f.did,"acs-scope",Long.toString(f.auth.authenticate(f.token).revision),Arrays.asList(new PersonalResourceScope.Resource("ACS_SESSION","acs-store","session-one")),Collections.emptyList(),new TreeSet<String>(Arrays.asList("OBSERVE")));
        access=new PersonalChildMessagesAccess(f.auth,json,(d,m,p,b,k)->{upstreamPaths.add(m+" "+p);return fake(p);});
    }
    @After public void close()throws Exception {if(f!=null)f.cleanup();}

    String delegated(String sid,String cid,String slot){return PersonalChildMessagesAccess.delegatedId(sid,cid,slot);}

    PersonalChildMessagesAccess.Reply fake(String path){
        ObjectNode body=json.createObjectNode();
        if(path.equals("/api/v2/runs/run-1"))return new PersonalChildMessagesAccess.Reply(200,body.put("runId","run-1").put("sessionId","session-one"));
        if(path.equals("/api/v2/runs/run-1/collaboration-groups/gid-1"))return new PersonalChildMessagesAccess.Reply(200,body.put("entityId","gid-1").put("runId","run-1"));
        if(path.equals("/api/v2/runs/run-1/collaboration-groups/gid-1/executions/cid-1"))
            return new PersonalChildMessagesAccess.Reply(200,body.put("entityId","cid-1").put("groupId",wrongGroup?"gid-other":"gid-1").put("sessionId",missingSession?null:"child-sid"));
        if(path.equals("/api/v2/sessions/child-sid"))
            return new PersonalChildMessagesAccess.Reply(200,body.put("sessionId","child-sid").put("parentSessionId",wrongParent?"other-parent":"session-one").put("sessionPurpose","DELEGATED_WRITE_CANDIDATE"));
        if(path.startsWith("/api/v2/sessions/child-sid/messages")){
            messageReads.incrementAndGet();
            int limit=50;String cursor=null;
            for(String pair:path.substring(path.indexOf('?')+1).split("&")){String[] kv=pair.split("=",2);if(kv.length==2&&kv[0].equals("limit"))limit=Integer.parseInt(kv[1]);if(kv.length==2&&kv[0].equals("cursor"))cursor=kv[1];}
            ArrayNode items=body.putArray("items");
            items.addObject().put("id",delegated("child-sid","cid-1","objective")).put("role","user").put("content","Fix the module").put("createdAt",1000L);
            items.addObject().put("id","delegated-msg-zzz").put("role","assistant").put("content",bigContent?new String(new char[70000]).replace('\0','x'):"Working on it").put("createdAt",1000L);
            items.addObject().put("id",delegated("child-sid","cid-1","answer")).put("role","assistant").put("content","Done").put("createdAt",1001L);
            boolean more=items.size()>limit&&cursor==null;
            while(items.size()>(cursor==null?limit:items.size()))items.remove(items.size()-1);
            body.put("hasOlder",false).put("hasNewer",more).put("afterCursor","af-1").put("transcriptRevision","3");
            return new PersonalChildMessagesAccess.Reply(200,body);
        }
        return new PersonalChildMessagesAccess.Reply(404,body.put("code","NOT_FOUND"));
    }

    PersonalChildMessagesAccess.Result call(String query)throws Exception {return access.messages(f.token,"run-1","gid-1","cid-1",query);}

    @Test public void projectionMapsDelegatedKindsSeqAndCoverage()throws Exception {
        JsonNode page=call(null).body;
        assertEquals("child-messages/1",page.path("schemaVersion").asText());
        assertEquals("session-one",page.path("parentTaskId").asText());
        assertEquals("gid-1",page.path("collaborationId").asText());
        assertEquals("cid-1",page.path("childId").asText());
        assertEquals("child-sid",page.path("streamId").asText());
        assertEquals("FULL_SINCE_CAPTURE",page.path("coverage").asText());
        ArrayNode items=(ArrayNode)page.path("items");
        assertEquals("ASSIGNMENT",items.get(0).path("kind").asText());
        assertEquals("STATUS",items.get(1).path("kind").asText());
        assertEquals("RETURN",items.get(2).path("kind").asText());
        long previous=-1;for(JsonNode item:items){long seq=Long.parseLong(item.path("seq").asText());assertTrue(seq>previous);previous=seq;}
        // GAP-07: createdAt is the wire ISO instant, converted from upstream epoch millis.
        assertEquals("1970-01-01T00:00:01Z",items.get(0).path("createdAt").asText());
        assertEquals("af-1",page.path("nextCursor").asText());
        assertFalse(page.path("hasMoreBefore").asBoolean());
    }
    @Test public void oversizedBodyFlagsRowAndDegradesCoverage()throws Exception {
        bigContent=true;
        JsonNode page=call(null).body;
        assertEquals("PARTIAL",page.path("coverage").asText());
        JsonNode row=null;for(JsonNode i:page.path("items"))if("STATUS".equals(i.path("kind").asText()))row=i;
        assertNotNull(row);
        assertTrue(row.path("textTruncated").asBoolean());
        assertTrue(row.path("text").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=65536);
        bigContent=false;
    }
    @Test public void childSessionMustBindToAuthorizedParent()throws Exception {
        wrongParent=true;
        f.denied("DEVICE_SCOPE_DENIED",()->call(null));
        wrongParent=false;missingSession=true;
        JsonNode page=call(null).body;
        assertEquals("UNAVAILABLE",page.path("coverage").asText());assertEquals(0,page.path("items").size());
        missingSession=false;wrongGroup=true;
        f.denied("LOCAL_BINDING_MISMATCH",()->call(null));
    }
    @Test public void queryWhitelistLimitCursorAndExclusiveBefore()throws Exception {
        f.denied("INVALID_REQUEST",()->call("cursor=c&before=b"));
        f.denied("INVALID_REQUEST",()->call("limit=101"));
        f.denied("INVALID_REQUEST",()->call("unknown=1"));
        f.denied("INVALID_REQUEST",()->call("limit=5&limit=6"));
        JsonNode page=call("limit=2").body;assertEquals(2,page.path("items").size());
        page=call("cursor=opaque-token.1").body;assertEquals(3,page.path("items").size());
        page=call("before=1001000").body;assertEquals(2,page.path("items").size());
        assertTrue(upstreamPaths.stream().anyMatch(p->p.contains("cursor=opaque-token.1")||p.contains("cursor=opaque-token%2E1")||p.contains("cursor=opaque-token")));
    }
    @Test public void truncationMarkerIsHonest()throws Exception {
        ObjectNode reply=json.createObjectNode();ArrayNode items=reply.putArray("items");for(int i=0;i<5;i++)items.add(i);
        assertTrue(PersonalChildMessagesAccess.truncateItems(reply,"items",3));
        assertEquals(3,reply.path("items").size());assertTrue(reply.path("truncated").asBoolean());
        assertFalse(PersonalChildMessagesAccess.truncateItems(reply,"items",5));
    }
}
