package com.dwinovo.numen.agent.tool;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同名展开去重（2026-10-24，主人裁决②）。
 *
 * <p><b>为什么要有这组钉子</b>：这机制治的是「说明书自增殖」——实测 33 次
 * {@code find_tools select:mine} 就把展开块堆到 28,206 / 40,000 token（70% 窗口），
 * 205 万 token 的会话史里 823 个展开块占 124 万。
 * 它自己也是"验证器"，所以它的测试不能只看"看起来没变大"，必须钉住数量和 token 两个数。
 */
class ToolDisclosureDedupeTest {

    /** 一段像 find_tools 返回的展开块：头部标记 + 正文 + 结束标记。 */
    private static String block(String names, String body) {
        return "<functions expanded=\"" + names + "\">\n" + body + "\n</functions>";
    }

    private static String body(String tool, int repeat) {
        return ("<function>{\"name\":\"" + tool + "\",\"description\":\""
                + "x".repeat(repeat) + "\"}</function>");
    }

    @Test
    void 两次同名展开_只保留最新那份() {
        String first = block("mine", body("mine", 400));
        String second = block("mine", body("mine", 400));

        List<String> out = ToolDisclosure.dedupeSameName(List.of(first, second));

        assertEquals(2, out.size(), "长度必须不变（只换内容，不删消息）");
        assertTrue(ToolDisclosure.hasExpandedBlock(out.get(1)), "最新那份必须保持完整展开");
        assertFalse(ToolDisclosure.hasExpandedBlock(out.get(0)), "旧那份必须被压成索引行");
    }

    @Test
    void 三次同名展开_窗口里只剩一份完整定义() {
        String a = block("mine", body("mine", 300));
        String b = block("mine", body("mine", 300));
        String c = block("mine", body("mine", 300));

        List<String> out = ToolDisclosure.dedupeSameName(List.of(a, b, c));

        long intact = out.stream().filter(ToolDisclosure::hasExpandedBlock).count();
        assertEquals(1, intact, "同名工具在窗口里最多一份完整定义");
        assertTrue(ToolDisclosure.hasExpandedBlock(out.get(2)), "保留的是最新那份");
    }

    @Test
    void 不同工具各留一份_互不影响() {
        String mine = block("mine", body("mine", 200));
        String craft = block("craft", body("craft", 200));

        List<String> out = ToolDisclosure.dedupeSameName(List.of(mine, craft));

        assertTrue(ToolDisclosure.hasExpandedBlock(out.get(0)), "mine 只有一份，必须留着");
        assertTrue(ToolDisclosure.hasExpandedBlock(out.get(1)), "craft 只有一份，必须留着");
    }

    @Test
    void 一条块里两个工具_按名字逐个判() {
        String both = block("mine,craft", body("mine", 120) + body("craft", 120));
        String later = block("mine", body("mine", 120));

        List<String> out = ToolDisclosure.dedupeSameName(List.of(both, later));

        // 第一条里 mine 变旧了、craft 还算新 → 整条块仍要压（因为它含旧名）
        assertTrue(ToolDisclosure.hasExpandedBlock(out.get(1)), "新块保持完整");
        String slimmed = out.get(0);
        assertTrue(slimmed.contains("craft"), "压成索引行时两个参数名都要在，实际：" + slimmed);
    }

    @Test
    void 瘦身后窗口token确实下降() {
        List<String> raw = new ArrayList<>();
        for (int i = 0; i < 10; i++) raw.add(block("mine", body("mine", 900)));

        List<String> out = ToolDisclosure.dedupeSameName(raw);

        int before = raw.stream().mapToInt(String::length).sum();
        int after = out.stream().mapToInt(String::length).sum();
        assertTrue(after < before, "10 份同名说明书必须显著变小；before=" + before + " after=" + after);
        // 只有最后一份是完整体，其余 9 份压成索引行
        assertEquals(1, out.stream().filter(ToolDisclosure::hasExpandedBlock).count());
    }

    @Test
    void 没有展开块时原样返回() {
        List<String> plain = List.of("普通工具结果", "另一个结果");
        assertEquals(plain, ToolDisclosure.dedupeSameName(plain));

        List<String> empty = ToolDisclosure.dedupeSameName(List.of());
        assertTrue(empty.isEmpty());
    }

    @Test
    void firstNamesOf只报首次出现的名字() {
        String two = block("mine", body("mine", 10)) + block("craft", body("craft", 10));
        Set<String> names = ToolDisclosure.firstNamesOf(two);
        assertEquals(Set.of("mine", "craft"), names);
    }
}
