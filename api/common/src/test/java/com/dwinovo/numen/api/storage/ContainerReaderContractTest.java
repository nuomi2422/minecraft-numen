package com.dwinovo.numen.api.storage;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 037/T8b：容器读能力的契约（不依赖 Minecraft）。
 *
 * <p>重点不是"能读到一个数字"，而是三种结果分得开：
 * 读到了有东西 / 读到了是空的 / 根本没读到。
 */
class ContainerReaderContractTest {

    /** 只为证明接口可用；真实实现由宿主提供。 */
    private static final class Fake implements ContainerReader {
        @Override
        public Result read(Level level, BlockPos pos) {
            if (pos.getX() < 0) return Result.unknown("chunk not loaded");
            Map<String, Integer> items = new LinkedHashMap<>();
            if (pos.getX() == 0 && pos.getY() == 0 && pos.getZ() == 0) return Result.known(items);   // 已知为空
            items.put("minecraft:torch", 8);
            items.put("minecraft:cooked_beef", 3);
            return Result.known(items);
        }
    }

    @Test
    void knownItemsAreCopyOnWrite() {
        ContainerReader.Result r = new Fake().read(null, new BlockPos(1, 64, 1));
        assertTrue(r.known());
        assertEquals(8, r.items().get("minecraft:torch"));
        assertThrows(UnsupportedOperationException.class, () -> r.items().put("minecraft:dirt", 1),
                "返回的 map 必须是不可变的，调用方不能改到实现的内部状态");
    }

    @Test
    void emptyContainerIsKnownNotEmptyUnknown() {
        ContainerReader.Result empty = new Fake().read(null, new BlockPos(0, 0, 0));
        assertTrue(empty.known(), "已知为空是 known，不是 unknown");
        assertTrue(empty.items().isEmpty());
        assertEquals(null, empty.reason());
    }

    @Test
    void unreadableContainerCarriesAReasonAndNoItems() {
        ContainerReader.Result unknown = new Fake().read(null, new BlockPos(-1, 64, 0));
        assertFalse(unknown.known());
        assertTrue(unknown.items().isEmpty(), "不知道的时候不许编出数量");
        assertNotNull(unknown.reason());
    }

    @Test
    void unknownWithoutReasonIsRejectedAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new ContainerReader.Result(false, Map.of(), "  "),
                "读不到却不说原因，等于把未知伪装成结论");
        assertThrows(IllegalArgumentException.class, () -> new ContainerReader.Result(false, Map.of(), null));
    }

    @Test
    void nullItemsAreNormalisedInsteadOfThrowing() {
        ContainerReader.Result r = new ContainerReader.Result(true, null, null);
        assertTrue(r.known());
        assertTrue(r.items().isEmpty(), "null 归一为空表，调用方不用到处判空");
    }
}