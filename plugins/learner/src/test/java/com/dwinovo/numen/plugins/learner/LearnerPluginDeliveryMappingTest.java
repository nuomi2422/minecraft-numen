package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.api.WorldContextStamp;
import com.dwinovo.numen.plugins.learner.core.UsageLedger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-13 C1：父 API DeliveredContext → LearnerPlugin 映射 → 现有 UsageLedger 的端到端边界。 */
class LearnerPluginDeliveryMappingTest {
    private static final UUID C = UUID.fromString("a43d753e-790d-472b-8d5b-dc18ac0afbc0");

    @TempDir Path dir;

    private static NumenPlugins.DeliveredContext ctx(WorldContextStamp.Token token) {
        return new NumenPlugins.DeliveredContext(C, token, "req-1", "execution", "EXECUTOR", "", "",
                List.of(new NumenPlugins.DeliveredRef("skill:sk-a", "v1", false)));
    }

    @Test
    void mapsFullContextIntoOnePresentedUnknownRow() {
        UsageLedger ledger = new UsageLedger(dir);
        var token = new WorldContextStamp.Token(Map.of("world_id", "live-x", "server_session", "s-1"), 1L);
        DeliveryContextSink.accept(ctx(token), ledger);

        assertEquals(1, ledger.all().size());
        UsageLedger.Entry e = ledger.all().get(0);
        assertEquals(UsageLedger.Phase.PRESENTED, e.phase());
        assertEquals(UsageLedger.Outcome.UNKNOWN, e.outcome());
        assertEquals(C.toString(), e.companionId());
        assertTrue(e.detail().contains("live-x"));
        assertTrue(e.detail().contains("execution"));
    }

    @Test
    void missingWorldTokenWritesNothing() {
        UsageLedger ledger = new UsageLedger(dir);
        DeliveryContextSink.accept(ctx(new WorldContextStamp.Token(null, 0L)), ledger);
        assertTrue(ledger.all().isEmpty(), "旧世界/无身份不落新候选，也不伪造 episode");
    }
}
