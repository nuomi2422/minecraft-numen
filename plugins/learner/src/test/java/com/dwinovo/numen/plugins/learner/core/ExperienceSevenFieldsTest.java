package com.dwinovo.numen.plugins.learner.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V4「七字段经验」的契约测试。
 *
 * <p><b>为什么这一批非做不可（实机证据）</b>：2026-10-01 实机跑 {@code learner_review}，
 * LLM 判定质量<b>是对的</b>（4 条 {@code NO_ACTION} 判得对，唯一该沉淀的那条抓到了真 bug、0.95），
 * 但它给的 {@code experience_draft} <b>是一段散文，七个字段一个都没结构化</b>。
 * 根因：{@code 45} §2 的七字段<b>从来只是文档描述</b>，代码里是一个 {@code String}。
 *
 * <p>所以本类把「格式」钉成代码：模型必须交出 7 个具名字段，
 * 拿不出来就<b>如实说拿不出来</b>，而不是把一段话说得像有结构。
 */
public class ExperienceSevenFieldsTest {

    private static final String GOOD = "{\"mechanism\":\"下界竖井要先探底再往下挖\","
            + "\"preconditions\":\"y<=-12 且垂直通道\","
            + "\"failureConditions\":\"开阔熔岩湖上方；末影人直视范围内\","
            + "\"observableSignal\":\"HUD 血量<6 且无食物 → 停止下潜\","
            + "\"derivation\":\"血量<6 → 上岸 → 找食物 → 血量≥8 再回\","
            + "\"efficiency\":\"一次下潜 12 格 vs 3 格×4 次；时间 -70%\","
            + "\"evidence\":\"ep7 t2000 commentary 原话 + 截图路径\","
            // E4：分类是第 2 批的必填项。这条讲「怎么做」⇒ EXECUTION。
            + "\"experienceType\":\"EXECUTION\"}";

    /** E4：同一条正文，但**故意不给分类**（用来验证「没交就不许当合格」）。 */
    private static final String GOOD_WITHOUT_TYPE = GOOD.replace(",\"experienceType\":\"EXECUTION\"}", "}");

    private static JsonObject obj(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    // ---------- 解析 ----------

    @Test
    void sevenFieldObjectParses() {
        Experience x = Experience.parse(obj("{\"experience\":" + GOOD + "}"));
        assertNotNull(x);
        assertEquals(7, x.filledCount());
        assertEquals("下界竖井要先探底再往下挖", x.mechanism());
        assertEquals("开阔熔岩湖上方；末影人直视范围内", x.failureConditions());
        assertTrue(x.requiredComplete());
        assertTrue(x.acceptable());
        assertEquals("", x.unacceptableReason());
    }

    @Test
    void legacyProseStringIsNotAcceptedAsExperience() {
        // ⚠️ 这是本轮最关键的一条：老格式 experience_draft 是一段散文。
        // **刻意不把它塞进 mechanism 蒙过去** —— 那样会让「格式已落地」看起来成立，
        // 而实际仍然是一段散文，下游拿到手还是没法按字段检索。
        Experience x = Experience.parse(obj(
                "{\"experience_draft\":\"hp=0 是有效值，不能因为是 0 就当缺失\"}"));
        assertNull(x, "一段散文不是结构化经验，必须是 null（缺失就缺失）");
    }

    @Test
    void missingExperienceKeyGivesNull() {
        assertNull(Experience.parse(obj("{\"reasoning\":\"x\"}")));
        assertNull(Experience.parse(null));
    }

    @Test
    void allEmptyExperienceObjectGivesNull() {
        assertNull(Experience.parse(obj("{\"experience\":{}}")), "全空 = 缺失，不是「一条空经验」");
    }

    @Test
    void nonObjectExperienceGivesNull() {
        assertNull(Experience.parse(obj("{\"experience\":\"一段话\"}")));
        assertNull(Experience.parse(obj("{\"experience\":123}")));
    }

    // ---------- 完整度 ----------

    @Test
    void missingTwoRequiredFieldsIsNotAcceptableAndSaysWhich() {
        Experience x = Experience.parse(obj("{\"experience\":{"
                + "\"mechanism\":\"m\",\"preconditions\":\"p\",\"derivation\":\"d\","
                + "\"efficiency\":\"e\",\"evidence\":\"v\"}}"));
        assertNotNull(x);
        assertEquals(5, x.filledCount());
        assertFalse(x.requiredComplete());
        assertFalse(x.acceptable());
        String why = x.unacceptableReason();
        assertTrue(why.contains("失效条件"), why);
        assertTrue(why.contains("可观察信号"), why);
        assertEquals(List.of("失效条件", "可观察信号"), x.missingFields());
    }

    @Test
    void oneRequiredFieldPresentIsStillNotAcceptable() {
        Experience x = Experience.parse(obj("{\"experience\":{"
                + "\"mechanism\":\"m\",\"preconditions\":\"p\",\"failureConditions\":\"会害人\","
                + "\"derivation\":\"d\",\"efficiency\":\"e\",\"evidence\":\"v\"}}"));
        assertFalse(x.acceptable(), "有失效条件但没可观察信号 = 无法在世界里核对");
        assertTrue(x.unacceptableReason().contains("可观察信号"));
    }

    @Test
    void allSevenRequiredForThisBatchAcceptance() {
        // 用户 2026-10-01 定「先不严，第 2 批再卡」→ 本轮只卡「七字段齐全 + 两个关键字段非空」
        String fiveOfSeven = "{\"experience\":{\"mechanism\":\"m\",\"preconditions\":\"p\","
                + "\"derivation\":\"d\",\"efficiency\":\"e\",\"evidence\":\"v\"}}";
        assertFalse(Experience.parse(obj(fiveOfSeven)).acceptable());
        assertTrue(Experience.parse(obj("{\"experience\":" + GOOD + "}")).acceptable());
    }

    // ---------- B21：缺失不许伪装成数据 ----------

    @Test
    void toMapOnlyContainsRealValues() {
        Experience x = Experience.parse(obj("{\"experience\":{"
                + "\"mechanism\":\"m\",\"failureConditions\":\"会害人\"}}"));
        Map<String, Object> m = x.toMap();
        assertEquals(2, m.size());
        assertTrue(m.containsKey("mechanism"));
        assertTrue(m.containsKey("failureConditions"));
        assertFalse(m.containsKey("efficiency"), "空的字段不许出现（B21：缺失用「没有这个键」表达）");
        assertFalse(m.containsKey("preconditions"));
    }

    @Test
    void blankStringCountsAsMissingNotAsContent() {
        // " " 是最阴的一种：看起来有内容，其实什么都没有
        Experience x = Experience.parse(obj("{\"experience\":{\"mechanism\":\"   \",\"evidence\":\"v\"}}"));
        assertEquals(1, x.filledCount());
        assertEquals(List.of("机制", "前置条件", "失效条件", "可观察信号", "推导步骤", "效率"),
                x.missingFields().stream().filter(k -> !k.equals("证据")).toList());
    }

    @Test
    void summarySaysUnacceptableInsteadOfPretending() {
        Experience x = Experience.parse(obj("{\"experience\":{\"mechanism\":\"一段话\"}}"));
        String s = x.summary();
        assertTrue(s.startsWith("不合格["), s);
        assertTrue(s.contains("一段话"), "不合格也要保留已有的内容，别全丢：" + s);
    }

    @Test
    void goodExperienceSummaryIsJustTheMechanism() {
        assertEquals("下界竖井要先探底再往下挖",
                Experience.parse(obj("{\"experience\":" + GOOD + "}")).summary());
    }

    // ---------- 与 Verdict 的接线 ----------

    @Test
    void verdictCarriesStructuredExperience() {
        String v = "{\"memo_id\":\"m-1\",\"actions\":[\"WRITE_EXPERIENCE\"],\"confidence\":0.9,"
                + "\"reasoning\":\"r\",\"experience\":" + GOOD + ",\"rewritten_query\":[\"q\"]}";
        Verdict parsed = Verdict.parse("m-1", v);
        assertNotNull(parsed);
        assertNotNull(parsed.experience());
        assertTrue(parsed.experience().acceptable());
        assertTrue(parsed.toJson().contains("experience_acceptable"));
        assertFalse(parsed.toJson().contains("experience_draft"),
                "旧键不许再出现 —— 否则「格式已落地」看起来成立");
    }

    @Test
    void verdictWithProseOnlyHasNullExperienceAndSaysNothingAboutIt() {
        Verdict parsed = Verdict.parse("m-2",
                "{\"memo_id\":\"m-2\",\"actions\":[\"WRITE_EXPERIENCE\"],\"confidence\":0.9,"
                        + "\"reasoning\":\"r\",\"experience_draft\":\"一段散文\"}");
        assertNotNull(parsed);
        assertNull(parsed.experience());
        assertFalse(parsed.toJson().contains("\"experience\""), "缺失就不放这个键（B21）");
        assertFalse(parsed.toJson().contains("experience_acceptable"));
    }

@Test
    void promptSpecDemandsTheTwoCriticalFields() {
        String spec = Experience.promptSpec();
        assertTrue(spec.contains("failureConditions"), spec);
        assertTrue(spec.contains("observableSignal"), spec);
        assertTrue(spec.contains("NO_ACTION"), "填不出关键字段就别硬凑，应当反回 NO_ACTION（这条别删）");
    }

    // ---------- E4：分类 ----------

    @Test
    void promptSpecNowDemandsAClassification() {
        String spec = Experience.promptSpec();
        assertTrue(spec.contains("experienceType"), "第 2 批起分类是必填项，prompt 必须写明");
        for (String t : Experience.EXPERIENCE_TYPES) {
            assertTrue(spec.contains(t), "prompt 里少了可选值 " + t + "，它只能靠猜");
        }
        assertTrue(spec.contains("8 个必填键"), "键数说 7 就会让人交 7 个键的旧格式");
        // A6（98 号）：audience 是可选键，prompt 必须说明它可选、说不清就不写
        assertTrue(spec.contains("audience"), "prompt 必须提到 audience 键");
        assertTrue(spec.contains("可选"), "audience 必须写成可选，否则会挤掉必填键的注意力");
    }

    @Test
    void typeIsNormalizedButNeverGuessed() {
        assertEquals("FAILURE", parseWithType("FAILURE").typeName());
        assertEquals("FAILURE", parseWithType("  failure  ").typeName(), "大小写与空白该归一");
        assertEquals("TOOL_DEFECT", parseWithType("tool_defect").typeName());
        // ★ 这些必须判非法：type 决定 stableKey / fingerprint，也就是条目的身份键。
        //   猜错 = 真实的一条被劈成两条，或两条不同的被并成一条。
        assertEquals("", parseWithType("失败经验").typeName(), "中文近义词不是分类");
        assertEquals("", parseWithType("FAILURE_TYPE").typeName());
        assertEquals("", parseWithType("").typeName());
    }

    @Test
    void missingOrInvalidTypeIsReportedNotHidden() {
        Experience noType = parse(GOOD_WITHOUT_TYPE);
        assertFalse(noType.typeComplete());
        assertFalse(noType.acceptable(), "七字段全填但没分类 ⇒ 仍不合格（不然落库时才炸）");
        assertTrue(noType.unacceptableReason().contains("experienceType"),
                "要说清缺的是分类：" + noType.unacceptableReason());

        Experience bad = parseWithType("失败经验");
        assertFalse(bad.acceptable());
        assertTrue(bad.typeProblem().contains("失败经验"),
                "要把它**交的那个值**原样说出来，不能只说「非法」：" + bad.typeProblem());
    }

    @Test
    void validTypeMakesAFullExperienceAcceptable() {
        assertTrue(parse(GOOD).acceptable());
        assertTrue(parseWithType("EXECUTION").typeComplete());
    }

    private static Experience parse(String experienceJson) {
        return Experience.parse(obj("{\"experience\":" + experienceJson + "}"));
    }

    private static Experience parseWithType(String type) {
        return parse(GOOD.replace("\"EXECUTION\"", "\"" + type + "\""));
    }

    // 2026-11-12 原件：learner.jsonl L30479 request / L30480 response；
    // requestId c0355981-dcc4-414b-850b-f68e672ef3e3，transport lr-293。
    // L30491 三条仅 Q0 硬失败，L30492 stored=0；不外推其它批次。
    // 原 verdict 仅省略 retrieval 和空产物键，不将 SHADOW 结果当落库资格。
    private static final String ORIGINAL_EVIDENCE_BATCH = """
            {
              "verdicts": [
                {
                  "memo_id": "ai-instr-78281859238400-10",
                  "actions": ["WRITE_EXPERIENCE"],
                  "confidence": 0.72,
                  "reasoning": "三个窗口(12:51/12:53/13:04)同一 task=primary-a43d753e-0 都是 attempts=3、threshold=3、fromNegotiation=false，背包 29→28→27（世界真变了）预算却没回退；命中[同一件事]已有条目，故 REVISE：把「跨窗口累积 + 执行期同烧 + 窗口内零调用不等于没发生」补进已有条目，不另立 NEW。缺席行动：窗口内未换失败形态、未上报重规划。",
                  "experience": {
                    "mechanism": "replan 预算由引擎按失败形态记账：同一 subtask 同一形态失败累加 attempts，达 context.threshold 即判 loop_detected 停车。计数是引擎侧累积事实，跨窗口、跨时间、世界状态变化都不回退；执行期(fromNegotiation=false)与协商期共用同一计数。",
                    "preconditions": "task=primary-a43d753e-0 同一 subtask 以同一失败形态连续失败；attempts 每次失败 +1；threshold=3；fromNegotiation=false|true 均计入；窗口内 tool_calls=0 不代表没发生",
                    "failureConditions": "失败形态或 task id 已变、人类已重置链/换新 primary 时不适用；把它当成『再试一次就好』的依据会在第 3 次直接停车；仅凭窗口内无动作记录断定空转不可靠（读不到≠没发生）",
                    "observableSignal": "attempts=3, threshold=3, fromNegotiation=false, reason=failure replan budget exhausted (loop), task=primary-a43d753e-0, items 29→28→27, 窗口内 tool_calls=0",
                    "derivation": "看到 attempts 逼近 threshold 而失败形态未变 → 同形态重试只烧预算不解决问题 → 必须在第 threshold 次前换成可执行的不同动作或上报重规划；已 loop_detected 只能由外部重启链/换 primary 解除",
                    "efficiency": "省掉 threshold-1 次必然失败的重试与 10~20 分钟窗口消耗，并避免把『世界状态变了』误判成预算已重置",
                    "evidence": ["event:instr-78281859238400-10","event:instr-77552257470500-4","event:instr-77612563973300-5"],
                    "experienceType": "FAILURE",
                    "audience": ["planner","executor"]
                  },
                  "rewritten_query": ["replan budget exhausted loop_detected attempts threshold","重试预算 跨窗口 累积 fromNegotiation=false","同一 primary 多次 loop 停车 不停机对策"]
                },
                {
                  "memo_id": "ai-instr-77552257470500-4",
                  "actions": ["WRITE_EXPERIENCE"],
                  "confidence": 0.7,
                  "reasoning": "与上一条同一 primary-a43d753e-0、同一 attempts=3/threshold=3/fromNegotiation=false，仅窗口不同（items 29→28）；机制已被[同一件事]条目覆盖，无新增维度，故 REUSE，不再生产近似重复条目（前轮近似重复已被质量门 REJECT）。",
                  "experience": {
                    "mechanism": "同一 subtask 以同一失败形态重复失败时引擎累加 attempts，达 threshold 判 loop 停车；该计数跨窗口保留，背包数量/时间变化都不清零。",
                    "preconditions": "task=primary-a43d753e-0；失败形态未变；attempts=3；threshold=3；fromNegotiation=false",
                    "failureConditions": "失败形态已变、或链已被人类重启时不要把旧窗口的 attempts 当现状；据此认定『必然还会停车』而不看形态会误判",
                    "observableSignal": "attempts=3, threshold=3, fromNegotiation=false, task=primary-a43d753e-0, items 29→28, 窗口内 tool_calls=0",
                    "derivation": "读到同一 task 再次 attempts=3 且形态未变 → 视为同一条已耗尽的预算，不做同形态重试，直接走换动作或上报",
                    "efficiency": "避免重复投入一个 window 的试错时间",
                    "evidence": ["event:instr-77552257470500-4"],
                    "experienceType": "FAILURE",
                    "audience": ["planner"]
                  },
                  "rewritten_query": ["replan budget exhausted 同一 primary 第二窗口","loop_detected attempts=3 threshold=3"]
                },
                {
                  "memo_id": "ai-instr-77612563973300-5",
                  "actions": ["WRITE_EXPERIENCE"],
                  "confidence": 0.7,
                  "reasoning": "第三个窗口，同样 task=primary-a43d753e-0/attempts=3/threshold=3/fromNegotiation=false，items 29→28；与前面两块及库内[同一件事]同一机制，无新维度（窗口内无工具调用不等于没发生，不足以支撑新机制），REUSE 已有条目。",
                  "experience": {
                    "mechanism": "引擎对同一 subtask 的 replan 失败按形态计 attempts，达 threshold 判 loop 停车；计数不随窗口切换、时间推移或背包变化回退。",
                    "preconditions": "task=primary-a43d753e-0；同一失败形态；attempts=3；threshold=3；fromNegotiation=false",
                    "failureConditions": "链已重启或 primary 已更换时旧计数不再适用；用本条解释『换形态后的新失败』会误判为又是预算问题",
                    "observableSignal": "attempts=3, threshold=3, fromNegotiation=false, reason=failure replan budget exhausted (loop), task=primary-a43d753e-0, tool_calls=0",
                    "derivation": "同一 task 第三次出现该形态 → 判定为预算已耗尽，停止同形态重试，交给外部重启/换 primary",
                    "efficiency": "省去再来一轮必然停车的时间窗",
                    "evidence": ["event:instr-77612563973300-5"],
                    "experienceType": "FAILURE",
                    "audience": ["planner"]
                  },
                  "rewritten_query": ["loop_detected 第三窗口 预算不回退","replan budget exhausted 换 primary 才能解除"]
                }
              ]
            }
            """;

    /** Reviewer 同包测试复用一份原响应；每次返回新树，避免测试互相污染。 */
    public static JsonArray originalEvidenceBatch() {
        return obj(ORIGINAL_EVIDENCE_BATCH).getAsJsonArray("verdicts");
    }

    // request 的 memo 三段逐字保留；createdAt 不参与门禁，用 0 不编造时间。
    private static final List<Memo> ORIGINAL_MEMOS = List.of(
            new Memo("ai-instr-78281859238400-10", "[T2/loop_detected]  / task=primary-a43d753e-0 / reason=failure replan budget exhausted (loop)", "loop_detected", "重试预算耗尽于第 N 次=3", "context.attempts=3, context.fromNegotiation=false, context.threshold=3, companionId=a43d753e-790d-472b-8d5b-dc18ac0afbc0, reason=failure replan budget exhausted (loop), task=primary-a43d753e-0, window_first=instr-78281859238400-10", 0L),
            new Memo("ai-instr-77552257470500-4", "[T2/loop_detected]  / task=primary-a43d753e-0 / reason=failure replan budget exhausted (loop)", "loop_detected", "重试预算耗尽于第 N 次=3", "context.threshold=3, companionId=a43d753e-790d-472b-8d5b-dc18ac0afbc0, reason=failure replan budget exhausted (loop), task=primary-a43d753e-0, context.attempts=3, context.fromNegotiation=false, window_first=instr-77552257470500-4", 0L),
            new Memo("ai-instr-77612563973300-5", "[T2/loop_detected]  / task=primary-a43d753e-0 / reason=failure replan budget exhausted (loop)", "loop_detected", "重试预算耗尽于第 N 次=3", "context.threshold=3, companionId=a43d753e-790d-472b-8d5b-dc18ac0afbc0, reason=failure replan budget exhausted (loop), task=primary-a43d753e-0, context.attempts=3, context.fromNegotiation=false, window_first=instr-77612563973300-5", 0L)
    );

    private static Verdict evidenceVerdict(String evidenceJson) {
        JsonObject experience = obj(GOOD);
        if (evidenceJson == null) {
            experience.remove("evidence");
        } else {
            experience.add("evidence", JsonParser.parseString(evidenceJson));
        }
        JsonObject verdict = new JsonObject();
        verdict.addProperty("memo_id", "m-evidence");
        verdict.add("actions", JsonParser.parseString("[\"WRITE_EXPERIENCE\"]"));
        verdict.add("experience", experience);
        return Verdict.parse("m-evidence", verdict.toString());
    }

    private static Memo evidenceMemo() {
        // 合成边界样本，有出处也不能替缺失的 experience.evidence 补证。
        return new Memo("m-evidence", "竖井下潜遇到危险", "mining", "先探底再下潜",
                "hp=12/20, armor=none, window_first=must-not-fill-evidence", 0L,
                Memo.CATEGORY_LEARNING, List.of("must-not-fill-evidence"));
    }

    private static ExperienceQualityGate.Result quality(Verdict verdict, Memo memo) {
        ExperienceDraft draft = ExperienceDraft.from(verdict, memo);
        return ExperienceQualityGate.evaluate(verdict.experience(), memo,
                draft.entry().get("description").getAsString());
    }

    private static boolean storable(Verdict verdict, Memo memo, ExperienceQualityGate.Result gate) {
        ExperienceDraft draft = ExperienceDraft.from(verdict, memo);
        Gson gson = new Gson();
        return ExperiencePersistencePolicy.storable(Map.of(
                "entry", gson.fromJson(draft.toJson(), Map.class),
                "mapping", gson.fromJson(draft.explain(), Map.class)), gate.hasHardFailure());
    }

    private static void assertReferencesInOrder(String evidence, List<String> references) {
        int previous = -1;
        for (String reference : references) {
            int at = evidence.indexOf(reference, previous + 1);
            assertTrue(at > previous, "引用缺失或乱序：" + reference + " in " + evidence);
            previous = at;
        }
    }

    @Test
    void legalEvidenceArraysPreserveEventBlockAndContextReferences() {
        List<String> references = List.of("event:fixture-event", "block:360,66,636", "context:dc-turn-3");
        Verdict verdict = evidenceVerdict(new Gson().toJson(references));
        assertNotNull(verdict);
        assertNotNull(verdict.experience());
        assertReferencesInOrder(verdict.experience().evidence(), references);
        assertTrue(verdict.experience().acceptable());
        JsonObject serialized = obj(verdict.toJson()).getAsJsonObject("experience");
        assertTrue(serialized.get("evidence").isJsonPrimitive());
        assertTrue(serialized.getAsJsonPrimitive("evidence").isString(),
                "canonical JSON 仍是旧 String 字段，不把数组传播给消费者");
        assertEquals(verdict.experience().evidence(), serialized.get("evidence").getAsString());
        assertEquals(verdict.experience().evidence(),
                ExperienceDraft.from(verdict, evidenceMemo()).entry().get("evidence").getAsString());
    }

    @Test
    void legacyEvidenceStringsKeepTheirOriginalContent() {
        for (String evidence : List.of("ep7 t2000 commentary 原话 + 截图路径", "event:old-id",
                "旧散文来源 + 截图路径")) {
            Verdict verdict = evidenceVerdict(new Gson().toJson(evidence));
            assertEquals(evidence, verdict.experience().evidence());
            assertEquals(evidence, ExperienceDraft.from(verdict, evidenceMemo()).entry()
                    .get("evidence").getAsString());
        }
    }

    @Test
    void missingEmptyAndNullEvidenceNeverBorrowMemoReferences() {
        for (String evidence : java.util.Arrays.asList(null, "null", "\"\"", "\"  \"", "[]")) {
            assertEvidenceRejected(evidence);
        }
    }

    @Test
    void nonStringAndWhollyInvalidEvidenceRemainRejected() {
        for (String evidence : List.of("{}", "123", "true",
                "[null,123,false,{},[],\"\",\"  \",\"no-prefix\",\"other:bad\",\"event:\",\"block:\",\"context:\"]")) {
            assertEvidenceRejected(evidence);
        }
    }

    private static void assertEvidenceRejected(String evidenceJson) {
        Verdict verdict = evidenceVerdict(evidenceJson);
        assertNotNull(verdict.experience());
        assertTrue(verdict.experience().evidence().isBlank(), "坏证据不能变成数据：" + evidenceJson);
        assertFalse(verdict.experience().acceptable());
        ExperienceQualityGate.Result gate = quality(verdict, evidenceMemo());
        assertEquals(List.of("Q0"), gate.failedIds(), "仅隔离证据缺失，其他必需输入保持完整");
        assertEquals(ExperienceQualityGate.Verdict.REJECT, gate.verdict());
        assertFalse(storable(verdict, evidenceMemo(), gate));
        assertTrue(ExperienceDraft.from(verdict, evidenceMemo()).entry().get("evidence").getAsString().isBlank(),
                "不能拿 memo.evidence 或 window_first 替这条 experience 补证");
        assertFalse(obj(verdict.toJson()).getAsJsonObject("experience").has("evidence"));
    }

    @Test
    void mixedEvidenceArrayKeepsOnlyUsableStringReferences() {
        Verdict verdict = evidenceVerdict(
                "[null,17,false,{},[],\"\",\"no-prefix\",\"other:bad\",\"event:\","
                        + "\"event:good-1\",\"block:dc-turn-3\",\"context:dc-turn-3\"]");
        String evidence = verdict.experience().evidence();
        assertReferencesInOrder(evidence, List.of("event:good-1", "block:dc-turn-3", "context:dc-turn-3"));
        for (String invalid : List.of("null", "17", "false", "{", "[", "no-prefix", "other:bad")) {
            assertFalse(evidence.contains(invalid), evidence);
        }
        assertEquals(1, evidence.split("event:", -1).length - 1, "空 id 项不得混入：" + evidence);
    }

    @Test
    void originalThreeVerdictsKeepEvidenceThroughQualityAndDraftStorageEligibility() {
        JsonArray originals = originalEvidenceBatch();
        assertEquals(3, originals.size());
        for (int i = 0; i < originals.size(); i++) {
            JsonObject raw = originals.get(i).getAsJsonObject();
            Memo memo = ORIGINAL_MEMOS.get(i);
            Verdict verdict = Verdict.parse(memo.id(), raw.toString());
            assertNotNull(verdict, memo.id());
            assertTrue(verdict.actions().contains(Verdict.Action.WRITE_EXPERIENCE));
            List<String> references = new java.util.ArrayList<>();
            raw.getAsJsonObject("experience").getAsJsonArray("evidence")
                    .forEach(ref -> references.add(ref.getAsString()));
            assertReferencesInOrder(verdict.experience().evidence(), references);
            assertEquals("FAILURE", verdict.experience().typeName());
            ExperienceQualityGate.Result gate = quality(verdict, memo);
            assertTrue(gate.failedIds().isEmpty(), memo.id() + ": " + gate.failedIds());
            assertEquals(ExperienceQualityGate.Verdict.REVIEW_REQUIRED, gate.verdict(),
                    "保留引用不等于已核验正文/证据真伪，更不等于可发布");
            assertTrue(gate.undecidableIds().contains("Q4"));
            assertTrue(storable(verdict, memo, gate), "仅既有 DRAFT 落库资格，不实际存储");
            assertEquals(verdict.experience().evidence(),
                    ExperienceDraft.from(verdict, memo).entry().get("evidence").getAsString());
        }
    }

    @Test
    void originalVerdictsStillFailQ0WhenTheirEvidenceIsActuallyMissing() {
        JsonArray originals = originalEvidenceBatch();
        for (int i = 0; i < originals.size(); i++) {
            JsonObject raw = originals.get(i).getAsJsonObject();
            raw.getAsJsonObject("experience").remove("evidence");
            Memo memo = ORIGINAL_MEMOS.get(i);
            Verdict verdict = Verdict.parse(memo.id(), raw.toString());
            assertTrue(verdict.experience().evidence().isBlank());
            ExperienceQualityGate.Result gate = quality(verdict, memo);
            assertEquals(List.of("Q0"), gate.failedIds());
            assertEquals(ExperienceQualityGate.Verdict.REJECT, gate.verdict());
            assertFalse(storable(verdict, memo, gate), "memo 快照仍有 window_first 也不许补证");
        }
    }
}
