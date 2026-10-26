package com.dwinovo.numen.experience.core;

import com.dwinovo.numen.experience.api.ExperienceEntry;
import com.dwinovo.numen.experience.api.ExperienceMaturity;
import com.dwinovo.numen.experience.api.ExperienceType;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * WORLD_MECHANIC 是 2026-10-26 新增的第六类：它必须能独立解析、独立保 id，
 * 不能被世界关系/策略吞并。种子卡（和平不刷怪、跳夜需就寝）依赖这条。
 */
class WorldMechanicTypeTest {

    @Test
    void worldMechanicParsesAsItsOwnTypeAndKeepsStableId() {
        JsonObject o = new JsonObject();
        o.addProperty("type", "WORLD_MECHANIC");
        o.addProperty("title", "难度=peaceful 时敌对生物不会自然生成");
        o.addProperty("description", "探索很久看不到敌对实体，先查难度");
        o.addProperty("mechanism", "peaceful ⇒ 敌对生物不自然生成");
        o.addProperty("maturity", "OBSERVED");
        o.addProperty("priority", 90);

        ExperienceEntry e = ExperienceEntry.fromJson(o);

        assertEquals(ExperienceType.WORLD_MECHANIC, e.type());
        assertEquals("world_mechanic|难度=peaceful 时敌对生物不会自然生成", e.id());
        assertEquals(ExperienceMaturity.OBSERVED, e.maturity());
        assertEquals("peaceful ⇒ 敌对生物不自然生成", e.mechanism());
    }

    @Test
    void worldMechanicStableKeyIsDistinctFromWorldRelation() {
        String title = "同一句世界规则";
        String mech = ExperienceEntry.stableKey(ExperienceType.WORLD_MECHANIC, title);
        String rel = ExperienceEntry.stableKey(ExperienceType.WORLD_RELATION, title);
        assertEquals("world_mechanic|同一句世界规则", mech);
        org.junit.jupiter.api.Assertions.assertNotEquals(mech, rel);
    }
}
