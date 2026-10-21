package com.dwinovo.numen.experience.api;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G6a 判据 G1/G3（离线）：发布资格状态机 + 老条目兼容。
 *
 * <p>最要紧的一条：**缺键/未知值必须落 PUBLISHED** —— 升级当天不许把所有既有经验藏起来。
 */
class PublicationStateTest {

    @Test
    void 缺键与未知值都落PUBLISHED() {
        assertEquals(PublicationState.PUBLISHED, PublicationState.parse(null));
        assertEquals(PublicationState.PUBLISHED, PublicationState.parse(""));
        assertEquals(PublicationState.PUBLISHED, PublicationState.parse("  "));
        assertEquals(PublicationState.PUBLISHED, PublicationState.parse("GARBAGE"));
        assertEquals(PublicationState.TRIAL, PublicationState.parse("trial"));
        assertEquals(PublicationState.REVOKED, PublicationState.parse(" REVOKED "));
    }

    @Test
    void 只有PUBLISHED可执行_REVOKED不可检索() {
        assertTrue(PublicationState.PUBLISHED.executable());
        assertFalse(PublicationState.DRAFT.executable());
        assertFalse(PublicationState.TRIAL.executable());
        assertFalse(PublicationState.SUSPENDED.executable());
        assertFalse(PublicationState.REVOKED.executable());

        assertTrue(PublicationState.DRAFT.retrievable());
        assertTrue(PublicationState.PUBLISHED.retrievable());
        assertFalse(PublicationState.REVOKED.retrievable());
    }

    @Test
    void 迁移表_合法与非法() {
        assertTrue(PublicationState.DRAFT.canTransitionTo(PublicationState.TRIAL));
        assertTrue(PublicationState.DRAFT.canTransitionTo(PublicationState.PUBLISHED));
        assertTrue(PublicationState.TRIAL.canTransitionTo(PublicationState.PUBLISHED));
        assertTrue(PublicationState.PUBLISHED.canTransitionTo(PublicationState.SUSPENDED));
        assertTrue(PublicationState.SUSPENDED.canTransitionTo(PublicationState.PUBLISHED));

        assertFalse(PublicationState.DRAFT.canTransitionTo(PublicationState.DRAFT), "同状态不算迁移");
        assertFalse(PublicationState.PUBLISHED.canTransitionTo(PublicationState.DRAFT), "发布后不许退回草稿");
        assertFalse(PublicationState.REVOKED.canTransitionTo(PublicationState.PUBLISHED), "撤回是终态");
        assertTrue(PublicationState.REVOKED.terminal());
    }

    @Test
    void 条目编解码_新键写出_老JSON读成PUBLISHED() {
        ExperienceEntry e = ExperienceEntry.builder()
                .type(ExperienceType.EXECUTION)
                .title("发布门测试")
                .description("d")
                .publication(PublicationState.TRIAL)
                .build();
        JsonObject json = e.toJson();
        assertEquals("TRIAL", json.get("publication").getAsString());
        assertEquals(PublicationState.TRIAL, ExperienceEntry.fromJson(json).publication());

        // 老条目：把键删掉，读回来必须是 PUBLISHED
        JsonObject legacy = e.toJson();
        legacy.remove("publication");
        assertEquals(PublicationState.PUBLISHED, ExperienceEntry.fromJson(legacy).publication());
    }
}
