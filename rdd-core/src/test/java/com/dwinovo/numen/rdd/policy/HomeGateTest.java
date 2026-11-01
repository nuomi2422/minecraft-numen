package com.dwinovo.numen.rdd.policy;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 167 §B6：基地阶段是高风险动作的硬依赖，且只收紧不放松。 */
class HomeGateTest {

    @Test
    void lowRiskIsUntouched() {
        var normal = RiskGate.check(RiskLevel.NORMAL, Map.of());
        assertEquals(normal.allowed(), HomeGate.enforce(normal, HomeStage.NO_HOME).allowed());
        var mining = RiskGate.check(RiskLevel.MINING, Map.of());
        assertEquals(mining.allowed(), HomeGate.enforce(mining, HomeStage.NO_HOME).allowed());
    }

    @Test
    void netherNeedsAtLeastStocked() {
        var open = RiskGate.check(RiskLevel.NORMAL, Map.of());   // 用一个 allowed 的裁决当载体
        var asNether = new RiskGate.Verdict(true, RiskLevel.NETHER, java.util.List.of(), java.util.List.of());
        for (HomeStage st : java.util.List.of(HomeStage.NO_HOME, HomeStage.CANDIDATE,
                HomeStage.SHELTERED, HomeStage.RESPAWN_BOUND)) {
            var v = HomeGate.enforce(asNether, st);
            assertFalse(v.allowed(), st + " 不该放行下界");
            assertTrue(v.missing().stream().anyMatch(m -> m.contains("home_stage=" + st.name())),
                    v.missing().toString());
        }
        for (HomeStage st : java.util.List.of(HomeStage.STOCKED, HomeStage.LIT_PROTECTED, HomeStage.ESTABLISHED)) {
            var v = HomeGate.enforce(asNether, st);
            assertTrue(v.allowed(), st + " 已经具备恢复能力，不该被基地门拦");
            assertEquals(java.util.List.of(), v.missing());
        }
        assertTrue(open.allowed());
    }

    @Test
    void establishedIsNotAPassReasonByItself() {
        // 家里再齐，也不能把 RiskGate 的物资缺口抹掉
        var blocked = RiskGate.check(RiskLevel.NETHER, Map.of());
        var v = HomeGate.enforce(blocked, HomeStage.ESTABLISHED);
        assertFalse(v.allowed(), "ESTABLISHED 不能抵消物资缺口");
        assertEquals(blocked.missing(), v.missing());
    }
}
