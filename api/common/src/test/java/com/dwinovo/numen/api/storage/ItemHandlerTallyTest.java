package com.dwinovo.numen.api.storage;

import com.dwinovo.numen.api.storage.ItemHandlerTally.Slot;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 037/T8b：能力视图合并口径。
 *
 * <p>钉住的是实机踩过的坑：同一个箱子按 7 个面各返回一个新包装对象，
 * 不按内容去重就会把数量读成 7 倍。
 */
class ItemHandlerTallyTest {

    @Test
    void identicalViewsFromEveryFaceAreCountedOnce() {
        List<Slot> view = List.of(new Slot("minecraft:iron_ingot", 9), new Slot("minecraft:wooden_hoe", 1));
        List<List<Slot>> sevenFaces = List.of(view, view, view, view, view, view, view);
        Map<String, Integer> merged = ItemHandlerTally.merge(sevenFaces);
        assertEquals(9, merged.get("minecraft:iron_ingot"), "7 个面不能读成 63");
        assertEquals(1, merged.get("minecraft:wooden_hoe"), "7 个面不能读成 7");
    }

    @Test
    void slotOrderAndSplittingDoNotChangeTheSignature() {
        List<Slot> a = List.of(new Slot("minecraft:torch", 5), new Slot("minecraft:bread", 2));
        List<Slot> b = List.of(new Slot("minecraft:bread", 2), new Slot("minecraft:torch", 5));
        List<Slot> c = List.of(new Slot("minecraft:torch", 3), new Slot("minecraft:torch", 2),
                new Slot("minecraft:bread", 2));
        assertEquals(5, ItemHandlerTally.merge(List.of(a, b)).get("minecraft:torch"), "顺序不同仍是同一份视图");
        assertEquals(5, ItemHandlerTally.merge(List.of(a, c)).get("minecraft:torch"), "拆格不影响签名");
    }

    @Test
    void genuinelyDifferentHandlersAreSummed() {
        List<Slot> chestA = List.of(new Slot("minecraft:coal", 10));
        List<Slot> chestB = List.of(new Slot("minecraft:coal", 4));
        assertEquals(14, ItemHandlerTally.merge(List.of(chestA, chestB)).get("minecraft:coal"),
                "内容不同的两个容器要相加");
    }

    @Test
    void emptyHandlersAndNullsContributeNothing() {
        assertTrue(ItemHandlerTally.merge(List.of()).isEmpty());
        assertTrue(ItemHandlerTally.merge(null).isEmpty());
        assertTrue(ItemHandlerTally.merge(List.of(List.of(), List.of())).isEmpty());
    }

    @Test
    void resultIsImmutableAndSorted() {
        Map<String, Integer> merged = ItemHandlerTally.merge(List.of(
                List.of(new Slot("minecraft:torch", 1), new Slot("minecraft:apple", 2))));
        assertThrows(UnsupportedOperationException.class, () -> merged.put("minecraft:air", 1));
        assertEquals(List.of("minecraft:apple", "minecraft:torch"), List.copyOf(merged.keySet()),
                "按 id 排序，跨次对比才稳定");
    }

    @Test
    void badSlotsAreRejectedInsteadOfSilentlyCounted() {
        assertThrows(IllegalArgumentException.class, () -> new Slot("  ", 1));
        assertThrows(IllegalArgumentException.class, () -> new Slot("minecraft:coal", 0),
                "0 个不该出现在非空视图里，安静吞掉会让调用方以为自己算漏了");
        assertThrows(IllegalArgumentException.class, () -> new Slot("minecraft:coal", -3));
    }
}
