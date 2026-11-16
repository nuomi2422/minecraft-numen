package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.PrimarySpec;
import com.dwinovo.numen.rdd.core.RddChainFactory;
import com.dwinovo.numen.rdd.core.TaskChain;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** RddStagePlanner.parse 纯容错解析单测（Stage-A 一级清单，批B-2；不触 LLM/网络/MC）。 */
class RddStagePlannerParseTest {

    @Test void extractedDecoderAndLegacyEntryKeepTheSameContract() {
        Gson gson = new Gson();
        for (String input : java.util.Arrays.asList(null, "", "not-json", "null", "{}",
                gson.toJson(Map.of("stages", List.of())))) {
            assertEquals(gson.toJson(RddStagePlanParser.parse(input)),
                    gson.toJson(RddStagePlanner.parse(input)));
        }
        String valid = "{\"stages\":[{\"theme\":\" collect stone \"}]}";
        var direct = RddStagePlanParser.parse(valid);
        assertEquals(1, direct.size());
        assertEquals("collect stone", direct.get(0).description());
        assertEquals(gson.toJson(direct), gson.toJson(RddStagePlanner.parse(valid)));
    }

    @Test void staleRationaleIsNotPresentedAsCurrent() {
        assertTrue(RddStagePlanner.rationaleApplicable(true, 7L, 7L), "same revision is current");
        assertFalse(RddStagePlanner.rationaleApplicable(true, 6L, 7L), "older revision is stale");
        assertFalse(RddStagePlanner.rationaleApplicable(true, 8L, 7L), "future revision is not trusted either");
        assertFalse(RddStagePlanner.rationaleApplicable(false, 7L, 7L), "no entry means no rationale");
    }

    @Test void planningRequestCarriesRationaleWhenPresent() {
        String render = "<rationale><why>钻石镐可直接挖黑曜石</why><risks>下界风险</risks></rationale>";
        String with = RddStagePlanner.withRationale("base", render);
        assertTrue(with.startsWith("base"), "original prompt must stay intact");
        assertTrue(with.contains(render), "planner must receive the exact rationale section");
        assertEquals("base", RddStagePlanner.withRationale("base", ""), "absent rationale adds nothing");
        assertEquals("base", RddStagePlanner.withRationale("base", null));
        assertEquals("", RddStagePlanner.withRationale(null, render));
    }

    @Test void parsesStagesWithOptionalWaitFor() {
        String json = """
                {"stages":[
                  {"theme":"石器时代：做石斧石镐备基础材料"},
                  {"theme":"铁器时代：建熔炉炼铁做铁器",
                   "wait_for":[{"asset_key":"minecraft:stone_pickaxe","minimum":1}]}
                ]}""";
        List<PrimarySpec> stages = RddStagePlanner.parse(json);
        assertEquals(2, stages.size());
        assertEquals("石器时代：做石斧石镐备基础材料", stages.get(0).description());
        assertTrue(stages.get(0).waitFor().isEmpty());
        assertEquals(1, stages.get(1).waitFor().size());
        assertEquals("minecraft:stone_pickaxe", stages.get(1).waitFor().get(0).assetKey());
        assertEquals(1, stages.get(1).waitFor().get(0).minimum());
    }

    @Test void dropsUnusableWaitForKeepsStage() {
        // 裸键(永不匹配背包)、大写、负 minimum ->整条门丢弃，但阶段保留（门是增强不是必须）
        String json = """
                {"stages":[
                  {"theme":"石器时代",
                   "wait_for":[{"asset_key":"stone_pickaxe","minimum":1},
                               {"asset_key":"minecraft:Diamond","minimum":1}]},
                  {"theme":"铁器时代",
                   "wait_for":[{"asset_key":"minecraft:iron_ingot","minimum":0}]}
                ]}""";
        List<PrimarySpec> stages = RddStagePlanner.parse(json);
        assertEquals(2, stages.size());
        assertTrue(stages.get(0).waitFor().isEmpty(), "bare/case-wrong keys are unusable ->dropped");
        assertTrue(stages.get(1).waitFor().isEmpty(), "minimum<=0 is not 'already holds' ->dropped");
    }

    @Test void keepsStageWithSomeGoodWaitFor() {
        String json = """
                {"stages":[{"theme":"下界",
                   "wait_for":[{"asset_key":"flint_and_steel","minimum":1},
                               {"asset_key":"minecraft:obsidian","minimum":10}]}]}""";
        List<PrimarySpec> stages = RddStagePlanner.parse(json);
        assertEquals(1, stages.size());
        assertEquals(1, stages.get(0).waitFor().size());
        assertEquals("minecraft:obsidian", stages.get(0).waitFor().get(0).assetKey());
        assertEquals(10, stages.get(0).waitFor().get(0).minimum());
    }

    @Test void dropsThemeLessStagesAndEmptyOrBadInput() {
        assertTrue(RddStagePlanner.parse(null).isEmpty());
        assertTrue(RddStagePlanner.parse("").isEmpty());
        assertTrue(RddStagePlanner.parse("not json {{{").isEmpty());
        assertTrue(RddStagePlanner.parse("{}").isEmpty());
        assertTrue(RddStagePlanner.parse("{\"stages\":[]}").isEmpty());
        String badThemes = """
                {"stages":[
                  {"theme":"  "},
                  {"wait_for":[{"asset_key":"minecraft:stone","minimum":1}]}
                ]}""";
        assertTrue(RddStagePlanner.parse(badThemes).isEmpty());
    }

    @Test void capsAtMaxStages() {
        StringBuilder sb = new StringBuilder("{\"stages\":[");
        // ★ 17 与 12 都是字面量（契约：一级上限 12 条 / 灌 17 条逼出封顶），刻意不用 MAX_STAGES。
        //   变异测试实测：写成常量时上限改了它跟着改，循环与断言一起漂，测试仍绿。
        //   详见 RddRedlineContractPinTest。
        for (int i = 0; i < 17; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"theme\":\"阶段").append(i).append("\"}");
        }
        sb.append("]}");
        List<PrimarySpec> stages = RddStagePlanner.parse(sb.toString());
        assertEquals(12, stages.size());
        assertEquals("阶段0", stages.get(0).description());
    }

    @Test void equipmentTargetSurvivesRiskPoliciesWithoutBecomingAnInventoryGate() {
        String armor = """
                {"feet":"#minecraft:boots","legs":"minecraft:iron_leggings",
                 "chest":"minecraft:iron_chestplate","head":"minecraft:iron_helmet"}""";
        JsonObject expected = JsonParser.parseString(armor).getAsJsonObject();
        List<PrimarySpec> parsed = RddStagePlanner.parse("""
                {"stages":[
                  {"theme":"收小麦做面包","equipment_target":%s,
                   "wait_for":[{"asset_key":"minecraft:bread","minimum":4},
                               {"asset_key":"minecraft:stone_pickaxe","minimum":1}]},
                  {"theme":"下矿挖铁","equipment_target":%s,
                   "wait_for":[{"asset_key":"minecraft:cobblestone","minimum":2}]},
                  {"theme":"穿好铁靴、铁护腿、铁胸甲、铁头盔"}
                ]}""".formatted(armor, armor));
        List<PrimarySpec> prepared = RddRiskPlanning.ensureSmeltPrepStage(parsed, Map.of());
        prepared = RddRiskPlanning.ensurePrepStage(prepared, RddChainFactory.MAX_STAGES);
        List<PrimarySpec> planned = RddRiskPlanning.injectWaitFor(prepared, Map.of());
        assertEquals(4, planned.size(), "the existing preparation insertion still runs");
        assertEquals(RddRiskPlanning.PREP_STAGE_DESC, planned.get(1).description());
        assertNull(planned.get(1).equipmentTarget(), "an inserted preparation stage must not inherit a target");
        assertNull(planned.get(3).equipmentTarget(), "legacy Chinese themes must not invent a target");
        assertEquals(1, planned.get(0).waitFor().size(), "self-produced bread is still pruned");
        assertEquals("minecraft:stone_pickaxe", planned.get(0).waitFor().get(0).assetKey());
        assertEquals(3, planned.get(2).waitFor().size(), "mining adds only the existing torch/bread gates");
        assertTrue(planned.get(2).waitFor().stream().anyMatch(r ->
                r.assetKey().equals("minecraft:cobblestone") && r.minimum() == 2));
        JsonObject goal = new Gson().toJsonTree(RddChainFactory.fromStages(
                UUID.fromString("04200000-0000-0000-0000-000000000007"), "继续探索", planned)).getAsJsonObject();
        var primaries = goal.getAsJsonArray("primaryGoals");
        for (int i : List.of(0, 2)) {
            assertEquals(expected, primaries.get(i).getAsJsonObject().get("equipmentTarget"),
                    "both risk-policy rebuilds must preserve all slots, including #tag");
            assertEquals(new Gson().toJsonTree(planned.get(i).waitFor()),
                    primaries.get(i).getAsJsonObject().get("waitFor"));
        }
        for (String bad : List.of("{}", armor.replace("#minecraft:boots", "boots"),
                armor.replace("\"#minecraft:boots\"", "1"),
                armor.replace("\"#minecraft:boots\"", "null"),
                armor.replace("\"feet\"", "\"boots\""),
                armor.replace("}", ",\"offhand\":\"minecraft:shield\"}"))) {
            assertTrue(RddStagePlanner.parse("""
                    {"stages":[{"theme":"合法前缀"},{"theme":"后续阶段","equipment_target":%s}]}
                    """.formatted(bad)).isEmpty(), "explicit invalid target rejects the whole batch: " + bad);
        }
    }

    @Test void explicitFourSlotStageTargetSurvivesFactoryAndRestart() {
        String armor = """
                {"feet":"minecraft:iron_boots","legs":"minecraft:iron_leggings",
                 "chest":"minecraft:iron_chestplate","head":"minecraft:iron_helmet"}""";
        Gson gson = new Gson();
        JsonObject expectedTarget = JsonParser.parseString(armor).getAsJsonObject();
        List<PrimarySpec> stages = RddStagePlanner.parse("""
                {"stages":[
                  {"theme":"探索下一处安全地点","equipment_target":%s},
                  {"theme":"建立补给点","equipment_target":%s,
                   "wait_for":[{"asset_key":"minecraft:stone_pickaxe","minimum":1}]},
                  {"theme":"穿好铁靴、铁护腿、铁胸甲、铁头盔"}
                ]}""".formatted(armor, armor));
        assertEquals(3, stages.size());
        assertTrue(stages.get(0).waitFor().isEmpty(), "equipment is an obligation, not an inventory prerequisite");
        assertEquals(1, stages.get(1).waitFor().size());
        assertEquals("minecraft:stone_pickaxe", stages.get(1).waitFor().get(0).assetKey());
        assertEquals(1, stages.get(1).waitFor().get(0).minimum());
        assertTrue(stages.get(2).waitFor().isEmpty());
        for (int i = 0; i < 2; i++) {
            JsonObject stage = gson.toJsonTree(stages.get(i)).getAsJsonObject();
            assertEquals(expectedTarget, stage.get("equipmentTarget"),
                    "explicit four-slot obligation must survive Stage-A even without equipment words in theme");
        }
        JsonElement legacyTarget = gson.toJsonTree(stages.get(2)).getAsJsonObject().get("equipmentTarget");
        assertTrue(legacyTarget == null || legacyTarget.isJsonNull()
                        || legacyTarget.isJsonObject() && legacyTarget.getAsJsonObject().size() == 0,
                "legacy theme alone must not invent a typed equipment obligation");

        TaskChain chain = new TaskChain(RddChainFactory.fromStages(
                UUID.fromString("04200000-0000-0000-0000-000000000006"), "继续探索", stages));
        String saved = chain.toJson();
        TaskChain restored = TaskChain.fromJson(saved);
        for (String snapshot : List.of(saved, restored.toJson())) {
            var primaries = JsonParser.parseString(snapshot).getAsJsonObject()
                    .getAsJsonObject("goal").getAsJsonArray("primaryGoals");
            assertEquals(3, primaries.size());
            for (int i = 0; i < 2; i++) {
                assertEquals(expectedTarget, primaries.get(i).getAsJsonObject().get("equipmentTarget"),
                        "factory and restart must retain all four explicit equipment slots");
            }
            assertEquals(0, primaries.get(0).getAsJsonObject().getAsJsonArray("waitFor").size(),
                    "equipment_target must not be converted into a waitFor inventory gate");
            var waitFor = primaries.get(1).getAsJsonObject().getAsJsonArray("waitFor");
            assertEquals(1, waitFor.size(), "only the separately declared inventory prerequisite belongs in waitFor");
            assertEquals("minecraft:stone_pickaxe", waitFor.get(0).getAsJsonObject().get("assetKey").getAsString());
            assertEquals(1, waitFor.get(0).getAsJsonObject().get("minimum").getAsInt());
            JsonElement restoredLegacyTarget = primaries.get(2).getAsJsonObject().get("equipmentTarget");
            assertTrue(restoredLegacyTarget == null || restoredLegacyTarget.isJsonNull()
                            || restoredLegacyTarget.isJsonObject() && restoredLegacyTarget.getAsJsonObject().size() == 0,
                    "factory and restart must not infer equipment from a legacy Chinese theme");
            assertEquals(0, primaries.get(2).getAsJsonObject().getAsJsonArray("waitFor").size());
        }
    }

    @Test void malformedExplicitEquipmentTargetRejectsWholeStageBatch() {
        String armor = """
                {"feet":"minecraft:iron_boots","legs":"minecraft:iron_leggings",
                 "chest":"minecraft:iron_chestplate","head":"minecraft:iron_helmet"}""";
        List<String> badTargets = List.of(
                """
                {"feet":"minecraft:iron_boots"}""",
                """
                ["minecraft:iron_boots","minecraft:iron_leggings","minecraft:iron_chestplate","minecraft:iron_helmet"]""",
                "null",
                """
                {"minecraft:iron_boots":1,"minecraft:iron_leggings":1,
                 "minecraft:iron_chestplate":1,"minecraft:iron_helmet":1}""");
        for (String badTarget : badTargets) {
            String json = """
                    {"stages":[
                      {"theme":"合法前缀阶段","equipment_target":%s},
                      {"theme":"后续阶段","equipment_target":%s}
                    ]}""".formatted(armor, badTarget);
            assertTrue(RddStagePlanner.parse(json).isEmpty(),
                    "malformed explicit target must reject the entire batch, not drop the field/stage or return a prefix: "
                            + badTarget);
        }
    }
}
