package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-05：R2 契约「错误时保留旧数据只能标历史，不能渲染成当前库存」。
 *
 * <p>现场代码：扫描失败会保留上一次缓存（对的），但 cached() 原样返回，
 * 文字是一句现在时的「你身边的容器里放着…」——旧数据看起来像当前库存。
 *
 * <p>这里钉住标记规则：超龄才标、标出年龄、内容不动。
 */
class RddStorageStalenessTest {

    private static final String TEXT =
            "<storage nearby=\"1\">你身边的容器里放着：(10,64,10) 木头×3。这些**不在背包里**</storage>";

    @Test
    void freshCacheIsUntouched() {
        assertEquals(TEXT, RddStorageStaleness.markStale(TEXT, 1_000L, 10_000L), "没超龄不许改字");
    }

    @Test
    void staleCacheGetsAgeAttributeAndPrefix() {
        String marked = RddStorageStaleness.markStale(TEXT, 42_000L, 10_000L);
        assertTrue(marked.contains("stale=\"true\""), "旧缓存必须带 stale 标记，实际：" + marked);
        assertTrue(marked.contains("age_s=\"42\""), "要说得清旧了多久，实际：" + marked);
        assertTrue(marked.contains("(42 秒前看到的)"), "文字不能再是现在时，实际：" + marked);
        assertTrue(marked.contains("木头×3"), "内容不许丢");
    }

    @Test
    void emptyTextStaysEmpty() {
        assertEquals("", RddStorageStaleness.markStale("", 99_000L, 10_000L));
    }

    @Test
    void boundaryIsNotStale() {
        assertFalse(RddStorageStaleness.markStale(TEXT, 10_000L, 10_000L).contains("stale="),
                "恰好等于阈值不算旧（<= 才不标）");
    }
}
