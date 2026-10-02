package com.dwinovo.numen.plugins.learner.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * B2 / E3：七字段 → 经验库条目形状的映射 + 硬质量门。
 *
 * <p><b>这批的边界</b>：{@code plugins/learner} <b>刻意不依赖</b> {@code experience-core}
 * （两个 mod 各带一份同名类会炸），所以映射只能产出「schema 兼容的 JSON」。
 * 键名绑定由 {@link ExperienceDraftKeysBindToRealEntryTest} 读真源文件核对。</p>
 */
class ExperienceDraftGateTest {

    private static final String GOOD = "{\"mechanism\":\"下界竖井要先探底再往下挖\","
            + "\"preconditions\":\"y<=-12 且垂直通道\","
            + "\"failureConditions\":\"开阔熔岩湖上方；末影人直视范围内\","
            + "\"observableSignal\":\"HUD 血量<6 且无食物 → 停止下潜\","
            + "\"derivation\":\"血量<6 → 上岸 → 找食物 → 血量≥8 再回\","
            + "\"efficiency\":\"一次下潜 12 格 vs 3 格×4 次；时间 -70%\","
            + "\"evidence\":\"ep7 t2000 commentary 原话\","
            // E4：分类是第 2 批必填项（这条讲「怎么做」⇒ EXECUTION）。
            //   少了它，草稿就没有 type 键 ⇒ 进不了 ExperienceStore（learn() 对 type==null 抛）。
            + "\"experienceType\":\"EXECUTION\"}";

    /** E4：同一条正文，**故意不给分类** —— 用来验「没交就不许编 type」这条红线。 */
    private static final String GOOD_NO_TYPE = GOOD.replace(",\"experienceType\":\"EXECUTION\"}", "}");

    private static JsonObject obj(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    /**
     * ⚠️ {@link Experience#parse} 吃的是<b>整个判定对象</b>（找 {@code experience} 键），
     * 不是七字段本身。直接 {@code parse(GOOD)} 会返回 null —— 这个坑我踩了一次。
     */
    private static Experience sevenFields() {
        return Experience.parse(obj("{\"experience\":" + GOOD + "}"));
    }

    private static String withMechanism(String mechanism) {
        JsonObject o = obj(GOOD);
        o.addProperty("mechanism", mechanism);
        return o.toString();
    }

    private static Verdict verdict(String experienceJson, String... actions) {
        StringBuilder acts = new StringBuilder("[");
        for (int i = 0; i < actions.length; i++) {
            if (i > 0) {
                acts.append(',');
            }
            acts.append('"').append(actions[i]).append('"');
        }
        acts.append(']');
        Verdict v = Verdict.parse("m-1",
                "{\"memo_id\":\"m-1\",\"actions\":" + acts + ",\"confidence\":0.8,"
                        + "\"reasoning\":\"r\",\"experience\":" + experienceJson + "}");
        assertNotNull(v, "判定本身要能解析出来");
        return v;
    }

    private static Memo fullMemo(String snapshot) {
        return new Memo("m-1", "往下挖的时候掉血", "mining", "先直挖 12 格再铺水", snapshot, 1000L);
    }

    private static ExperienceDraft draftOf(Verdict v, Memo m) {
        return ExperienceDraft.from(v, m);
    }

    // ---------- 映射 ----------

    @Test
    void draftUsesOnlyKeysTheExperienceStoreKnows() {
        ExperienceDraft d = draftOf(verdict(GOOD, "WRITE_EXPERIENCE"), fullMemo("hp=12/20, armor=none"));
        for (String k : d.toJson().keySet()) {
            assertTrue(ExperienceDraft.ENTRY_KEYS.contains(k) || k.equals("title_display"),
                    "键 " + k + " 不在 ExperienceEntry.toJson() 的契约里，写进去也读不出来");
        }
        for (String k : ExperienceDraft.WRITTEN_KEYS) {
            assertTrue(d.toJson().has(k), "映射承诺会写 " + k + "，实际没写");
        }
    }

    /**
     * ★ Codex P1-1 的红线，<b>E4 之后原样保留</b>：<b>没交就不许写 type</b>。
     *
     * <p>变的是 type 的<b>来源</b>（从 {@code actions} 推断 → 学习者自己交），
     * 没变的是<b>不许编</b>：{@code actions} 含 {@code USE_CARRIER} 也<b>证明不了</b>
     * 分类是 POLICY —— 那还是「把操作建议当成分类事实」。</p>
     */
    @Test
    void draftDoesNotInventATypeWhenLearnerDidNotGiveOne() {
        ExperienceDraft d = draftOf(verdict(GOOD_NO_TYPE, "WRITE_EXPERIENCE", "USE_CARRIER"),
                fullMemo("hp=12/20"));
        assertFalse(d.toJson().has("type"),
                "学习者没交 experienceType ⇒ entry.type 不许出现（哪怕 actions 里有 USE_CARRIER）");
        assertEquals("POLICY", d.suggestedType(), "只能作为建议给出");
        assertTrue(d.explain().get("needs_classification").getAsBoolean());
        assertFalse(d.explain().get("entry_ready_for_store").getAsBoolean(),
                "没有 type 的草稿进不了库，必须如实说");
        assertTrue(d.explain().get("type_resolution").getAsString().contains("没有 type"));
        assertTrue(d.explain().get("suggested_type_why").getAsString().contains("只是建议"));
    }

    /** ★ E4：学习者交了合法分类 ⇒ 草稿就带 type，真的能落库了（这是断链被接上的那一环）。 */
    @Test
    void draftCarriesTheTypeTheLearnerGave() {
        ExperienceDraft d = draftOf(verdict(GOOD, "WRITE_EXPERIENCE"), fullMemo("hp=12/20"));
        assertTrue(d.toJson().has("type"), "交了就要写 —— 否则 learn() 会因 type==null 抛异常");
        assertEquals("EXECUTION", d.toJson().get("type").getAsString());
        assertFalse(d.explain().get("needs_classification").getAsBoolean());
        assertTrue(d.explain().get("entry_ready_for_store").getAsBoolean());
        assertTrue(d.explain().get("type_resolution").getAsString().contains("学习者自己交"));
    }

    /** E4：交的是近义词/中文 ⇒ 判为非法，**不许猜一个**（type 决定身份键，猜错会并/劈条目）。 */
    @Test
    void draftRefusesANearSynonymAsType() {
        String near = GOOD.replace("\"experienceType\":\"EXECUTION\"", "\"experienceType\":\"失败经验\"");
        ExperienceDraft d = draftOf(verdict(near, "WRITE_EXPERIENCE"), fullMemo("hp=12/20"));
        assertFalse(d.toJson().has("type"), "「失败经验」不是 FAILURE ⇒ 不许当成 type 落库");
        assertTrue(d.explain().get("type_resolution").getAsString().contains("失败经验"));
    }

    @Test
    void draftIsAcceptedByTheExperienceStoreSchema() {
        ExperienceDraft d = draftOf(verdict(GOOD, "WRITE_EXPERIENCE"), fullMemo("hp=12/20"));
        assertFalse(d.toJson().get("description").getAsString().isBlank(), "description 必填，不能空");
        assertEquals("下界竖井要先探底再往下挖", d.toJson().get("title").getAsString());
        assertEquals("OBSERVED", d.toJson().get("maturity").getAsString(),
                "一次成功未验证 = OBSERVED（doc 59 D5：允许进目录但必须标待验证）");
    }

    @Test
    void draftDoesNotInventToolNames() {
        // tool_names 映射不出来 —— 就不放这个键（B21：缺失不许用「无」冒充）
        ExperienceDraft d = draftOf(verdict(GOOD, "WRITE_EXPERIENCE"), fullMemo("hp=12/20"));
        assertFalse(d.toJson().has("tool_names"), "映射不出来就不该出现这个键");
    }

    @Test
    void draftKeepsEvidenceSomewhereEvenThoughEntryHasNoSlot() {
        ExperienceDraft d = draftOf(verdict(GOOD, "WRITE_EXPERIENCE"), fullMemo("hp=12/20"));
        assertTrue(d.toJson().get("rationale").getAsString().contains("ep7 t2000"),
                "evidence 在 ExperienceEntry 里没有槽位，但绝不能丢 —— 并进 rationale");
        assertEquals("ep7 t2000 commentary 原话", d.evidenceNote());
        assertEquals("ep7 t2000 commentary 原话",
                d.explain().get("experience_evidence").getAsString());
    }

    @Test
    void priorityMapsConfidenceAndStaysInRange() {
        assertEquals(1, ExperienceDraft.priorityFrom(0.0));
        assertEquals(100, ExperienceDraft.priorityFrom(1.0));
        assertEquals(80, ExperienceDraft.priorityFrom(0.8));
        assertEquals(1, ExperienceDraft.priorityFrom(-3));
        assertEquals(100, ExperienceDraft.priorityFrom(7));
    }

    /** ★ Codex P1-4：截断 title 会让两条长经验撞成一条。落盘的 title 必须是完整的。 */
    @Test
    void longTitleIsKeptWholeAndOnlyTheDisplayCopyIsClipped() {
        String longMech = "机制".repeat(60);
        ExperienceDraft d = draftOf(verdict(withMechanism(longMech), "WRITE_EXPERIENCE"), fullMemo("hp=12/20"));
        assertEquals(longMech, d.toJson().get("title").getAsString(),
                "★ title 是 id 的一部分，落盘时绝不能截断");
        assertEquals(ExperienceDraft.DISPLAY_TITLE_CHARS + 1,
                d.toJson().get("title_display").getAsString().length(),
                "展示版才截断");
        assertEquals(ExperienceDraft.DISPLAY_TITLE_CHARS,
                d.explain().get("title_display_truncated_to").getAsInt());
    }

    /** 两条只有后半段不同的长机制，落盘后不能撞成同一条。 */
    @Test
    void twoLongTitlesWithSamePrefixStayDistinct() {
        String head = "机制".repeat(40);
        ExperienceDraft a = draftOf(verdict(withMechanism(head + "甲"), "WRITE_EXPERIENCE"), fullMemo("hp=12/20"));
        ExperienceDraft b = draftOf(verdict(withMechanism(head + "乙"), "WRITE_EXPERIENCE"), fullMemo("hp=12/20"));
        assertNotEquals(a.toJson().get("title").getAsString(), b.toJson().get("title").getAsString());
        assertEquals(a.titleDisplay(), b.titleDisplay(), "展示版撞上是预期的，落盘版不能撞");
    }

    /** ★ Codex P1-3：前置条件不许被拼成因果链的下一步。 */
    @Test
    void applicabilityConditionsAreLabelledNotChained() {
        ExperienceDraft d = draftOf(verdict(GOOD, "WRITE_EXPERIENCE"), fullMemo("hp=12/20"));
        String rec = d.toJson().get("recommended_response").getAsString();
        assertTrue(rec.contains("适用条件：y<=-12"), rec);
        assertTrue(rec.contains("禁用条件：开阔熔岩湖上方"), rec);
        assertFalse(rec.contains(" → y<=-12"), "前置条件不是因果链的一环：" + rec);
    }

    @Test
    void draftRequiresStructuredExperience() {
        Verdict noExp = Verdict.parse("m-1",
                "{\"memo_id\":\"m-1\",\"actions\":[\"NO_ACTION\"],\"confidence\":0.5,\"reasoning\":\"r\"}");
        assertNotNull(noExp);
        assertThrows(IllegalArgumentException.class, () -> ExperienceDraft.from(noExp, fullMemo("hp=1")));
    }

    @Test
    void draftSurvivesMissingMemo() {
        ExperienceDraft d = draftOf(verdict(GOOD, "WRITE_EXPERIENCE"), null);
        assertNotNull(d.toJson().get("title"));
        assertFalse(d.toJson().get("tags").getAsJsonArray().toString().contains("memo:"),
                "没有 memo 就不该编一个 memo: 标签");
    }

    // ---------- 质量门 ----------

    @Test
    void gatePassesAFullHonestExperience() {
        Experience seven = sevenFields();
        ExperienceDraft d = draftOf(verdict(GOOD, "WRITE_EXPERIENCE"), fullMemo("hp=12/20, armor=none"));
        ExperienceQualityGate.Result r = ExperienceQualityGate.evaluate(
                seven, fullMemo("hp=12/20, armor=none"), d.toJson().get("description").getAsString());
        assertFalse(r.hasHardFailure(), "不该有硬失败：" + r.failedIds() + " " + r.undecidableIds());
        // Q2/Q4 判不了 ⇒ 顶层不能报 PASS（Codex P1-2：那会变成假绿）
        assertEquals(ExperienceQualityGate.Verdict.REVIEW_REQUIRED, r.verdict());
        assertTrue(r.undecidableIds().contains("Q2"), r.undecidableIds().toString());
        assertTrue(r.undecidableIds().contains("Q4"), r.undecidableIds().toString());
    }

    /** 本批的关键诚实性：判不了的必须说判不了，不能报 PASS。 */
    @Test
    void gateNeverReportsPassWhileSomethingIsUndecidable() {
        Experience seven = sevenFields();
        ExperienceQualityGate.Result r = ExperienceQualityGate.evaluate(
                seven, fullMemo("hp=12/20"), seven.mechanism() + seven.derivation());
        assertEquals(ExperienceQualityGate.Verdict.REVIEW_REQUIRED, r.verdict());
        assertTrue(r.undecidableCount() >= 2);
    }

    @Test
    void gateRejectsWhenSevenFieldsAreIncomplete() {
        Experience seven = Experience.parse(obj(
                "{\"experience\":{\"mechanism\":\"m\",\"preconditions\":\"p\",\"derivation\":\"d\","
                        + "\"efficiency\":\"e\",\"evidence\":\"v\"}}"));
        ExperienceQualityGate.Result r = ExperienceQualityGate.evaluate(
                seven, fullMemo("hp=12/20"), seven.mechanism());
        assertTrue(r.hasHardFailure());
        assertEquals(ExperienceQualityGate.Verdict.REJECT, r.verdict());
        assertTrue(r.failedIds().contains("Q0"), r.failedIds().toString());
        assertTrue(r.failedIds().contains("Q5"), "缺失效条件 ⇒ 不知道什么时候会害人");
    }

    @Test
    void gateRejectsEmptyAdviceSentence() {
        // doc 59 §6.2 最后一条：「不能只写『应该注意安全』这种不可执行句子」
        String advice = "{\"mechanism\":\"应该注意安全\",\"preconditions\":\"p\","
                + "\"failureConditions\":\"f\",\"observableSignal\":\"s\","
                + "\"derivation\":\"d\",\"efficiency\":\"e\",\"evidence\":\"v\"}";
        Experience seven = Experience.parse(obj("{\"experience\":" + advice + "}"));
        ExperienceQualityGate.Result r = ExperienceQualityGate.evaluate(
                seven, fullMemo("hp=12/20, armor=none"), seven.mechanism());
        assertTrue(r.failedIds().contains("Q6"), r.failedIds().toString());
    }

    /** ★ Codex P2-6：前缀匹配会误伤「注意安全：立即停止挖掘并…」这种正常句子。 */
    @Test
    void gateAcceptsAdviceLikeSentenceThatIsActuallyActionable() {
        String advice = "{\"mechanism\":\"注意安全：血量低于六格就立刻停止挖掘并先铺水再回撤\","
                + "\"preconditions\":\"y<=-12\",\"failureConditions\":\"开阔熔岩湖上方\","
                + "\"observableSignal\":\"血量<6\",\"derivation\":\"停止→铺水→回升\","
                + "\"efficiency\":\"省一次返工\",\"evidence\":\"ep7\"}";
        Experience seven = Experience.parse(obj("{\"experience\":" + advice + "}"));
        ExperienceQualityGate.Result r = ExperienceQualityGate.evaluate(
                seven, fullMemo("hp=12/20, armor=none"), seven.mechanism());
        assertFalse(r.failedIds().contains("Q6"),
                "以「注意安全」开头但后面就是动作的句子不该被当成空话：" + r.failedIds());
    }

    @Test
    void gateRejectsWhenEventChainIsIncomplete() {
        Experience seven = sevenFields();
        // 没试过什么、也没环境快照 ⇒ 说不出「当时到底发生了什么」
        Memo thin = new Memo("m-1", "往下挖掉血", "mining", "", "", 1000L);
        ExperienceQualityGate.Result r = ExperienceQualityGate.evaluate(
                seven, thin, seven.mechanism() + seven.derivation());
        assertTrue(r.failedIds().contains("Q1"), r.failedIds().toString());
    }

    /** ★ Codex P2-9：snapshot="garbage" 非空，但不构成任何现场事实。 */
    @Test
    void gateRejectsUnreadableSnapshot() {
        Experience seven = sevenFields();
        Memo junk = new Memo("m-1", "往下挖掉血", "mining", "先直挖", "garbage", 1000L);
        ExperienceQualityGate.Result r = ExperienceQualityGate.evaluate(
                seven, junk, seven.mechanism() + seven.derivation());
        assertTrue(r.failedIds().contains("Q1"),
                "快照读不出任何键值或血量 ⇒ 说不出当时发生了什么：" + r.failedIds());
    }

    @Test
    void gateRejectsMissingExperienceEntirely() {
        ExperienceQualityGate.Result r = ExperienceQualityGate.evaluate(null, fullMemo("hp=1"), null);
        assertEquals(ExperienceQualityGate.Verdict.REJECT, r.verdict());
        assertEquals("Q0", r.checks().get(0).id());
    }

    // ---------- 死亡判据（Codex P1-5 给了 6 个反例，逐个钉住） ----------

    @Test
    void hpIsBoundToItsOwnKeyNotToAnyNumberThatLooksLikeZero() {
        assertEquals(6.0, ExperienceQualityGate.hpFromSnapshot("hp=6/20"));
        assertEquals(6.0, ExperienceQualityGate.hpFromSnapshot("health=6"));
        // food / max_hp 都不是血量
        assertEquals(20.0, ExperienceQualityGate.hpFromSnapshot("hp=20/20, food=0/20"),
                "food=0/20 不能被当血量");
        assertNull(ExperienceQualityGate.hpFromSnapshot("max_hp=0"), "max_hp 不是当前血量");
        // 小数尾巴不能被误匹配成分数
        assertEquals(1.0, ExperienceQualityGate.hpFromSnapshot("hp=1.0/20"));
        // 负值 / 残缺 / 分母 0 / 非数字 一律读不到
        assertNull(ExperienceQualityGate.hpFromSnapshot("hp=-1"));
        assertNull(ExperienceQualityGate.hpFromSnapshot("hp=0/"), "分母残缺 = 读不到，不是血量 0");
        assertNull(ExperienceQualityGate.hpFromSnapshot("hp=0/0"), "分母 0 = 读不到");
        assertNull(ExperienceQualityGate.hpFromSnapshot("hp=abc"));
        assertNull(ExperienceQualityGate.hpFromSnapshot(""));
        assertNull(ExperienceQualityGate.hpFromSnapshot(null));
    }

    @Test
    void hpZeroIsTheOnlyDeathEvidence() {
        assertEquals(0.0, ExperienceQualityGate.hpFromSnapshot("hp=0/20"));
        assertEquals(0.0, ExperienceQualityGate.hpFromSnapshot("\"hp\": 0"));
        assertEquals(0.0, ExperienceQualityGate.hpFromSnapshot("0/20"), "裸 N/M 写法也认");
        // 濒死不等于死亡
        assertEquals(6.0, ExperienceQualityGate.hpFromSnapshot("hp=6/20"));
    }

    /** 死亡现场存在时 Q3 从 UNDECIDABLE 变 PASS。 */
    @Test
    void deathEvidenceUpgradesQ3() {
        Experience seven = sevenFields();
        ExperienceQualityGate.Result dead = ExperienceQualityGate.evaluate(
                seven, fullMemo("hp=0/20, armor=none"), seven.mechanism() + seven.derivation());
        ExperienceQualityGate.Result alive = ExperienceQualityGate.evaluate(
                seven, fullMemo("hp=9/20, armor=none"), seven.mechanism() + seven.derivation());
        assertEquals(ExperienceQualityGate.Status.PASS, statusOf(dead, "Q3"));
        assertEquals(ExperienceQualityGate.Status.UNDECIDABLE, statusOf(alive, "Q3"),
                "活着不是死亡证据，但这也不能说「没发生死亡」，所以是 UNDECIDABLE 不是 FAIL");
    }

    private static ExperienceQualityGate.Status statusOf(ExperienceQualityGate.Result r, String id) {
        for (ExperienceQualityGate.Check c : r.checks()) {
            if (c.id().equals(id)) {
                return c.status();
            }
        }
        return fail("门禁里没有这条检查：" + id + "，检查项集合变了");
    }

    // ---------- 输出形状 ----------

    @Test
    void gateMapIsSelfDescribing() {
        Experience seven = sevenFields();
        JsonObject m = ExperienceQualityGate.evaluate(
                seven, fullMemo("hp=12/20"), seven.mechanism() + seven.derivation()).toMap();
        assertTrue(m.has("verdict"));
        assertTrue(m.has("hard_fail"));
        assertTrue(m.has("undecidable"));
        assertTrue(m.has("needs_review"), "判不了的项必须能被机器读到，不能只埋在 checks 里");
        assertEquals(7, m.getAsJsonArray("checks").size(), "Q0..Q6 七条都在，缺一条就看不见");
        assertNotNull(m.getAsJsonArray("checks").get(0).getAsJsonObject().get("why"));
    }
}