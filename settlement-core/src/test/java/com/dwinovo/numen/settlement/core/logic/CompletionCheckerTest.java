package com.dwinovo.numen.settlement.core.logic;

import com.dwinovo.numen.settlement.core.TestData;
import com.dwinovo.numen.settlement.core.accept.WorldProbe;
import com.dwinovo.numen.settlement.core.accept.EntityView;
import com.dwinovo.numen.settlement.core.model.BlockBox;
import com.dwinovo.numen.settlement.core.model.DimAnchor;
import com.dwinovo.numen.settlement.core.model.FacilityTemplate;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 施工收口的世界侧判据：任务说"结束"不等于"建好了"，答案只能从世界读。 */
class CompletionCheckerTest {

    private static final String DIM = TestData.DIM;

    /** 可写探针：key = "x,y,z"。 */
    private static final class FakeProbe implements WorldProbe {
        final Map<String, String> blocks = new HashMap<>();
        boolean loaded = true;

        void put(int x, int y, int z, String id) {
            blocks.put(x + "," + y + "," + z, id);
        }

        @Override public boolean loaded(String dimension, int x, int y, int z) {
            return loaded;
        }

        @Override public String blockIdAt(String dimension, int x, int y, int z) {
            return blocks.getOrDefault(x + "," + y + "," + z, "minecraft:air");
        }

        @Override public List<EntityView> entities(String dimension, BlockBox box) {
            return List.of();
        }
    }

    @Test
    void allMarksPresentIsComplete() {
        FakeProbe probe = new FakeProbe();
        // 锚点 (10,64,20)：标记在局部 (0,0,0) 与 (1,0,0)
        probe.put(10, 64, 20, "minecraft:oak_fence");
        probe.put(11, 64, 20, "minecraft:oak_fence");
        List<CompletionChecker.Mark> marks = List.of(
                new CompletionChecker.Mark(0, 0, 0, "minecraft:oak_fence"),
                new CompletionChecker.Mark(1, 0, 0, "minecraft:oak_fence"));

        CompletionChecker.Result r = CompletionChecker.check(marks,
                DimAnchor.of(DIM, 10, 64, 20), 5, 5, 0, probe);
        assertTrue(r.complete());
        assertEquals(2, r.matched());
        assertTrue(r.missing().isEmpty());
    }

    @Test
    void missingCellIsReportedWithItsWorldPosition() {
        FakeProbe probe = new FakeProbe();
        probe.put(10, 64, 20, "minecraft:oak_fence");
        List<CompletionChecker.Mark> marks = List.of(
                new CompletionChecker.Mark(0, 0, 0, "minecraft:oak_fence"),
                new CompletionChecker.Mark(1, 0, 0, "minecraft:oak_fence"));

        CompletionChecker.Result r = CompletionChecker.check(marks,
                DimAnchor.of(DIM, 10, 64, 20), 5, 5, 0, probe);
        assertFalse(r.complete());
        assertEquals(1, r.missingCount());
        assertTrue(r.missing().get(0).startsWith("11,64,20"), r.missing().toString());
    }

    @Test
    void wrongBlockCountsAsMissingNotPresent() {
        FakeProbe probe = new FakeProbe();
        probe.put(10, 64, 20, "minecraft:oak_fence");
        probe.put(11, 64, 20, "minecraft:oak_fence_gate");   // 该是栅栏
        List<CompletionChecker.Mark> marks = List.of(
                new CompletionChecker.Mark(0, 0, 0, "minecraft:oak_fence"),
                new CompletionChecker.Mark(1, 0, 0, "minecraft:oak_fence"));
        CompletionChecker.Result r = CompletionChecker.check(marks,
                DimAnchor.of(DIM, 10, 64, 20), 5, 5, 0, probe);
        assertFalse(r.complete());
        assertTrue(r.missing().get(0).contains("实为"), r.missing().toString());
    }

    @Test
    void unloadedChunkIsMissingNotSilentlyPassed() {
        FakeProbe probe = new FakeProbe();
        probe.loaded = false;
        List<CompletionChecker.Mark> marks = List.of(
                new CompletionChecker.Mark(0, 0, 0, "minecraft:oak_fence"));
        CompletionChecker.Result r = CompletionChecker.check(marks,
                DimAnchor.of(DIM, 10, 64, 20), 5, 5, 0, probe);
        assertFalse(r.complete(), "未加载不许当成功");
        assertTrue(r.missing().get(0).contains("未加载"));
    }

    @Test
    void rotationMovesMarkedCells() {
        // 局部 (0,0) 在 5×5 里顺时针 90° → (4,0)
        DimAnchor anchor = DimAnchor.of(DIM, 10, 64, 20);
        CompletionChecker.Mark m = new CompletionChecker.Mark(0, 0, 0, "minecraft:oak_fence");
        DimAnchor w = CompletionChecker.toWorld(m, anchor, 5, 5, 1);
        assertEquals(14, w.x());
        assertEquals(20, w.z());
    }

    @Test
    void penTemplateMarksMatchItsRealGeometry() {
        // 牧场的标记必须落在它自己的 5×5 外框上（角/边中点/门），不能凭空写
        FacilityTemplate pen = TemplateCatalog.byId("pen_basic").orElseThrow();
        assertFalse(pen.marks().isEmpty(), "牧场要有结构标记");
        for (CompletionChecker.Mark m : pen.marks()) {
            assertTrue(m.localX() >= 0 && m.localX() <= 4 && m.localZ() >= 0 && m.localZ() <= 4,
                    "标记必须在 5×5 内: " + m);
            boolean onPerimeter = m.localX() == 0 || m.localZ() == 0
                    || m.localX() == 4 || m.localZ() == 4;
            assertTrue(onPerimeter, "牧场标记必须在围栏上: " + m);
        }
        assertEquals(1, pen.marks().stream().filter(m -> m.blockId().contains("gate")).count(),
                "恰好一处门");
    }

    @Test
    void emptyMarksIsNotComplete() {
        FakeProbe probe = new FakeProbe();
        CompletionChecker.Result r = CompletionChecker.check(List.of(),
                DimAnchor.of(DIM, 0, 0, 0), 5, 5, 0, probe);
        assertFalse(r.complete(), "没有标记就不许判完成");
        assertEquals(0, r.total());
    }

    @Test
    void houseMarksIncludeFurnitureSoAMissingChestIsCaught() {
        // ★ 2026-10-09 实机抓到的谎报：D3 核心屋报「世界核对 11/11 COMPLETE」，
        //   而箱子根本没落地——因为标记只采了结构，家具不在采样里。
        //   用户要的是"还差什么"，家具漏了就是谎报完成。
        var house = TemplateCatalog.byId("core_house").orElseThrow();
        long furniture = house.marks().stream()
                .filter(m -> m.blockId().contains("chest") || m.blockId().contains("furnace")
                        || m.blockId().contains("bed") || m.blockId().contains("crafting_table"))
                .count();
        assertEquals(4, furniture, "四件家具都必须进标记: " + house.marks());

        // 而且缺箱子时必须判"未完成"
        FakeProbe probe = new FakeProbe();
        int ax = 100;
        int ay = 64;
        int az = 200;
        for (CompletionChecker.Mark m : house.marks()) {
            String block = m.blockId();
            if (block.contains("chest")) {
                continue;   // 故意不摆箱子
            }
            // 按标记的世界坐标摆上（旋转 0 时 = 锚点 + 局部）
            probe.put(ax + m.localX(), ay + m.localY(), az + m.localZ(), block);
        }
        CompletionChecker.Result r = CompletionChecker.check(house.marks(),
                DimAnchor.of(DIM, ax, ay, az), house.sizeX(), house.sizeZ(), 0, probe);
        assertFalse(r.complete(), "缺箱子必须判未完成");
        assertTrue(r.missing().stream().anyMatch(s -> s.contains("chest")),
                "缺失项要点名箱子: " + r.missing());
    }
}
