package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.api.observation.ObservationBatch;
import com.dwinovo.numen.api.observation.WorldObservationService;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-07：4→1 请求、逐条缺失/错误隔离、同刻证明。 */
class RddObservationBatchTest {
    private static WorldObservationService.Response r(WorldObservationService.Status s, String topic, long tick) {
        return new WorldObservationService.Response(s, topic, "snap", tick,
                WorldObservationService.Coverage.FULL, s == WorldObservationService.Status.ERROR ? "boom" : "", List.of(), 0L);
    }
    private static ObservationBatch.Result res(WorldObservationService.Response... rs) {
        return new ObservationBatch.Result("q", WorldObservationService.Status.OK,
                ObservationBatch.Consistency.SAME_TICK, 0, List.of(rs), "");
    }
    @Test void oneQueryCarriesFourRequests() {
        var q = RddObservationBatch.query("minecraft:overworld", 0, 64, 0);
        assertEquals(4, q.requests().size());
        assertEquals("environment", q.requests().get(0).topic());
        assertEquals("blocks", q.requests().get(1).topic());
        assertEquals("entities", q.requests().get(2).topic());
        assertEquals("entities", q.requests().get(3).topic());
        assertEquals(ObservationBatch.MAX_DEADLINE_MS, q.deadlineMs());
    }
    @Test void fullBatchSameTick() {
        var s = RddObservationBatch.summarize(res(
                r(WorldObservationService.Status.OK,"environment",10),
                r(WorldObservationService.Status.OK,"blocks",10),
                r(WorldObservationService.Status.OK,"entities",10),
                r(WorldObservationService.Status.OK,"entities",10)));
        assertTrue(s.missingTopics().isEmpty());
        assertTrue(s.errorTopics().isEmpty());
        assertTrue(s.sameTick());
        assertEquals(4, s.observedCount());
    }
    @Test void missingAndErrorAreIsolatedPerTopic() {
        var s = RddObservationBatch.summarize(res(
                r(WorldObservationService.Status.ERROR,"environment",10),
                r(WorldObservationService.Status.OK,"blocks",10),
                r(WorldObservationService.Status.OK,"entities",10)));
        assertEquals(List.of("entities#close"), s.missingTopics());
        assertEquals(List.of("environment"), s.errorTopics());
        assertEquals(3, s.observedCount());
    }
    @Test void differentTicksNotSameTick() {
        var s = RddObservationBatch.summarize(res(
                r(WorldObservationService.Status.OK,"environment",10),
                r(WorldObservationService.Status.OK,"blocks",11),
                r(WorldObservationService.Status.OK,"entities",10),
                r(WorldObservationService.Status.OK,"entities",10)));
        assertFalse(s.sameTick());
    }
}
