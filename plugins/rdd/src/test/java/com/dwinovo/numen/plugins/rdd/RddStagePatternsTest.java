package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 「题型」骨架的护栏（纯文本，不碰 Minecraft）。
 *
 * <p>要钉住三件事：
 * <ol>
 *   <li><b>只在长线目标时出现</b>——普通目标多一个 token 都是浪费，而且会盖过经验库里的路线；</li>
 *   <li><b>有出口判据</b>——没有出口的阶段会互相吞，这是阶段名额被浪费掉的主因；</li>
 *   <li><b>给形状不给数量</b>——不许出现硬编码的「挖 32 个」「坐标」这类会被实况打脸的东西。</li>
 * </ol>
 */
class RddStagePatternsTest {

    @Test
    void 普通目标不注入() {
        assertEquals("", RddStagePatterns.block("造一个箱子"));
        assertEquals("", RddStagePatterns.block("挖 32 个圆石"));
        assertEquals("", RddStagePatterns.block(""), "空目标不能炸，也不能凭空长骨架");
        assertEquals("", RddStagePatterns.block(null));
    }

    @Test
    void 长线目标注入骨架() {
        for (String goal : new String[]{"通关MC", "打败末影龙", "beat minecraft", "打到末地"}) {
            String b = RddStagePatterns.block(goal);
            assertFalse(b.isBlank(), "「" + goal + "」应命中长线骨架");
            assertTrue(b.contains("<stage_patterns>") && b.contains("</stage_patterns>"),
                    "必须是可定位的独立块");
        }
    }

    @Test
    void 骨架覆盖关键环节且带出口判据() {
        String b = RddStagePatterns.block("通关MC");
        assertTrue(b.contains("末地"), "终局目标必须提末地：" + b);
        assertTrue(b.contains("烈焰"), "下界取烈焰棒是必经环节");
        assertTrue(b.contains("末影之眼"), "定位要塞靠眼睛");
        assertTrue(b.contains("出口："), "每个阶段都要有出口判据（否则阶段互相吞）");
        // 2026-10-28 实机：她攒够黑曜石却**没有打火石**（燧石4、铁锭0），门造好也点不着。
        assertTrue(b.contains("打火石"), "下界阶段必须点名点火物，否则会在门口干瞪眼：" + b);
        assertTrue(b.contains("燧石") && b.contains("铁锭"), "要写出打火石的配方，不然她不知道缺什么");
        // 2026-10-28 实机：规划器写了''烈焰弹点火''，而烈焰弹要烈焰粉（只能在下界拿）= 循环依赖。
        assertTrue(b.contains("循环依赖"), "必须点名''不要用烈焰弹''，否则规划器会选一个进不去下界就拿不到的材料");
    }

    @Test
    void 只给形状不给硬数量() {
        String b = RddStagePatterns.block("通关MC");
        assertFalse(b.matches(".*挖\\s*\\d+\\s*个.*"), "不许写死数量（实况会打脸）：" + b);
        assertFalse(b.matches(".*\\(\\s*-?\\d+\\s*,\\s*-?\\d+.*"), "不许写坐标");
    }

    @Test
    void 有界() {
        String b = RddStagePatterns.block("通关MC");
        assertTrue(b.length() < 1200, "规划 prompt 的附加段必须有界（实际 " + b.length() + " 字）");
    }
}
