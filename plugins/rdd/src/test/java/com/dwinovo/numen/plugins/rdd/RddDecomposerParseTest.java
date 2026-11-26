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

    @Test
    @SuppressWarnings("unchecked")
    void equipmentSlotsSurviveSchemaParsingNormalizationInstallationAndRestore() {
        var root = (java.util.Map<String, Object>) RddDecomposer.decomposeSchema().get("properties");
        var subtasks = (java.util.Map<String, Object>) root.get("subtasks");
        var item = (java.util.Map<String, Object>) subtasks.get("items");
        var fields = (java.util.Map<String, Object>) item.get("properties");
        var condition = (java.util.Map<String, Object>) fields.get("condition");
        var props = (java.util.Map<String, Object>) condition.get("properties");
        var slots = (java.util.Map<String, Object>) props.get("slots");
        var slotNames = java.util.Set.of("feet", "legs", "chest", "head");
        assertEquals("object", slots.get("type"));
        assertEquals(false, slots.get("additionalProperties"));
        assertEquals(slotNames, java.util.Set.copyOf((List<String>) slots.get("required")));
        var slotProps = (java.util.Map<String, Object>) slots.get("properties");
        assertEquals(slotNames, slotProps.keySet());
        slotProps.values().forEach(value -> assertEquals(java.util.Map.of("type", "string"), value));

        var expectedSlots = java.util.Map.of("feet", "minecraft:iron_boots", "legs", "minecraft:iron_leggings",
                "chest", "minecraft:iron_chestplate", "head", "#minecraft:head_armor");
        String json = """
                {"subtasks":[{"description":"wear full armor","condition":{"type":"equipment",
                 "slots":{"feet":"minecraft:iron_boots","legs":"minecraft:iron_leggings",
                 "chest":"minecraft:iron_chestplate","head":"#minecraft:head_armor"},
                 "dimension":"minecraft:overworld","optional":false}},
                 {"description":"wear full armor","condition":{"type":"equipment","item":"minecraft:iron_helmet","slot":"head"}},
                 {"description":"legacy any","condition":{"type":"equipment","item":"minecraft:iron_helmet","slot":"any"}}]}
                """;
        var parsed = RddDecomposer.parse(json);
        assertEquals(3, parsed.size());
        assertEquals(java.util.Map.of("type", "equipment", "slots", expectedSlots,
                "dimension", "minecraft:overworld", "optional", false), parsed.getFirst().condition());
        var normalized = RddPlanGuard.normalizeConditions(parsed);
        assertEquals(parsed, normalized, "normalization must not drop the explicit four-slot contract");
        var goal = RddChainFactory.fromSpec(java.util.UUID.randomUUID(), "equipment", normalized);
        assertEquals(normalized.getFirst().condition(), goal.primaryGoals().getFirst().subtasks().getFirst().condition());

        var chain = unexpandedChain();
        var injected = RddGoalDriver.buildInjection(chain.currentPrimary().id(), 1, normalized);
        RddGoalDriver.acceptPlan(injected, ignored -> {}, () -> chain.expandCurrentPrimary(injected));
        chain.startCurrent();
        assertEquals(normalized.getFirst().condition(), chain.currentSubtask().condition());
        var restored = com.dwinovo.numen.rdd.core.TaskChain.fromJson(chain.toJson());
        assertEquals(normalized.getFirst().condition(), restored.currentSubtask().condition());
        assertEquals(expectedSlots, restored.currentSubtask().condition().get("slots"));
        assertTrue(restored.satisfiedStages().isEmpty(), "install/restore is not completion evidence");
        assertEquals(3, restored.currentPrimary().subtasks().size());
        for (int i = 1; i < 3; i++) {
            var legacy = restored.currentPrimary().subtasks().get(i).condition();
            assertEquals(parsed.get(i).condition(), legacy);
            assertFalse(legacy.containsKey("slots"), "legacy single-item intent must not become a full set");
        }
        var headOnly = restored.currentPrimary().subtasks().get(1).condition();
        assertTrue(com.dwinovo.numen.rdd.core.WorldFactConditions.equipmentSlotsSatisfied(
                headOnly, false, false, false, true, false, false));
        var any = restored.currentPrimary().subtasks().get(2).condition();
        assertTrue(com.dwinovo.numen.rdd.core.WorldFactConditions.equipmentSlotsSatisfied(
                any, false, false, false, false, true, false), "legacy any still includes the main hand");
    }

    @Test void invalidExplicitEquipmentSlotsRejectWholeBatchInsteadOfInstallingSevenStepPrefix() {
        String fullSlots = """
                {"feet":"minecraft:iron_boots","legs":"minecraft:iron_leggings",
                 "chest":"minecraft:iron_chestplate","head":"minecraft:iron_helmet"}
                """;
        var mixed = new com.google.gson.JsonObject();
        mixed.addProperty("type", "equipment");
        mixed.add("slots", com.google.gson.JsonParser.parseString(fullSlots));
        mixed.addProperty("item", "minecraft:iron_helmet");
        mixed.addProperty("slot", "head");
        mixed.addProperty("minimum", 1);
        var malformed = mixed.deepCopy();
        malformed.remove("item"); malformed.remove("slot"); malformed.remove("minimum");
        malformed.addProperty("slots", "not-an-object");
        var partial = malformed.deepCopy();
        partial.add("slots", com.google.gson.JsonParser.parseString("{\"head\":\"minecraft:iron_helmet\"}"));
        var inventory = partial.deepCopy();
        inventory.addProperty("type", "inventory");
        inventory.addProperty("asset_key", "minecraft:oak_log");
        inventory.addProperty("minimum", 1);
        var untyped = inventory.deepCopy();
        untyped.remove("type");
        for (var bad : List.of(malformed, mixed, partial, inventory, untyped)) {
            var raw = legalSteps(7);
            var tail = new com.google.gson.JsonObject();
            tail.addProperty("description", "explicit equipment requirement");
            tail.add("condition", bad);
            raw.getAsJsonArray("subtasks").add(tail);
            var parsed = RddDecomposer.parse(raw.toString());
            assertTrue(parsed.isEmpty(), () -> "bad explicit slots must reject all seven earlier steps: " + bad);
            var guarded = RddPlanGuard.filterSubtasks(RddPlanGuard.normalizeConditions(parsed), "通关MC", java.util.Map.of());
            assertRealEmptyFallback(guarded.allowed());
        }
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

    // Selected-profile consumer tests share the real send authorization seam; no live config/HTTP/MC.
    @Test void selectedKeyWorksWithoutGlobalConfigAndKeepsPlannerOnlyEffortOverride() {
        var f = new PlannerEndpointFixture();
        var selected = endpoint("openai", "FAKE_PROFILE_A");
        f.lookup = id -> java.util.concurrent.CompletableFuture.completedFuture(selected);
        f.ask(f.a, "stage_a");
        assertTrue(f.sent.isEmpty(), "endpoint completion must still enter the client queue");
        f.runClient();
        var actual = f.sent.getFirst();
        assertEquals(selected.provider(), actual.provider());
        assertEquals(selected.model(), actual.model());
        assertEquals(selected.apiKey(), actual.apiKey());
        assertEquals(selected.baseUrl(), actual.baseUrl());
        assertEquals(selected.proxy(), actual.proxy());
        assertEquals("low", actual.reasoningEffort());
        assertEquals("high", selected.reasoningEffort(), "do not mutate the selected execution profile");
        assertEquals(List.of("stage_a:0"), f.budgetChecks);
        assertEquals(0, f.failures);
    }

    @Test void companionAndProviderChangesDriveBothPlannerStagesWithoutBorrowing() {
        var f = new PlannerEndpointFixture();
        var profiles = new java.util.HashMap<java.util.UUID, com.dwinovo.numen.agent.llm.LlmEndpoint>();
        profiles.put(f.a, endpoint("openai", "FAKE_PROFILE_A"));
        profiles.put(f.b, endpoint("deepseek", "FAKE_PROFILE_B"));
        f.lookup = id -> java.util.concurrent.CompletableFuture.completedFuture(profiles.get(id));
        f.ask(f.a, "stage_a"); f.ask(f.b, "stage_b"); f.runClient();
        assertEquals("FAKE_PROFILE_A", f.sent.get(0).apiKey());
        assertEquals("low", f.sent.get(0).reasoningEffort());
        assertEquals("FAKE_PROFILE_B", f.sent.get(1).apiKey());
        assertEquals("off", f.sent.get(1).reasoningEffort());
        profiles.put(f.a, endpoint("deepseek", "FAKE_PROFILE_A_CHANGED"));
        f.ask(f.a, "fallback"); f.runClient();
        assertEquals("FAKE_PROFILE_A_CHANGED", f.sent.get(2).apiKey());
        assertEquals("off", f.sent.get(2).reasoningEffort());
        assertEquals(List.of(f.a, f.b, f.a), f.lookedUp);
        assertEquals(List.of("stage_a:0", "stage_b:0", "fallback:0"), f.budgetChecks);
    }

    @Test void absentDeletedAndIncompleteEndpointsFailBeforeBudgetOrNetwork() {
        for (String stage : List.of("stage_a", "stage_b", "fallback")) {
            var f = new PlannerEndpointFixture();
            f.lookup = id -> java.util.concurrent.CompletableFuture.completedFuture(null);
            f.ask(f.a, stage);
            f.lookup = id -> java.util.concurrent.CompletableFuture.completedFuture(endpoint("", ""));
            f.ask(f.a, stage);
            f.lookup = id -> java.util.concurrent.CompletableFuture.completedFuture(endpoint("openai", " "));
            f.ask(f.a, stage); f.runClient();
            assertTrue(f.sent.isEmpty()); assertTrue(f.budgetChecks.isEmpty());
            assertEquals(3, f.failures);
        }
    }

    @Test void originalStageBudgetRefusalStillPreventsEveryPlannerSend() {
        for (String stage : List.of("stage_a", "stage_b", "fallback")) {
            var f = new PlannerEndpointFixture(); f.allowed = false;
            f.ask(f.a, stage); f.runClient();
            assertEquals(List.of(stage + ":0"), f.budgetChecks);
            assertTrue(f.sent.isEmpty()); assertEquals(1, f.failures);
        }
    }

    @Test void lookupFailureAndQueuedWorldChangeInvokeOriginalFailureWithoutSending() {
        var failed = new PlannerEndpointFixture();
        failed.lookup = id -> java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("fixture lookup"));
        failed.ask(failed.a, "stage_b"); failed.runClient();
        assertEquals(1, failed.failures); assertTrue(failed.sent.isEmpty());
        var changed = new PlannerEndpointFixture();
        changed.ask(changed.a, "stage_a"); changed.generation.incrementAndGet(); changed.runClient();
        assertEquals(1, changed.failures); assertTrue(changed.sent.isEmpty());
        assertTrue(changed.budgetChecks.isEmpty());
    }

    @Test void failedEndpointFutureKeepsTheRealSinglePassPlaceholderAndObjective() {
        var f = new PlannerEndpointFixture();
        var goals = new java.util.ArrayList<com.dwinovo.numen.rdd.api.Goal>();
        f.lookup = id -> java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("fixture lookup"));
        f.ask(f.a, "fallback", () -> goals.add(RddDecomposer.fallbackGoal(f.a, "keep original objective")));
        f.runClient();
        assertTrue(f.sent.isEmpty()); assertEquals(1, goals.size());
        assertEquals("keep original objective", goals.getFirst().description());
        assertFalse(goals.getFirst().primaryGoals().isEmpty(), "failure must not swallow the goal");
    }

    @Test void synchronousLookupAndSendErrorsDoNotSkipFailureCallback() {
        var f = new PlannerEndpointFixture();
        f.lookup = id -> { throw new IllegalStateException("fixture resolver"); };
        f.ask(f.a, "stage_a"); f.runClient();
        assertEquals(1, f.failures); assertTrue(f.sent.isEmpty());
        RddDecomposer.withPlannerEndpoint(f.a, "stage_b",
                id -> java.util.concurrent.CompletableFuture.completedFuture(endpoint("openai", "FAKE_PROFILE_A")),
                f.client::add, f.generation::get, (stage, estimate) -> true,
                ep -> { throw new IllegalStateException("fixture transport setup"); }, ignored -> {}, () -> f.failures++);
        f.runClient();
        assertEquals(2, f.failures);
    }

    @Test void clientSchedulingRejectionFailsExactlyOnceBeforeOrAfterTransport() {
        for (int rejectAt : List.of(1, 2)) {
            var schedules = new java.util.concurrent.atomic.AtomicInteger();
            var sends = new java.util.concurrent.atomic.AtomicInteger();
            var failures = new java.util.concurrent.atomic.AtomicInteger();
            RddDecomposer.withPlannerEndpoint(java.util.UUID.randomUUID(), "fallback",
                    id -> java.util.concurrent.CompletableFuture.completedFuture(endpoint("openai", "FAKE_PROFILE_A")),
                    task -> { if (schedules.incrementAndGet() == rejectAt) throw new IllegalStateException("fixture queue"); task.run(); },
                    () -> 7L, (stage, estimate) -> true,
                    selected -> { sends.incrementAndGet(); return java.util.concurrent.CompletableFuture.completedFuture("{}"); },
                    ignored -> fail("rejected callback must not run"), failures::incrementAndGet);
            assertEquals(1, failures.get());
            assertEquals(rejectAt - 1, sends.get());
        }
    }

    @Test void throwingCallerCallbacksAreNeverReclassifiedOrInvokedTwice() {
        var results = new java.util.concurrent.atomic.AtomicInteger();
        var failures = new java.util.concurrent.atomic.AtomicInteger();
        RddDecomposer.withPlannerEndpoint(java.util.UUID.randomUUID(), "stage_a",
                id -> java.util.concurrent.CompletableFuture.completedFuture(endpoint("openai", "FAKE_PROFILE_A")),
                Runnable::run, () -> 7L, (stage, estimate) -> true,
                selected -> java.util.concurrent.CompletableFuture.completedFuture("{}"),
                ignored -> { results.incrementAndGet(); throw new IllegalStateException("fixture caller result"); },
                failures::incrementAndGet);
        assertEquals(1, results.get()); assertEquals(0, failures.get());
        RddDecomposer.withPlannerEndpoint(java.util.UUID.randomUUID(), "stage_b",
                id -> java.util.concurrent.CompletableFuture.completedFuture(null),
                Runnable::run, () -> 7L, (stage, estimate) -> true,
                selected -> { throw new AssertionError("missing endpoint cannot send"); }, ignored -> {},
                () -> { failures.incrementAndGet(); throw new IllegalStateException("fixture caller failure"); });
        assertEquals(1, failures.get());
    }

    @Test void plannerHttpFailureNeverResolvesOrSendsAnExecutionReplacement() {
        for (String stage : List.of("stage_a", "stage_b", "fallback")) {
            var client = new java.util.ArrayList<Runnable>();
            var lookups = new java.util.concurrent.atomic.AtomicInteger();
            var sends = new java.util.ArrayList<com.dwinovo.numen.agent.llm.LlmEndpoint>();
            var failures = new java.util.concurrent.atomic.AtomicInteger();
            RddDecomposer.withPlannerEndpoint(java.util.UUID.randomUUID(), stage,
                    id -> { lookups.incrementAndGet(); return java.util.concurrent.CompletableFuture.completedFuture(
                            endpoint("deepseek", "FAKE_INDEPENDENT_PLANNER_A")); },
                    client::add, () -> 7L, (phase, estimate) -> true,
                    selected -> { sends.add(selected); return java.util.concurrent.CompletableFuture.<String>failedFuture(
                            new IllegalStateException("fixture HTTP 401 invalid planner")); },
                    ignored -> fail("HTTP failure cannot produce a plan"), failures::incrementAndGet);
            while (!client.isEmpty()) client.removeFirst().run();
            assertEquals(1, lookups.get(), "failed independent planner must not resolve an execution replacement");
            assertEquals(1, sends.size()); assertEquals("FAKE_INDEPENDENT_PLANNER_A", sends.getFirst().apiKey());
            assertEquals("off", sends.getFirst().reasoningEffort()); assertEquals(1, failures.get());
        }
    }

    @Test void oldPendingPlannerEndpointCannotSendAfterWorldGenerationChanges() {
        for (String stage : List.of("stage_a", "stage_b", "fallback")) {
            var f = new PlannerEndpointFixture();
            var pending = new java.util.concurrent.CompletableFuture<com.dwinovo.numen.agent.llm.LlmEndpoint>();
            f.lookup = id -> pending;
            f.ask(f.a, stage);
            assertTrue(f.client.isEmpty());
            f.generation.incrementAndGet();
            pending.complete(endpoint("deepseek", "FAKE_OLD_PLANNER_A"));
            f.runClient();
            assertTrue(f.sent.isEmpty()); assertTrue(f.budgetChecks.isEmpty());
            assertEquals(1, f.failures); assertEquals(List.of(f.a), f.lookedUp);
        }
    }

    private static com.dwinovo.numen.agent.llm.LlmEndpoint endpoint(String provider, String key) {
        return new com.dwinovo.numen.agent.llm.LlmEndpoint(provider, "fixture-model", key,
                "https://fixture.invalid/v1", "fixture-proxy", "high");
    }

    @Test void admissionGenerationChangeAfterBudgetCannotSend() {
        var generation = new java.util.concurrent.atomic.AtomicLong(7L);
        var failures = new java.util.concurrent.atomic.AtomicInteger();
        var sends = new java.util.concurrent.atomic.AtomicInteger();
        RddDecomposer.withPlannerEndpoint(java.util.UUID.randomUUID(), "stage_b",
                id -> java.util.concurrent.CompletableFuture.completedFuture(endpoint("openai", "FAKE_PROFILE_A")),
                Runnable::run, generation::get, (stage, estimate) -> { generation.incrementAndGet(); return true; },
                ep -> { sends.incrementAndGet(); return java.util.concurrent.CompletableFuture.completedFuture("{}"); },
                ignored -> fail("stale admission cannot return"), failures::incrementAndGet);
        assertEquals(0, sends.get()); assertEquals(1, failures.get());
    }

    @Test void transportOwnerAndOriginalFacadeKeepSameCallbackTrace() {
        for (String stage : List.of("stage_a", "stage_b", "fallback")) {
            var facade = new java.util.ArrayList<String>();
            var owner = new java.util.ArrayList<String>();
            for (boolean direct : List.of(false, true)) {
                var trace = direct ? owner : facade;
                java.util.function.Function<java.util.UUID, java.util.concurrent.CompletableFuture<com.dwinovo.numen.agent.llm.LlmEndpoint>> lookup = id -> {
                    trace.add("lookup"); return java.util.concurrent.CompletableFuture.completedFuture(endpoint("openai", "FAKE_PROFILE_A"));
                };
                java.util.concurrent.Executor client = task -> { trace.add("client"); task.run(); };
                java.util.function.BiPredicate<String, Long> budget = (phase, estimate) -> { trace.add(phase + ":" + estimate); return true; };
                java.util.function.Function<com.dwinovo.numen.agent.llm.LlmEndpoint, java.util.concurrent.CompletableFuture<String>> send = ep -> {
                    assertEquals("FAKE_PROFILE_A", ep.apiKey()); trace.add("send");
                    return java.util.concurrent.CompletableFuture.completedFuture("arguments");
                };
                java.util.function.Consumer<String> result = value -> trace.add("result:" + value);
                Runnable failure = () -> fail("successful endpoint must not fail");
                if (direct) RddPlannerTransport.withPlannerEndpoint(java.util.UUID.randomUUID(), stage,
                        lookup, client, () -> 7L, budget, send, result, failure);
                else RddDecomposer.withPlannerEndpoint(java.util.UUID.randomUUID(), stage,
                        lookup, client, () -> 7L, budget, send, result, failure);
            }
            assertEquals(List.of("lookup", "client", stage + ":0", "send", "client", "result:arguments"), facade);
            assertEquals(facade, owner, "equivalence wiring only, not an independent old behavior golden");
        }
    }

    private static final class PlannerEndpointFixture {
        final java.util.UUID a = java.util.UUID.fromString("11111111-1111-1111-1111-111111111111");
        final java.util.UUID b = java.util.UUID.fromString("22222222-2222-2222-2222-222222222222");
        final java.util.List<Runnable> client = new java.util.ArrayList<>();
        final java.util.List<com.dwinovo.numen.agent.llm.LlmEndpoint> sent = new java.util.ArrayList<>();
        final java.util.List<java.util.UUID> lookedUp = new java.util.ArrayList<>();
        final java.util.List<String> budgetChecks = new java.util.ArrayList<>();
        final java.util.concurrent.atomic.AtomicLong generation = new java.util.concurrent.atomic.AtomicLong(7);
        java.util.function.Function<java.util.UUID, java.util.concurrent.CompletableFuture<com.dwinovo.numen.agent.llm.LlmEndpoint>> lookup =
                id -> java.util.concurrent.CompletableFuture.completedFuture(endpoint("openai", "FAKE_PROFILE_A"));
        boolean allowed = true;
        int failures;
        void ask(java.util.UUID id, String stage) { ask(id, stage, () -> failures++); }
        void ask(java.util.UUID id, String stage, Runnable onFail) {
            RddDecomposer.withPlannerEndpoint(id, stage,
                    requested -> { lookedUp.add(requested); return lookup.apply(requested); }, client::add,
                    generation::get, (phase, estimate) -> { budgetChecks.add(phase + ":" + estimate); return allowed; },
                    selected -> { sent.add(selected); return java.util.concurrent.CompletableFuture.completedFuture("{}"); },
                    ignored -> {}, onFail);
        }
        void runClient() { while (!client.isEmpty()) client.removeFirst().run(); }
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

    @Test void acceptedFourStepLimitRejectsFiveLegalStepsWithoutTruncation() {
        StringBuilder sb = new StringBuilder("{\"subtasks\":[");
        for (int i = 0; i < 5; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"description\":\"s").append(i).append('"')
                    .append(",\"condition\":{\"asset_key\":\"minecraft:oak_log\",\"minimum\":1}}");
        }
        sb.append("]}");
        var parsed = RddStageBSpecParser.parseDetailed(sb.toString());
        assertEquals(5, parsed.specs().size());
        var filtered = RddPlanGuard.filterSubtasks(RddPlanGuard.normalizeConditions(parsed.specs()),
                "立足开局", java.util.Map.of());
        assertTrue(RddDecomposer.stageBPublished(parsed, filtered, "立足开局", 4).isEmpty(),
                "the accepted per-primary limit rejects the whole batch");
        assertEquals(5, RddDecomposer.stageBPublished(parsed, filtered, "立足开局", 5).size(),
                "the exact accepted limit remains complete");
        assertTrue(RddDecomposer.stageBPublished(parsed, filtered, "立足开局", 5, 1).isEmpty(),
                "an explicit first-primary count rejects a different batch size");
        assertEquals(5, RddDecomposer.stageBPublished(parsed, filtered, "立足开局", 5, 5).size(),
                "the exact first-primary count remains complete");
    }

    // ---------- 2026-11-17 V02：必需丢弃后走真实共享空恢复，不装木材前缀 ----------

    private static final String V02_SIX_STEP_JSON = "{\"subtasks\": [{\"description\": \"徒手砍针叶林附近的树，攒够原木（起步 3 个即可推进；看到就多砍几棵，够做木镐+石镐+石斧共约 5~6 个原木就停）\", \"condition\": {\"type\": \"inventory\", \"group\": \"wood\", \"minimum\": 6}, \"node_type\": \"MAIN\"}, {\"description\": \"把原木在 2x2 手搓成木板，再把木板竖排 2 个搓木棍（需工作台时才放工作台）\", \"condition\": {\"type\": \"item_crafted\", \"asset_key\": \"minecraft:stick\", \"minimum\": 4}, \"node_type\": \"MAIN\"}, {\"description\": \"用木板 3 + 木棍 2 在工作台 3x3 里合成木镐\", \"condition\": {\"type\": \"item_crafted\", \"asset_key\": \"minecraft:wooden_pickaxe\", \"minimum\": 1}, \"node_type\": \"MAIN\"}, {\"description\": \"用木镐在附近地表/裸露岩面挖 6 个圆石（正好够石镐+石斧，不多挖）\", \"condition\": {\"type\": \"block_mined\", \"block\": \"minecraft:stone\", \"minimum\": 6}, \"node_type\": \"MAIN\"}, {\"description\": \"圆石 3 + 木棍 2 合成石镐\", \"condition\": {\"type\": \"item_crafted\", \"asset_key\": \"minecraft:stone_pickaxe\", \"minimum\": 1}, \"node_type\": \"MAIN\"}, {\"description\": \"圆石 3 + 木棍 2 合成石斧；石镐石斧一到手即停手，不再砍树挖石，下一步立刻出发找村庄\", \"condition\": {\"type\": \"item_crafted\", \"asset_key\": \"minecraft:stone_axe\", \"minimum\": 1}, \"node_type\": \"MAIN\"}]}";

    @Test
    void v02RequiredDropsReachRealEmptyFallbackNotWoodPrefix() {
        var parsed = RddStageBSpecParser.parseDetailed(V02_SIX_STEP_JSON);
        assertEquals(2, parsed.specs().size());
        assertEquals(4, parsed.skippedRequired().size());
        var filtered = RddPlanGuard.filterSubtasks(RddPlanGuard.normalizeConditions(parsed.specs()),
                "立足开局：徒手砍树取原木→木板→木棍→木镐；用木镐在附近地表挖6个石头，做出石镐+石斧即停手",
                java.util.Map.of());
        var published = RddDecomposer.stageBPublished(parsed, filtered, "立足开局：徒手砍树取原木→木板→木棍→木镐；用木镐在附近地表挖6个石头，做出石镐+石斧即停手");
        assertTrue(published.isEmpty(), "不能发布木材前缀");
        assertRealEmptyFallback(published);
    }
}
