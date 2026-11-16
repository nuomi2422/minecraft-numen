package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.core.RddDeathLedger;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Current loss owner + real ledger and original facade; no Minecraft callbacks are exercised. */
class RddRepairLossAnalysisTest {
    @SuppressWarnings("unchecked")
    private static <T> T field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(null);
    }

    private static RddDeathLedger.Death selected(UUID id, int hint) throws Exception {
        var method = RddRepairLossAnalysis.class.getDeclaredMethod("deathForRecompute", UUID.class, int.class);
        method.setAccessible(true);
        return (RddDeathLedger.Death) method.invoke(null, id, hint);
    }

    @Test void explicitOpenAndFallbackBatchSelectionKeepOriginalLedgerSemantics() throws Exception {
        UUID id = UUID.randomUUID();
        RddDeathLedger.restore(id, new RddDeathLedger.LedgerSnapshot(1, java.util.List.of()));
        try {
            var first = RddDeathLedger.record(id, 1000L, System.currentTimeMillis(), "0,0,0", 1, Map.of("minecraft:dirt", 2));
            var latest = RddDeathLedger.record(id, 2000L, System.currentTimeMillis(), "1,0,0", 2, Map.of("minecraft:stone", 3));
            Map<UUID,Integer> open = field(RddRepairDispatch.class, "OPEN_DEATH_SEQ");
            open.put(id, first.seq());
            assertEquals(first.seq(), selected(id, -1).seq(), "original facade owns open batch");
            assertEquals(latest.seq(), selected(id, latest.seq()).seq(), "explicit hint wins");
            open.remove(id);
            assertEquals(first.seq(), selected(id, first.seq()).seq(), "hint survives timeout clearing OPEN");
            assertEquals(latest.seq(), selected(id, -1).seq(), "no open batch uses latest");
            RddDeathLedger.confirmRecovered(id, first.seq(), "test closed batch");
            assertEquals(latest.seq(), selected(id, first.seq()).seq(),
                    "existing closed-batch fallback retained; not a guarantee of historical exact-batch lookup");
        } finally {
            RddRepairDispatch.remove(id);
            RddDeathLedger.restore(id, new RddDeathLedger.LedgerSnapshot(1, java.util.List.of()));
        }
    }

    @Test void facadeRemoveClearsOnlyOriginalPhaseKeysNotRestartOrArmedGuards() throws Exception {
        UUID id = UUID.randomUUID(), other = UUID.randomUUID();
        Set<String> phases = field(RddRepairLossAnalysis.class, "RECOMPUTED");
        Map<UUID,Long> restart = field(RddRepairLossAnalysis.class, "LAST_RECOMPUTED");
        Set<UUID> armed = field(RddRepairLossAnalysis.class, "ARMED_LOGGED");
        try {
            phases.add(id + "|1|timeout"); phases.add(id + "|1|recovered"); phases.add(other + "|1|timeout");
            restart.put(id, 1L); armed.add(id);
            Map<UUID,Integer> open = field(RddRepairDispatch.class, "OPEN_DEATH_SEQ");
            open.put(id, 1);
            RddRepairDispatch.remove(id);
            assertFalse(open.containsKey(id), "dispatch batch ownership is still cleaned by its facade");
            assertFalse(phases.contains(id + "|1|timeout"));
            assertFalse(phases.contains(id + "|1|recovered"));
            assertTrue(phases.contains(other + "|1|timeout"));
            assertEquals(1L, restart.get(id));
            assertTrue(armed.contains(id), "process-lived armed/restart guards were not reset before extraction");
        } finally {
            phases.removeIf(key -> key.startsWith(id.toString()) || key.startsWith(other.toString()));
            restart.remove(id); armed.remove(id);
        }
    }

    @Test void emptyDeathAndNullFacadeBoundariesStayFailSoftWithoutHostReads() {
        assertNull(RddRepairLossAnalysis.analyzeDeathLoss(null, null));
        assertNull(RddRepairDispatch.recomputeAfterDeath(null, null, false, "restart"));
        assertNull(RddRepairDispatch.recomputeAfterDeath(null, Map.of(), true, "timeout", 5));
        assertEquals(Map.of(), RddRepairDispatch.backupFromBase(null));
        assertEquals("", RddRepairDispatch.renderSemanticNote(null));
    }
}
