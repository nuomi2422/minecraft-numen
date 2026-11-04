package com.dwinovo.numen.plugins.worldstate.provider;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 039 U5#8：样本按距离排序（近的在前），坏样本不丢、排最后。 */
class BlockSampleSortTest {

    @Test
    void nearestFirst() {
        List<String> in = List.of(
                "minecraft:wheat[age=7]@10,64,10",
                "minecraft:wheat[age=7]@1,64,1",
                "minecraft:wheat[age=7]@5,64,5");
        List<String> out = BlockSpec.sortByDistance(in, 0, 64, 0);
        assertEquals("minecraft:wheat[age=7]@1,64,1", out.get(0));
        assertEquals("minecraft:wheat[age=7]@5,64,5", out.get(1));
        assertEquals("minecraft:wheat[age=7]@10,64,10", out.get(2));
    }

    @Test
    void malformedSamplesGoLastAndAreKept() {
        List<String> in = List.of("bad-sample", "minecraft:wheat[age=7]@0,64,2");
        List<String> out = BlockSpec.sortByDistance(in, 0, 64, 0);
        assertEquals("minecraft:wheat[age=7]@0,64,2", out.get(0));
        assertEquals("bad-sample", out.get(1), "解析不了的样本不许丢");
    }

    @Test
    void emptyInEmptyOut() {
        assertEquals(List.of(), BlockSpec.sortByDistance(List.of(), 0, 0, 0));
        assertEquals(List.of(), BlockSpec.sortByDistance(null, 0, 0, 0));
    }
    @Test
    void insertNearestKeepsTheClosestOnes() {
        java.util.List<String> list = new java.util.ArrayList<>();
        BlockSpec.insertNearest(list, "w@9,64,9", 2, 0, 64, 0);
        BlockSpec.insertNearest(list, "w@1,64,1", 2, 0, 64, 0);
        BlockSpec.insertNearest(list, "w@5,64,5", 2, 0, 64, 0);
        BlockSpec.insertNearest(list, "w@20,64,20", 2, 0, 64, 0);
        org.junit.jupiter.api.Assertions.assertEquals(2, list.size());
        org.junit.jupiter.api.Assertions.assertEquals("w@1,64,1", list.get(0));
        org.junit.jupiter.api.Assertions.assertEquals("w@5,64,5", list.get(1), "就近保留，远的被挤掉");
    }
}
