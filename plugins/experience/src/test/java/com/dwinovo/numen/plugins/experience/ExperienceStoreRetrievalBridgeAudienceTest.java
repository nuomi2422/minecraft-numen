package com.dwinovo.numen.plugins.experience;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A6（98 号）：tags 里的 audience:* 映射成显式角色；空 = legacy。 */
class ExperienceStoreRetrievalBridgeAudienceTest {

    @Test
    void parsesAudienceTags() {
        assertEquals(List.of("PLANNER"), ExperienceStoreRetrievalBridge.audienceFrom(List.of("learner", "audience:planner")));
        assertEquals(List.of("EXECUTOR", "LEARNER"),
                ExperienceStoreRetrievalBridge.audienceFrom(List.of("audience:executor", "audience:Learner")));
    }

    @Test
    void ignoresUnknownAndEmpty() {
        assertTrue(ExperienceStoreRetrievalBridge.audienceFrom(null).isEmpty());
        assertTrue(ExperienceStoreRetrievalBridge.audienceFrom(List.of()).isEmpty());
        assertTrue(ExperienceStoreRetrievalBridge.audienceFrom(List.of("audience:", "audience:boss", "learner")).isEmpty());
    }

    @Test
    void dedupes() {
        assertEquals(List.of("PLANNER"),
                ExperienceStoreRetrievalBridge.audienceFrom(List.of("audience:planner", "audience:PLANNER")));
    }
}
