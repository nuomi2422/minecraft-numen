package com.dwinovo.numen.plugins.learner.sample;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 158 §6 的指纹判据（159 §3 剧本 1 的前半）。
 *
 * <p>钉死四件事：归一化真的把「看起来一样」变成「字符串一样」；时间桶边界是左闭右开；
 * 强/弱指纹的差别<b>只在</b> cause；同伴/世界/目标变化必须换指纹。
 */
class SampleFingerprintTest {

    @Test
    void 全角与多余空白必须归一() {
        // 中文输入法产出全角数字和全角空格是常态；不归一化就会「同一死因两条指纹」
        assertEquals("zombie", SampleFingerprint.normalize("Ｚｏｍｂｉｅ"));
        assertEquals("a b c", SampleFingerprint.normalize("  a   b\n\tc  "));
        assertEquals("", SampleFingerprint.normalize(null));
        assertEquals("", SampleFingerprint.normalize("   "));
    }

    @Test
    void 归一化后大小写不敏感() {
        assertEquals(SampleFingerprint.normalize("GenericKill"), SampleFingerprint.normalize("generickill"));
    }

    @Test
    void 时间桶左闭右开() {
        long bucket = 1000L;
        assertEquals(0L, SampleFingerprint.bucketOf(0L, bucket));
        assertEquals(0L, SampleFingerprint.bucketOf(999L, bucket));
        assertEquals(1L, SampleFingerprint.bucketOf(1000L, bucket));
        assertEquals(1L, SampleFingerprint.bucketOf(1999L, bucket));
        assertEquals(2L, SampleFingerprint.bucketOf(2000L, bucket));
    }

    @Test
    void 时间桶对负数时间不能塌成0() {
        // floorDiv 才是对的：截断除法会把 -1 与 1 塞进同一个桶
        long bucket = 1000L;
        assertEquals(-1L, SampleFingerprint.bucketOf(-1L, bucket));
        assertEquals(-1L, SampleFingerprint.bucketOf(-1000L, bucket));
        assertNotEquals(SampleFingerprint.bucketOf(500L, bucket), SampleFingerprint.bucketOf(-500L, bucket));
    }

    @Test
    void 桶宽非法必须抛而不是静默退化() {
        assertThrows(IllegalArgumentException.class, () -> SampleFingerprint.bucketOf(1L, 0L));
        assertThrows(IllegalArgumentException.class, () -> SampleFingerprint.bucketOf(1L, -5L));
    }

    @Test
    void 归因不同则强指纹不同_但弱指纹相同() {
        String a = SampleFingerprint.of("c1", "death", "g1", "1", "zombie", "w1", "overworld", 7L);
        String b = SampleFingerprint.of("c1", "death", "g1", "1", "lava", "w1", "overworld", 7L);
        assertNotEquals(a, b, "死因不同不是同一件事");
        assertEquals(
                SampleFingerprint.weakOf("c1", "death", "g1", "1", "w1", "overworld", 7L),
                SampleFingerprint.weakOf("c1", "death", "g1", "1", "w1", "overworld", 7L),
                "弱指纹不看归因，用于标记疑似重复");
    }

    @Test
    void 同伴_世界_目标任一变化都换指纹() {
        String base = SampleFingerprint.of("c1", "death", "g1", "1", "zombie", "w1", "overworld", 7L);
        assertNotEquals(base, SampleFingerprint.of("c2", "death", "g1", "1", "zombie", "w1", "overworld", 7L));
        assertNotEquals(base, SampleFingerprint.of("c1", "death", "g1", "1", "zombie", "w2", "overworld", 7L));
        assertNotEquals(base, SampleFingerprint.of("c1", "death", "g1", "1", "zombie", "w1", "the_nether", 7L));
        assertNotEquals(base, SampleFingerprint.of("c1", "death", "g2", "1", "zombie", "w1", "overworld", 7L));
        assertNotEquals(base, SampleFingerprint.of("c1", "death", "g1", "2", "zombie", "w1", "overworld", 7L));
        assertNotEquals(base, SampleFingerprint.of("c1", "loop_detected", "g1", "1", "zombie", "w1", "overworld", 7L));
        assertNotEquals(base, SampleFingerprint.of("c1", "death", "g1", "1", "zombie", "w1", "overworld", 8L));
    }

    @Test
    void 强指纹稳定可复算() {
        // 跨进程幂等的前提：同样的输入必须给同样的串（不能掺 UUID / 系统时钟）
        String a = SampleFingerprint.of("c1", "death", "g1", "1", "Zombie", "w1", "overworld", 7L);
        String b = SampleFingerprint.of("c1", "death", "g1", "1", "zombie", "w1", "overworld", 7L);
        assertEquals(a, b, "归一化后必须相等");
    }

    @Test
    void 短哈希十二位且稳定() {
        String h1 = SampleFingerprint.shortHash("abc");
        String h2 = SampleFingerprint.shortHash("abc");
        assertEquals(h1, h2);
        assertEquals(12, h1.length());
        assertNotEquals(h1, SampleFingerprint.shortHash("abd"));
        assertTrue(h1.matches("[0-9a-f]{12}"));
        assertThrows(NullPointerException.class, () -> SampleFingerprint.shortHash(null));
    }
}
