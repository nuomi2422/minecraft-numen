package com.dwinovo.numen.plugins.learner.core;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-31：复盘草稿的「可落库」判定。
 *
 * <p><b>为什么需要这条测试</b>：2026-10-27 的自动落库用 {@code publishable} 当门槛，
 * 但渲染处从不置 true ⇒ 自动落库一直是死代码（经验库从 2026-10-29 起没有新同伴的条目）。
 * 同时质量门的 Q2/Q4 恒 UNDECIDABLE，PASS 不可达；若继续把「可发布」当「可落库」，
 * 就算补上 true 也永远落不了。本测试锁住「REVIEW_REQUIRED 可以落 DRAFT、REJECT 不行」。</p>
 */
class ExperiencePersistencePolicyTest {

    private static final Gson GSON = new Gson();

    private static final String GOOD = "{\"mechanism\":\"下界竖井要先探底再往下挖\","
            + "\"preconditions\":\"y<=-12 且垂直通道\","
            + "\"failureConditions\":\"开阔熔岩湖上方\","
            + "\"observableSignal\":\"血量<6 且无食物\","
            + "\"derivation\":\"血量<6 → 上岸 → 找食物\","
            + "\"efficiency\":\"一次下潜 12 格\","
            + "\"evidence\":\"ep7 t2000\","
            + "\"experienceType\":\"EXECUTION\"}";

    private static JsonObject obj(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static Verdict verdict(String experienceJson) {
        return Verdict.parse("m-1",
                "{\"memo_id\":\"m-1\",\"actions\":[\"WRITE_EXPERIENCE\"],\"confidence\":0.8,"
                        + "\"reasoning\":\"r\",\"experience\":" + experienceJson + "}");
    }

    private static Memo memo() {
        return new Memo("m-1", "往下挖的时候掉血", "mining",
                "先直挖 12 格再铺水", "hp=12/20, armor=none", 1000L);
    }

    /** 真实链路：Verdict → ExperienceDraft → 回执形状 {entry, mapping}。 */
    private static Map<String, Object> realDraftShell(boolean giveType) {
        String json = giveType ? GOOD : GOOD.replace(",\"experienceType\":\"EXECUTION\"}", "}");
        ExperienceDraft d = ExperienceDraft.from(verdict(json), memo());
        Map<String, Object> shell = new LinkedHashMap<>();
        shell.put("entry", GSON.fromJson(d.toJson(), Map.class));
        shell.put("mapping", GSON.fromJson(d.explain(), Map.class));
        return shell;
    }

    @Test
    void reviewRequiredDraftIsStorableAsDraft() {
        ExperienceQualityGate.Result gate = ExperienceQualityGate.evaluate(
                Experience.parse(obj("{\"experience\":" + GOOD + "}")), memo(),
                "下界竖井先探底；遇到开阔熔岩湖就回撤，血量<6 先上岸找食物。");
        assertFalse(gate.hasHardFailure(), "Q0/Q1/Q5/Q6 都应通过；硬失败为 false 才有资格落库");
        assertTrue(ExperiencePersistencePolicy.storable(realDraftShell(true), gate.hasHardFailure()),
                "REVIEW_REQUIRED 的草稿应可写进库（DRAFT），发布资格另算");
    }

    @Test
    void draftWithoutTypeIsNotStorable() {
        // 没有 type 的草稿进不了 ExperienceStore（learn() 会抛），不许假装可落库
        assertFalse(ExperiencePersistencePolicy.storable(realDraftShell(false), false));
    }

    @Test
    void hardFailedGateIsNeverStorable() {
        assertFalse(ExperiencePersistencePolicy.storable(realDraftShell(true), true));
    }

    @Test
    void missingOrMalformedDraftIsNotStorable() {
        assertFalse(ExperiencePersistencePolicy.storable(null, false));
        assertFalse(ExperiencePersistencePolicy.storable("not-a-map", false));
        assertFalse(ExperiencePersistencePolicy.storable(Map.of("entry", "not-a-map"), false));
        assertFalse(ExperiencePersistencePolicy.storable(Map.of("entry", Map.of()), false));
        assertFalse(ExperiencePersistencePolicy.storable(Map.of("entry", Map.of("type", " ")), false));
    }
}
