package com.dwinovo.numen.agent.context;

import com.dwinovo.numen.agent.context.ContextBudget.Allocation;
import com.dwinovo.numen.agent.context.ContextBudget.Section;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * G4b 契约（IC-75 §15.5 ContextBudget）：必知区永不裁、可选整段丢、拒绝不返回空、结果确定。
 *
 * <p>这些断言不是为了"跑通"，是为了钉住三件在实机上很难复现的事：
 * ①必知区超限时必须 DENIED 而不是悄悄少一段；②可选段落必须是整段丢（文本原样，不是半句）；
 * ③同一输入两次分配必须逐字节相同（否则"这轮为什么少了背包"无法解释）。
 */
class ContextBudgetTest {

    private static Section mandatory(String name, int tokens, String text) {
        return new Section(name, text, true, tokens);
    }

    private static Section optional(String name, int tokens, String text) {
        return new Section(name, text, false, tokens);
    }

    private static ContextBudget tokens(long max) {
        return new ContextBudget(0, 0, 0, max, 0L, 0L, "compact-split-v1");
    }

    private static ContextBudget chars(long max) {
        return new ContextBudget(0, 0, 0, 0L, max, 0L, "compact-split-v1");
    }

    @Test
    void unlimitedKeepsEverythingAndNeverReportsTruncation() {
        List<Section> sections = List.of(
                mandatory("current_task", 300, "<t/>"),
                optional("inventory", 900, "<i/>"),
                optional("effects", 50, "<e/>"));
        Allocation a = ContextBudget.allocate(ContextBudget.unlimited(), sections);

        assertEquals(ContextBudget.OK, a.status());
        assertEquals("", a.reasonCode());
        assertEquals(3, a.kept().size());
        assertTrue(a.dropped().isEmpty());
        assertTrue(a.truncationReasons().isEmpty());
        assertEquals(1250L, a.tokensUsed());
        assertEquals(12L, a.charsUsed());
        assertTrue(ContextBudget.unlimited().isUnlimited());
        assertEquals("unlimited", ContextBudget.unlimited().describe());
    }

    @Test
    void nullBudgetAndNullSectionsAreSafe() {
        assertTrue(ContextBudget.allocate(null, null).kept().isEmpty());
        assertEquals(ContextBudget.OK, ContextBudget.allocate(null, List.of()).status());
        assertEquals(ContextBudget.OK,
                ContextBudget.allocate(tokens(10), List.of(optional("empty", 0, ""))).status());
    }

    @Test
    void mandatoryOverflowIsDeniedButStillListsEveryMandatorySection() {
        // 判据 S82：必知区 900 > 上限 800 → DENIED + CONSTRAINT_OVERFLOW，且 kept 仍是全部必知区。
        Allocation a = ContextBudget.allocate(tokens(800), List.of(
                mandatory("current_task", 600, "<task>干活</task>"),
                mandatory("plugin:rdd", 300, "<rdd>需求缺口</rdd>"),
                optional("inventory", 100, "<inv/>")));

        assertEquals(ContextBudget.DENIED, a.status());
        assertTrue(a.denied());
        assertEquals(ContextBudget.CONSTRAINT_OVERFLOW, a.reasonCode());
        assertEquals(List.of("current_task", "plugin:rdd"), a.keptNames(),
                "拒绝不等于返回空：必知区必须原样列出来，否则调用方没法说清「差多少」");
        assertFalse(a.kept().isEmpty());
        assertEquals(900L, a.tokensUsed());
        assertEquals(List.of("inventory"), a.droppedNames(), "必知区都装不下时，可选段落一个也不给");
        assertEquals(List.of("inventory:DENIED"), a.truncationReasons());
    }

    @Test
    void mandatoryCharOverflowIsDeniedToo() {
        // 字符上限是独立硬上限，不能被 token 数掩盖（§15.5）。
        String big = "字".repeat(50);
        Allocation a = ContextBudget.allocate(chars(20), List.of(
                mandatory("current_task", 10, big),
                optional("inventory", 1, "<i/>")));
        assertEquals(ContextBudget.DENIED, a.status());
        assertEquals(ContextBudget.CONSTRAINT_OVERFLOW, a.reasonCode());
        assertEquals(50L, a.charsUsed());
    }

    @Test
    void optionalSectionsAreDroppedWholeAfterMandatoryAndNeverSliced() { // 判据 S83
        String inventory = "<inventory>" + "铁锭 x64 ".repeat(10) + "</inventory>";
        List<Section> sections = List.of(
                mandatory("current_task", 200, "<task/>"),
                optional("inventory", 300, inventory),
                optional("effects", 100, "<effects/>"),
                optional("riding", 100, "<riding/>"));
        Allocation a = ContextBudget.allocate(tokens(500), sections);

        assertEquals(ContextBudget.TRUNCATED, a.status());
        assertEquals(List.of("current_task", "inventory"), a.keptNames());
        assertEquals(List.of("effects", "riding"), a.droppedNames());
        assertEquals(inventory, a.kept().get(1).text(), "整段保留，绝不切半句");
        assertEquals(500L, a.tokensUsed());
        assertEquals(List.of("effects:TOKEN_LIMIT", "riding:TOKEN_LIMIT"), a.truncationReasons());
    }

    @Test
    void allocationIsDeterministic() {
        List<Section> sections = List.of(
                mandatory("current_task", 200, "<task/>"),
                optional("inventory", 300, "<inv/>"),
                optional("effects", 100, "<fx/>"),
                optional("riding", 100, "<ride/>"));
        Allocation a = ContextBudget.allocate(tokens(500), sections);
        Allocation b = ContextBudget.allocate(tokens(500), sections);
        assertEquals(a.keptNames(), b.keptNames());
        assertEquals(a.droppedNames(), b.droppedNames());
        assertEquals(a.truncationReasons(), b.truncationReasons());
        assertEquals(a.tokensUsed(), b.tokensUsed());
        assertEquals(a.status(), b.status());
    }

    @Test
    void keptOrderFollowsInputOrder() {
        Allocation a = ContextBudget.allocate(ContextBudget.unlimited(), List.of(
                optional("a", 1, "a"), mandatory("m1", 1, "m"), optional("b", 1, "b"),
                mandatory("m2", 1, "m")));
        assertEquals(List.of("a", "m1", "b", "m2"), a.keptNames());
    }

    @Test
    void rowLimitCapsKeptOptionalCount() {
        ContextBudget b = new ContextBudget(0, 1, 0, 0L, 0L, 0L, "t");
        Allocation a = ContextBudget.allocate(b, List.of(
                optional("a", 1, "a"), optional("b", 1, "b"), optional("c", 1, "c")));
        assertEquals(List.of("a"), a.keptNames());
        assertEquals(List.of("b:ROW_LIMIT", "c:ROW_LIMIT"), a.truncationReasons());
    }

    @Test
    void candidateLimitCountsOptionalSectionsOffered() {
        ContextBudget b = new ContextBudget(1, 0, 0, 0L, 0L, 0L, "t");
        Allocation a = ContextBudget.allocate(b, List.of(
                optional("a", 1, "a"), optional("b", 1, "b"), optional("c", 1, "c")));
        assertEquals(List.of("a"), a.keptNames());
        assertEquals(List.of("b:CANDIDATE_LIMIT", "c:CANDIDATE_LIMIT"), a.truncationReasons());
    }

    @Test
    void mandatoryReserveKeepsHeadroomForConstraints() {
        // 必知区现在只占 100，但给它留了 800：可选段落只能用到 200。
        ContextBudget b = new ContextBudget(0, 0, 0, 1000L, 0L, 800L, "t");
        Allocation a = ContextBudget.allocate(b, List.of(
                mandatory("current_task", 100, "<task/>"),
                optional("inventory", 300, "<inv/>"),
                optional("effects", 150, "<fx/>")));
        assertEquals(List.of("current_task", "effects"), a.keptNames());
        assertEquals(List.of("inventory:TOKEN_LIMIT"), a.truncationReasons());
        assertEquals(250L, a.tokensUsed());
    }

    @Test
    void charLimitIsIndependentOfTokenCount() {
        ContextBudget b = chars(10);
        Allocation a = ContextBudget.allocate(b, List.of(
                optional("short", 1, "0123456789"),
                optional("long", 1, "0123456789A")));
        assertEquals(List.of("short"), a.keptNames());
        assertEquals(List.of("long:CHAR_LIMIT"), a.truncationReasons());
        assertEquals(10L, a.charsUsed());
    }

    @Test
    void sectionNormalisesNullsAndNegatives() {
        Section s = new Section("x", null, false, -5);
        assertEquals("", s.text());
        assertEquals(0, s.tokens());
        assertEquals(0, s.chars());
        assertTrue(s.isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new Section(" ", "t", false, 1));
    }

    @Test
    void higherPrioritySectionsSurviveEvenWhenListedLast() {
        // 2026-10-20 实机现场改出来的规则：正文顺序（拼进提示词）与保留优先级是两件事。
        // 插件片段排在正文最后，却是最丢不起的——按「输入顺序优先」就会先丢它，正是要避免的。
        Allocation a = ContextBudget.allocate(chars(20), List.of(
                new Section("inventory", "0123456789A", false, 1, 10),   // 低优先，却列在最前
                new Section("plugin_fragments", "0123456789", false, 1, 40)));  // 高优先，却列在最后
        assertEquals(List.of("plugin_fragments"), a.keptNames(), "高优先级的先保留，哪怕它在正文里排最后");
        assertEquals(List.of("inventory"), a.droppedNames());
        assertEquals(List.of("inventory:CHAR_LIMIT"), a.truncationReasons());
    }

    @Test
    void keptAndDroppedAreReportedInInputOrderNotPriorityOrder() {
        Allocation a = ContextBudget.allocate(chars(30), List.of(
                new Section("a_low", "0".repeat(20), false, 1, 1),
                new Section("m", "x", true, 0, 0),
                new Section("z_high", "1".repeat(14), false, 1, 9)));
        // 上限 30：必知区 1 → 剩 29；z_high(14) 先保留 → 剩 15；a_low(20) 放不下 → 丢。
        assertEquals(List.of("m", "z_high"), a.keptNames(), "输出按输入顺序（拼回去的字节不能变）");
        assertEquals(List.of("a_low:CHAR_LIMIT"), a.truncationReasons());
    }

    @Test
    void describeExposesEveryLimit() {
        String d = new ContextBudget(2, 3, 4, 1000L, 2000L, 500L, "compact-split-v1").describe();
        assertTrue(d.contains("tokens=1000"));
        assertTrue(d.contains("chars=2000"));
        assertTrue(d.contains("reserve=500"));
        assertTrue(d.contains("rows=3"));
        assertTrue(d.contains("tokenizer=compact-split-v1"));
    }
}
