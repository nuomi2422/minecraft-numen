package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.agent.context.ContextBudget;
import com.dwinovo.numen.agent.provider.OpenAIProvider;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Village-dependent home guidance at the serialized request boundary (2026-11-12).
 * WHY: the old optional playbook rejected village houses while the owner's doctrine
 * preferred reusing them. Exercise the real block, budget and provider, not source text.
 * No Minecraft world, credentials or model transport is started by these tests.
 * These assertions cover guidance delivery only, not a proved village/home relationship.
 */
class RddBasePlaybookTest {
    private static final String SAFETY = "hp=1 danger=3 current-task=home-safety";
    private static final String RUNTIME = "plugin:rdd:runtime";
    private static final String PLAYBOOK = "plugin:rdd:base_playbook";

    @Test
    void requestPrefersObservedVillageReuseBeforeConditionalConstruction() {
        String text = userContent(request("通关MC 在基地安家", ContextBudget.unlimited()));
        assertTrue(text.contains("选定村庄"), "home guidance must name its village dependency");
        assertTrue(text.contains("已有农田"), "reuse must include existing food assets");
        assertTrue(text.contains("村民交易"), "village dependence must retain the trading reason");
        int inspect = text.indexOf("先实查");
        int reuse = text.indexOf("优先复用");
        int conditionalBuild = text.indexOf("没有合适房屋");
        int platform = text.indexOf("settlement_platform");
        assertTrue(inspect >= 0 && reuse > inspect, "inspect before choosing to reuse");
        assertTrue(conditionalBuild > reuse && platform > conditionalBuild,
                "platform must be a conditional fallback after reuse, never the default first step");
        assertTrue(text.contains("村内或村旁"));
        assertTrue(text.contains("可达"));
    }

    @Test
    void coreHouseSynonymReceivesTheSameVillageDependentRequest() {
        String text = userContent(request("完善核心屋", ContextBudget.unlimited()));
        assertTrue(text.contains("<base_playbook>"));
        assertTrue(text.contains("选定村庄"));
        assertTrue(text.contains("优先复用"));
    }

    @Test
    void requestDoesNotRejectVillageHousesOrTreatUnknownAsAbsentOrOrderRelocation() {
        String text = userContent(request("安家", ContextBudget.unlimited()));
        assertFalse(text.contains("别硬挤村民的房子"), "do not contradict the reusable-village-house doctrine");
        assertFalse(text.contains("先在你附近选一块"), "do not default to an unrelated empty site");
        assertFalse(text.contains("平台中央放床"), "the reusable house must not be forced onto a new platform");
        assertTrue(text.contains("未观测不等于没有"));
        assertTrue(text.contains("未知"));
        assertTrue(text.contains("不搬迁已可用基地"));
        assertTrue(text.contains("不另建农田"));
        assertTrue(text.contains("收获后补种"));
    }

    @Test
    void reusedOrNewHomeStillCarriesSafetyRespawnAndActualReserveRequirements() {
        String text = userContent(request("基地收口", ContextBudget.unlimited()));
        assertTrue(text.contains(SAFETY));
        assertTrue(text.contains("安全可用"));
        assertTrue(text.contains("水") && text.contains("悬崖"));
        assertTrue(text.contains("照明"));
        assertTrue(text.contains("sleep") && text.contains("重生点"));
        assertTrue(text.contains("保留"));
        assertTrue(text.contains("箱子") && text.contains("实际存入"));
        assertTrue(text.contains("备用装备"));
        assertTrue(text.contains("floor_block") && text.contains("背包里真有"));
    }

    @Test
    void unrelatedOrMissingTaskDoesNotInjectAndDoesNotInheritPreviousHomeText() {
        assertTrue(userContent(request("安家", ContextBudget.unlimited())).contains("<base_playbook>"));
        for (String task : new String[]{null, "", "  ", "采集浅层铁矿", "查看村民交易报价"}) {
            assertEquals("", RddBasePlaybook.block(task));
            String text = userContent(request(task, ContextBudget.unlimited()));
            assertEquals(SAFETY, text);
            assertFalse(text.contains("base_playbook"));
        }
    }

    @Test
    void tightBudgetDropsWholeOptionalPlaybookButKeepsMandatorySafetyInProviderJson() {
        var sections = sections("安家");
        var tight = new ContextBudget(0, 0, 0, 0L, SAFETY.length(), 0L, "char-bound-fixture");
        var allocation = ContextBudget.allocate(tight, sections);
        assertFalse(allocation.denied());
        assertEquals(List.of(RUNTIME), allocation.keptNames());
        assertEquals(List.of(PLAYBOOK), allocation.droppedNames());
        String text = userContent(serialize(allocation));
        assertEquals(SAFETY, text);
        assertFalse(text.contains("base_playbook"));
        var roomy = ContextBudget.allocate(ContextBudget.unlimited(), sections);
        assertEquals(List.of(RUNTIME, PLAYBOOK), roomy.keptNames());
        assertEquals(SAFETY + "\n" + RddBasePlaybook.block("安家"), userContent(serialize(roomy)),
                "the provider must receive the complete production block, not a hand-built substitute");
    }

    private static List<ContextBudget.Section> sections(String task) {
        List<ContextBudget.Section> sections = new ArrayList<>();
        // Only maxChars is bounded: no alternate token estimator is introduced here.
        sections.add(new ContextBudget.Section(RUNTIME, SAFETY, true, 0, 100));
        String playbook = RddBasePlaybook.block(task);
        if (!playbook.isEmpty()) sections.add(new ContextBudget.Section(PLAYBOOK, playbook, false, 0, 5));
        return sections;
    }

    private static JsonObject request(String task, ContextBudget budget) {
        var allocation = ContextBudget.allocate(budget, sections(task));
        assertFalse(allocation.denied());
        return serialize(allocation);
    }

    private static JsonObject serialize(ContextBudget.Allocation allocation) {
        String text = allocation.kept().stream().map(ContextBudget.Section::text)
                .collect(Collectors.joining("\n"));
        var provider = new OpenAIProvider();
        return provider.buildRequestBody("offline-fixture", "system",
                List.of(provider.buildUserMessage(text)), new JsonArray());
    }

    private static String userContent(JsonObject request) {
        for (var message : request.getAsJsonArray("messages")) {
            var object = message.getAsJsonObject();
            if ("user".equals(object.get("role").getAsString())) return object.get("content").getAsString();
        }
        throw new AssertionError("serialized request has no user message");
    }
}
