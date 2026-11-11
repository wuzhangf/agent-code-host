package com.sage.business.code.bootstrap;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.*;
public class WritableBudgetNamespaceProtocolTest {
    @BeforeClass public static void isolate(){FoundationProtocolAcceptanceTest.isolateRuntimeOptionalStorage();}
    @AfterClass public static void restore(){FoundationProtocolAcceptanceTest.restoreRuntimeOptionalStorage();}
    @Test public void unitNamedIntegrationUsesItsOwnPoolAndLeavesTheOriginalAdoptionReserve()throws Exception {
        WritableCollaborationProtocolTest fixture=new WritableCollaborationProtocolTest(){
            @Override protected void customizeWritableGraph(ObjectNode graph){((ObjectNode)graph.path("units").get(0)).put("unitId","integration");}
            @Override protected JsonNode api(String method,String path,String key,String client,JsonNode body,int expected)throws Exception {
                if("POST".equals(method)&&path.endsWith("/authorizations"))((ObjectNode)body.path("units").get(0)).put("unitId","integration");
                return super.api(method,path,key,client,body,expected);
            }
        };
        try{fixture.start();fixture.exercise(false,false);}finally{fixture.releaseModel.release(100);fixture.close();}
    }
    @Test public void legacyUnmarkedGroupKeepsItsOriginalUnitBudgetIdentity()throws Exception {
        WritableCollaborationProtocolTest fixture=new WritableCollaborationProtocolTest(){
            @Override protected JsonNode api(String method,String path,String key,String client,JsonNode body,int expected)throws Exception {
                JsonNode result=super.api(method,path,key,client,body,expected);
                if("POST".equals(method)&&path.endsWith("/collaboration-groups")){
                    try(java.sql.Connection db=java.sql.DriverManager.getConnection("jdbc:sqlite:"+root.resolve("state/gui-sessions.db"))){
                        try(java.sql.Statement q=db.createStatement()){Assert.assertEquals(1,q.executeUpdate("UPDATE writable_collaboration_records SET payload_json=json_remove(payload_json,'$.budgetNamespace') WHERE kind='GROUP'"));}
                    }
                }
                return result;
            }
        };
        try{fixture.start();fixture.exercise(false,false);}finally{fixture.releaseModel.release(100);fixture.close();}
    }

}
