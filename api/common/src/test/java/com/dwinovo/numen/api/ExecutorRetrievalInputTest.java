package com.dwinovo.numen.api;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** B0/B1: actual query builder -> actual knowledge aggregator -> contributor objective capture.
 * Source only in this batch; hand-filled fragments do NOT prove the RDD renderer.
 */
class ExecutorRetrievalInputTest {
    private static final UUID A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static StateFragment rdd(String content) { return new StateFragment("rdd", "runtime", "<rdd>"+content+"</rdd>", true, 100); }
    private static String fields(String goal, String phase, String step) {
        return "<active>true</active><goal_objective>"+goal+"</goal_objective><current_phase>"+phase+"</current_phase><current_task>"+step+"</current_task>";
    }
    private static String consume(UUID id, List<StateFragment> fragments, String core, String body, String why) {
        String query = ExecutorRetrievalInput.build(fragments, core, body, why);
        AtomicReference<String> captured = new AtomicReference<>();
        NumenPlugins.aggregateExecutorKnowledgeFragment(List.of((companion, objective) -> {
            assertEquals(id, companion); captured.set(objective);
            return NumenPlugins.KnowledgeFragment.ofText("captured");
        }), id, query);
        assertEquals(query, captured.get());
        assertTrue(captured.get().length() <= 400);
        return captured.get();
    }
    @Test void longBodyAndUnrelatedPluginPrefixCannotDisplaceRddTask() {
        var unrelated = new StateFragment("other", "runtime", "unrelated".repeat(600), true, 100);
        String q = consume(A, List.of(unrelated, rdd(fields("寻找下界要塞", "获取烈焰棒", "攻击烈焰人"))),
                "", "background".repeat(100), "<rationale><why>制作酿造台需要烈焰棒</why></rationale>");
        assertTrue(q.contains("寻找下界要塞")); assertTrue(q.contains("获取烈焰棒")); assertTrue(q.contains("攻击烈焰人"));
        assertFalse(q.contains("unrelated")); assertFalse(q.contains("<current_task"));
    }
    @Test void everyLongTaskFieldAndFactsKeepTheirReservedShare() {
        String q = consume(A, List.of(rdd(fields("目标".repeat(500), "阶段".repeat(500), "子步".repeat(500))+
                "血量 3 night=1 hostile=true bed=1 护甲=无")), "", "身体".repeat(500), "<why>理由"+"长".repeat(500)+"</why>");
        for(String label : List.of("goal:","phase:","step:","body:","why:")) assertTrue(q.contains(label), label);
        assertTrue(q.contains("hp=3")); assertTrue(q.contains("night=1")); assertTrue(q.contains("hostile=1"));
        assertTrue(q.contains("<state_facts")); assertTrue(q.contains("/>"));
    }
    @Test void lazyStageDoesNotInventSubtaskOrCarryStaleWhy() {
        String q = consume(A, List.of(rdd("<active>true</active><goal_objective>总目标</goal_objective><current_phase>待展开阶段</current_phase>")),
                "stale core", "", "<why>stale rationale</why>");
        assertTrue(q.contains("待展开阶段")); assertFalse(q.contains("step:")); assertFalse(q.contains("stale"));
    }
    @Test void terminalMissingAndDecomposingRddDoNotResurrectOldTask() {
        for(String state : List.of("<active>false</active>", "<active>false</active><decomposing>true</decomposing>",
                "<active>true</active><primary_status>COMPLETED</primary_status>", "<active>true</active><primary_status>FAILED</primary_status>")) {
            String q = consume(A,List.of(rdd(state+"<goal_objective>OLD_GOAL</goal_objective><current_task>OLD_STEP</current_task>")),
                    "OLD_CORE", "", "<why>OLD_WHY</why>");
            assertFalse(q.contains("OLD_"));
        }
    }
    @Test void activeSideUsesExistingSideProjectionNotSuspendedMainline() {
        String q=consume(A,List.of(rdd("<active>true</active><scope>SIDE</scope><side_task type=\"sleep\">去睡觉</side_task>"+
                "<goal_objective>OLD_MAIN</goal_objective><current_task>OLD_STEP</current_task>")),"OLD_CORE","","<why>OLD_WHY</why>");
        assertTrue(q.contains("去睡觉")); assertFalse(q.contains("OLD_"));
    }
    @Test void nonRddKeepsCoreGoalAndRealBodyFallback() {
        String q=consume(A,List.of(),"制作工作台","收集木头","<why>缺木板</why>");
        assertTrue(q.contains("制作工作台"));assertTrue(q.contains("收集木头"));
        String bodyOnly=consume(A,List.of(),"","钓鱼","");assertTrue(bodyOnly.contains("钓鱼"));
    }
    @Test void escapedTaskTextAndUnknownValuesAreNotReplacedWithGuesses() {
        String q=consume(A,List.of(rdd(fields("未知&amp;待确认", "阶段&lt;未核&gt;", "子步UNKNOWN"))),"","","");
        assertTrue(q.contains("未知&待确认"));assertTrue(q.contains("阶段<未核>"));assertTrue(q.contains("UNKNOWN"));
    }
    @Test void companionSnapshotsNeverShareTaskFields() {
        String qa=consume(A,List.of(rdd(fields("A_GOAL","A_PHASE","A_STEP"))),"","","");
        String qb=consume(B,List.of(rdd(fields("B_GOAL","B_PHASE","B_STEP"))),"","","");
        assertTrue(qa.contains("A_GOAL"));assertFalse(qa.contains("B_GOAL"));
        assertTrue(qb.contains("B_GOAL"));assertFalse(qb.contains("A_GOAL"));
    }
}
