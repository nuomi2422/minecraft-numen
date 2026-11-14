package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.SubtaskSpec;
import com.dwinovo.numen.rdd.core.RddChainFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** RddDecomposer.parse 纯容错解析的单测（不触 LLM/网络/MC）。 */
class RddDecomposerParseTest {
    @Test
    @SuppressWarnings("unchecked")
    void maturitySchemaAndParserPreservePropertiesWithoutChangingLegacy() {
        var schema = RddDecomposer.decomposeSchema();
        var root = (java.util.Map<String,Object>) schema.get("properties");
        var subs = (java.util.Map<String,Object>) root.get("subtasks");
        var item = (java.util.Map<String,Object>) subs.get("items");
        var fields = (java.util.Map<String,Object>) item.get("properties");
        var condition = (java.util.Map<String,Object>) fields.get("condition");
        var props = (java.util.Map<String,Object>) condition.get("properties");
        assertTrue(props.containsKey("block"));
        assertTrue(props.containsKey("radius"));
        var state = (java.util.Map<String,Object>) props.get("properties");
        assertEquals("object", state.get("type"));
        assertEquals(java.util.Map.of("type", "string"), state.get("additionalProperties"));
        String prefix = "{\"subtasks\":[{\"description\":\"observe maturity\",\"condition\":{\"type\":\"block_nearby\",\"block\":\"minecraft:wheat\",\"radius\":1";
        var mature = RddDecomposer.parse(prefix + ",\"properties\":{\"age\":\"7\"}}}]}");
        assertEquals(1, mature.size());
        assertEquals(java.util.Map.of("age", "7"), mature.get(0).condition().get("properties"));
        assertTrue(RddDecomposer.parse(prefix + ",\"properties\":{\"age\":7}}]}").isEmpty());
        var old = RddDecomposer.parse(prefix + "}}]}");
        assertEquals(1, old.size());
        assertFalse(old.get(0).condition().containsKey("properties"));
    }

    @Test void parsesGroupWithoutInventingSingleItemRequirement() {
        var specs = RddDecomposer.parse("{\"subtasks\":[{\"description\":\"food supply\",\"condition\":{\"group\":\"food\",\"minimum\":16}}]}");
        assertEquals(1, specs.size());
        assertEquals("food", specs.getFirst().condition().get("group"));
        assertFalse(specs.getFirst().condition().containsKey("asset_key"));
        assertDoesNotThrow(() -> com.dwinovo.numen.rdd.core.RddChainFactory.fromSpec(java.util.UUID.randomUUID(), "food", specs));
    }
    @Test void equipmentConditionsKeepTheirFieldsThroughParsingAndChainFactory() {
        String json = """
                {"subtasks":[{"description":"equip chestplate","condition":{"type":"equipment",
                 "item":"minecraft:iron_chestplate","slot":"chest","minimum":1,"dimension":"minecraft:overworld"}}]}
                """;
        var specs = RddDecomposer.parse(json);
        assertEquals(1, specs.size());
        assertEquals("minecraft:iron_chestplate", specs.getFirst().condition().get("item"));
        assertEquals("chest", specs.getFirst().condition().get("slot"));
        assertEquals(1, specs.getFirst().condition().get("minimum"));
        assertEquals("minecraft:overworld", specs.getFirst().condition().get("dimension"));
        var goal = RddChainFactory.fromSpec(java.util.UUID.randomUUID(), "equip", specs);
        assertEquals(specs.getFirst().condition(), goal.primaryGoals().getFirst().subtasks().getFirst().condition());
    }

    @Test void equipmentNullAndUnsupportedModeAreNotSilentlyConverted() {
        assertTrue(RddDecomposer.parse("{\"subtasks\":[{\"description\":\"equip\",\"condition\":{\"type\":\"equipment\",\"item\":\"minecraft:iron_boots\",\"slot\":null}}]}").isEmpty());
        assertTrue(RddDecomposer.parse("{\"subtasks\":[{\"description\":\"equip\",\"condition\":{\"type\":\"equipment\",\"item\":\"minecraft:iron_boots\",\"mode\":\"acquire\"}}]}").isEmpty());
    }

    @Test void parsesWorldEvidenceAndRejectsFractionalKillThreshold() {
        assertEquals(1, RddDecomposer.parse("{\"subtasks\":[{\"description\":\"base\",\"condition\":{\"type\":\"base\"}}]}").size());
        assertTrue(RddDecomposer.parse("{\"subtasks\":[{\"description\":\"dragon\",\"condition\":{\"type\":\"entity_killed\",\"entity\":\"minecraft:ender_dragon\",\"minimum\":1.5}}]}").isEmpty());
    }

    @Test void parsesValidSubtasks_body被忽略() {
        // 2026-10-07 用户裁决（单驾驶员）：规划器不给身体动作，body 一律忽略。
        // 就算模型/旧链塞了 body，parse 出来也必须是 null。
        String json = """
                {"subtasks":[
                  {"description":"�ռ� 5 ����ľԭľ","condition":{"asset_key":"minecraft:oak_log","minimum":5},
                   "body":{"task_type":"collect_items","args":{"item":"minecraft:oak_log","count":5}}},
                  {"description":"�� 3 ��ʯͷ","condition":{"asset_key":"minecraft:stone","minimum":3}}
                ]}""";
        List<SubtaskSpec> specs = RddDecomposer.parse(json);
        assertEquals(2, specs.size());
        assertEquals(5, specs.get(0).condition().get("minimum"));
        assertEquals("minecraft:oak_log", specs.get(0).condition().get("asset_key"));
        assertNull(specs.get(0).body(), "body 必须被忽略（单驾驶员）");
        assertNull(specs.get(1).body());
        assertEquals(3, specs.get(1).condition().get("minimum"));
    }

    @Test void malformedOrEmptyYieldsEmpty() {
        assertTrue(RddDecomposer.parse("not json {{{").isEmpty());
        assertTrue(RddDecomposer.parse("").isEmpty());
        assertTrue(RddDecomposer.parse("  ").isEmpty());
        assertTrue(RddDecomposer.parse(null).isEmpty());
        assertTrue(RddDecomposer.parse("{}").isEmpty());
        assertTrue(RddDecomposer.parse("{\"subtasks\":[]}").isEmpty());
        assertTrue(RddDecomposer.parse("{\"other\":1}").isEmpty());
    }

    @Test void dropsUnusableSubtasksKeepsGoodOnes() {
        String json = """
                {"subtasks":[
                  {"description":"ok","condition":{"asset_key":"minecraft:oak_log","minimum":1}},
                  {"description":"bad no condition"},
                  {"description":"bad blank key","condition":{"asset_key":"  ","minimum":1}},
                  {"description":"bad negative","condition":{"asset_key":"a","minimum":-2}}
                ]}""";
        List<SubtaskSpec> specs = RddDecomposer.parse(json);
        assertEquals(1, specs.size());
        assertEquals("ok", specs.get(0).description());
    }

    @Test void dropsNonNamespacedAssetKeys() {
        // "合法 JSON 但结构不可执行"必须被识别为失败：裸键/占位goal/大写 永不匹配背包键 -> 丢弃
        String json = """
                {"subtasks":[
                  {"description":"ok","condition":{"asset_key":"minecraft:oak_log","minimum":1}},
                  {"description":"bare key","condition":{"asset_key":"iron_ingot","minimum":1}},
                  {"description":"placeholder","condition":{"asset_key":"goal","minimum":1}},
                  {"description":"case wrong","condition":{"asset_key":"minecraft:Diamond","minimum":1}}
                ]}""";
        List<SubtaskSpec> specs = RddDecomposer.parse(json);
        assertEquals(1, specs.size());
        assertEquals("ok", specs.get(0).description());
        assertEquals("minecraft:oak_log", specs.get(0).condition().get("asset_key"));
    }

    // Original V22 new-rdd L58; rid 9f04f1d9-5d82-4f1f-88e9-1c3fce9eb43d / call_b510429a428c47dea6d26514.
    private static final String V22_ARGUMENTS = "{\"subtasks\":[{\"description\":\"补回最基础的工具链起点：就近砍树取原木，起步 4 个原木（够工作台+木镐+木棍），周围树多就顺手多砍几个为火把木棍备料。背包现在 0 件，不砍树谈不上任何后续。\",\"condition\":{\"type\":\"inventory\",\"group\":\"wood\",\"minimum\":4},\"node_type\":\"MAIN\"},{\"description\":\"把库存的木板/木棍做够：木板 ≥6（工作台 3 用 + 木镐 3 用，2x2 手搓），木棍 ≥8（木镐 2 + 石镐 2 + 石剑 1 + 石斧 2 + 火把备用）。已有就不重复做。\",\"condition\":{\"type\":\"inventory\",\"asset_key\":\"minecraft:stick\",\"minimum\":8},\"node_type\":\"MAIN\"},{\"description\":\"在安全处放工作台，合成木镐（3 木板 + 2 木棍）。没有木镐挖石头不掉落。\",\"condition\":{\"type\":\"item_crafted\",\"item\":\"minecraft:wooden_pickaxe\",\"minimum\":1},\"node_type\":\"MAIN\"},{\"description\":\"用木镐就近挖石头，起步 11 个圆石（石镐 3 + 石剑 1 + 石斧 3 + 熔炉 8 合起来算，一次挖够别跑两趟；现场石头多就整到 20 个以上）。\",\"condition\":{\"type\":\"block_mined\",\"block\":\"minecraft:stone\",\"minimum\":11},\"node_type\":\"MAIN\"},{\"description\":\"用圆石合成整套石器 + 熔炉：石镐（3 圆石+2 木棍）、石斧（3 圆石+2 木棍）、石剑（2 圆石+1 木棍）、熔炉（8 圆石围成 3x3 空心，手搓）。这是本轮下界/下矿的最低工具门。\",\"condition\":{\"type\":\"item_crafted\",\"item\":\"minecraft:stone_pickaxe\",\"minimum\":1},\"node_type\":\"MAIN\"},{\"description\":\"照明回补：用熔炉烧原木制成木炭（1 原木+任意燃料=1 木炭），再用木炭+木棍合成 ≥8 支火把（1 木炭 + 1 木棍 = 4 支）。没有火把既下不了矿也守不住下界。\",\"condition\":{\"type\":\"item_crafted\",\"item\":\"minecraft:torch\",\"minimum\":8},\"node_type\":\"MAIN\"},{\"description\":\"食物回补：就地打猎（牛/猪/鸡）或捡掉落，烤熟后随身带 ≥4 个熟肉。背包空着进下界等于送死，这条不补满不进烈焰人阶段。\",\"condition\":{\"type\":\"inventory\",\"group\":\"food\",\"minimum\":4},\"node_type\":\"MAIN\"},{\"description\":\"进下界前的最低铁料：用石镐挖浅层铁矿，起步 3 块粗铁（做 1 个打火石备用 + 水桶/铁料起步；已确认有 flint_and_steel 就不再额外点火石，只求铁料够穿甲）。不深挖死磕，一处挖空就换地方。\",\"condition\":{\"type\":\"inventory\",\"asset_key\":\"minecraft:raw_iron\",\"minimum\":3},\"node_type\":\"MAIN\"},{\"description\":\"用熔炉把粗铁烧成铁锭，先做铁剑 + 铁头盔（各需 2 锭和 5 锭则视量力而为；起步验收到 3 个铁锭即可）。有铁才敢在堡垒里和烈焰人换血。\",\"condition\":{\"type\":\"inventory\",\"asset_key\":\"minecraft:iron_ingot\",\"minimum\":3},\"node_type\":\"MAIN\"},{\"description\":\"确认装备就位：主手铁剑或石剑、身上有任意护甲件——这是进下界的最低门槛，不满足就别跳进传送门。\",\"condition\":{\"type\":\"equipment\",\"item\":\"minecraft:iron_sword\",\"slot\":\"mainhand\",\"optional\":true},\"node_type\":\"MAIN\"},{\"description\":\"确认身处下界（走进已点亮的下界传送门）。站在主世界同名坐标不算数，判据带 dimension。\",\"condition\":{\"type\":\"dimension\",\"dimension\":\"minecraft:the_nether\"},\"node_type\":\"MAIN\"},{\"description\":\"在下界找下界要塞：locate_structure 找 minecraft:fortress，分不超过 400 格一段接近，每 300 格 get_self_status 校准坐标；到要塞内即达成。路上远离熔岩、不跳水桶、不放床。\",\"condition\":{\"type\":\"structure\",\"structure\":\"minecraft:fortress\",\"dimension\":\"minecraft:the_nether\"},\"node_type\":\"MAIN\"},{\"description\":\"在要塞内击杀烈焰人拿烈焰棒，起步 1 根算推进（末影之眼每颗要 1 份烈焰粉 = 1 根烈焰棒做的粉够 2 颗；12 颗眼需 6 根棒，看清刷怪量再决定打到几根）。站远处用剑砍、别贴脸群殴。\",\"condition\":{\"type\":\"entity_killed\",\"entity\":\"minecraft:blaze\",\"minimum\":1},\"node_type\":\"MAIN\"},{\"description\":\"收集末影珍珠：在下界或回主世界对末影人作战，起步 1 颗算推进（12 颗眼需 12 颗珍珠，可在主世界夜晚旷野更安全地补）。已有珍珠不用重刷。\",\"condition\":{\"type\":\"inventory\",\"asset_key\":\"minecraft:ender_pearl\",\"minimum\":1},\"node_type\":\"MAIN\"},{\"description\":\"带着烈焰棒回主世界：站进已点亮的下界传送门返回（维度判据必须带 dimension，否则会在下界同名坐标上找人）。\",\"condition\":{\"type\":\"dimension\",\"dimension\":\"minecraft:overworld\"},\"node_type\":\"MAIN\"}]}";

    @Test void v22OriginalFifteenStepsReachRealEmptyFallbackInsteadOfInstallingPrefix() {
        var source = com.google.gson.JsonParser.parseString(V22_ARGUMENTS).getAsJsonObject().getAsJsonArray("subtasks");
        assertEquals(15, source.size());
        assertTrue(source.get(6).getAsJsonObject().get("description").getAsString().startsWith("食物回补"));
        assertTrue(source.get(8).getAsJsonObject().get("description").getAsString().contains("铁剑 + 铁头盔"));
        var specs = RddDecomposer.parse(V22_ARGUMENTS);
        var guarded = RddPlanGuard.filterSubtasks(RddPlanGuard.normalizeConditions(specs), "下界取烈焰棒与末影珍珠", java.util.Map.of());
        assertRealEmptyFallback(guarded.allowed());
    }

    @Test void exactlyEightLegalStepsRemainCompleteAndReachActualInstallation() {
        var raw = legalSteps(8);
        var parsed = RddDecomposer.parse(raw.toString());
        assertEquals(8, parsed.size(), "literal eight pins the existing bounded contract");
        assertEquals("legal-0", parsed.getFirst().description());
        assertEquals("legal-7", parsed.getLast().description());
        var guarded = RddPlanGuard.filterSubtasks(parsed, "通关MC", java.util.Map.of());
        var chain = unexpandedChain();
        var subs = RddGoalDriver.buildInjection("overflow-p", 1, guarded.allowed());
        RddGoalDriver.acceptPlan(subs, ignored -> {}, () -> chain.expandCurrentPrimary(subs));
        chain.startCurrent();
        assertEquals(8, chain.currentPrimary().subtasks().size());
        assertEquals("legal-0", chain.currentSubtask().description());
        assertEquals(parsed.getLast().condition(), chain.currentPrimary().subtasks().getLast().condition());
        assertTrue(chain.satisfiedStages().isEmpty());
    }

    @Test void invalidEntriesDoNotConsumeCapacityButTheNinthLegalStepStillRejects() {
        var raw = legalSteps(8);
        var steps = raw.getAsJsonArray("subtasks");
        steps.add("not a subtask");
        var missing = new com.google.gson.JsonObject(); missing.addProperty("description", "no condition"); steps.add(missing);
        steps.add(com.google.gson.JsonParser.parseString("{\"description\":\"bad key\",\"condition\":{\"asset_key\":\"goal\",\"minimum\":1}}"));
        assertEquals(8, RddDecomposer.parse(raw.toString()).size(), "eleven raw rows still contain only eight legal steps");
        steps.add(legalSteps(1).getAsJsonArray("subtasks").get(0));
        assertRealEmptyFallback(RddPlanGuard.filterSubtasks(RddDecomposer.parse(raw.toString()), "通关MC", java.util.Map.of()).allowed());
    }

    /** Calls the actual shared application consumer, not a test-owned if-nonempty callback. */
    private static void assertRealEmptyFallback(List<SubtaskSpec> specs) {
        java.util.UUID id = java.util.UUID.randomUUID(); Object token = new Object();
        var chain = unexpandedChain();
        chain.expandCurrentPrimary(List.of(com.dwinovo.numen.rdd.api.Subtask.hardCoded("old-step", "保留原计划规格", java.util.Map.of("group", "food", "minimum", 4))));
        chain.startCurrent();
        var oldPrimary = chain.currentPrimary(); int oldRevision = chain.planRevision();
        try {
            assertTrue(RddReplanTransition.requestSurvival(id, chain, RddSurvivalGate.Decision.SEEK_FOOD_FIRST,
                    "original food recovery", new java.util.HashMap<>(), "bounded-budget", 1, () -> {}, token).accepted());
            assertEquals(RddReplanTransition.SurvivalApplyOutcome.EMPTY_FALLBACK,
                    RddReplanTransition.applySurvivalPlan(id, token, chain, specs,
                            () -> { throw new AssertionError("incomplete prefix must not invoke replacement"); },
                            ignored -> fail("no applied plan may be published")));
            assertEquals(oldPrimary, chain.currentPrimary(), "original specs/pid remain, not a prefix replacement");
            assertEquals(oldRevision, chain.planRevision());
            assertEquals(com.dwinovo.numen.rdd.api.PrimaryGoalStatus.PENDING, chain.primaryStatus(),
                    "existing fallback resets MAIN to PENDING; it is not zero state change");
            assertEquals("old-step", chain.currentSubtask().id());
            assertTrue(chain.satisfiedStages().isEmpty(), "not completed and no primary advance");
            assertFalse(RddReplanTransition.hasSurvivalIntent(id));
        } finally { RddReplanTransition.invalidate(id); }
    }

    private static com.dwinovo.numen.rdd.core.TaskChain unexpandedChain() {
        return new com.dwinovo.numen.rdd.core.TaskChain(new com.dwinovo.numen.rdd.api.Goal("overflow-g", "通关MC", List.of(
                com.dwinovo.numen.rdd.api.PrimaryGoal.unexpanded("overflow-p", "当前目标", List.of()),
                com.dwinovo.numen.rdd.api.PrimaryGoal.unexpanded("next-p", "后续目标", List.of()))));
    }

    private static com.google.gson.JsonObject legalSteps(int count) {
        var root = new com.google.gson.JsonObject(); var steps = new com.google.gson.JsonArray();
        for (int i = 0; i < count; i++) {
            var step = new com.google.gson.JsonObject(); step.addProperty("description", "legal-" + i);
            var condition = new com.google.gson.JsonObject(); condition.addProperty("asset_key", "minecraft:oak_log"); condition.addProperty("minimum", i + 1);
            step.add("condition", condition); steps.add(step);
        }
        root.add("subtasks", steps); return root;
    }

    @Test void rejectsPlansOverMaxSubtasksWithoutReturningPrefix() {
        StringBuilder sb = new StringBuilder("{\"subtasks\":[");
        for (int i = 0; i < 20; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"description\":\"s").append(i).append('"')
                    .append(",\"condition\":{\"asset_key\":\"minecraft:oak_log\",\"minimum\":1}}");
        }
        sb.append("]}");
        List<SubtaskSpec> specs = RddDecomposer.parse(sb.toString());
        // Intentional contract replacement of capsAtMaxSubtasks: do not publish a truncated plan.
        assertTrue(specs.isEmpty(), "the ninth legal step invalidates the whole generated batch");
    }
}
