package com.dwinovo.numen.task;

import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.api.TaskFocusLeaseService;
import com.dwinovo.numen.api.WorldContextStamp;
import com.dwinovo.numen.entity.NumenPlayer;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class TaskFocusBrainTest {
    static final class Record extends TaskRecord {
        Record() { super("focus-fixture","call",1000); }
        @Override public String describe() { return "focus"; }
    }
    @Test void lateSelectionAcquiresOnceAndOldFinishCannotReleaseNewOwner() throws Exception {
        var id=UUID.randomUUID(); var brain=new CompanionBrain(id);
        var token=new WorldContextStamp.Token(Map.of("world_id","w","server_session","s"),1);
        var first=new Record(); first.bindIdentity(token); first.setState(TaskState.RUNNING);
        var second=new Record(); second.bindIdentity(token); second.setState(TaskState.RUNNING);
        var current=new java.util.HashSet<String>(); var count=new int[1];
        TaskFocusLeaseService fake=new TaskFocusLeaseService() {
            public Lease acquire(NumenPlayer c,TaskRecord task,String owner) { count[0]++; current.add(owner); return new Lease(id,owner,"lease-"+owner,Long.MAX_VALUE); }
            public boolean stillEligible(NumenPlayer c,TaskRecord task,Lease lease) { return true; }
            public void release(Lease l) { current.remove(l.owner()); }
        };
        var field=NumenPlugins.class.getDeclaredField("CAPABILITIES"); field.setAccessible(true);
        @SuppressWarnings("unchecked") var caps=(java.util.Map<Class<?>,Object>)field.get(null);
        Object previous=caps.put(TaskFocusLeaseService.class,fake);
        try {
            brain.reconcileTaskFocus(null,first); assertEquals(0,count[0]);
            assertTrue(first.bindSkillSelection(new TaskRecord.SkillSelection("skill:pkg","v1","body","digest","call",id.toString(),token)));
            brain.reconcileTaskFocus(null,first); brain.reconcileTaskFocus(null,first);
            assertEquals(1,count[0]); assertEquals(1,current.size());
            assertTrue(second.bindSkillSelection(new TaskRecord.SkillSelection("skill:pkg","v1","body","digest","call2",id.toString(),token)));
            brain.reconcileTaskFocus(null,second); assertEquals(2,current.size());
            first.setState(TaskState.CANCELLED); brain.rememberFinished(first); brain.rememberFinished(first);
            assertEquals(1,current.size(),"old terminal callback cannot release newer owner");
            second.setState(TaskState.SUCCESS); brain.rememberFinished(second); assertTrue(current.isEmpty());
        } finally { if(previous==null)caps.remove(TaskFocusLeaseService.class); else caps.put(TaskFocusLeaseService.class,previous); }
    }
}
