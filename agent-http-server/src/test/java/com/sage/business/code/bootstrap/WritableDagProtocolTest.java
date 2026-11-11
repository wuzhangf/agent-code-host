package com.sage.business.code.bootstrap;
import org.junit.Test;
/** Real eight-unit graph: five scoped writers, three existing Plan read-only executions and fixed ancestors. */
public class WritableDagProtocolTest extends WritableCollaborationProtocolTest {
    @Override protected boolean dagFixture(){return true;}
    @Test public void actualEightUnitDagUsesOnlyDeclaredAncestorBytesAndCannotActivateChildSessions()throws Exception{exercise(false,false);}
}
