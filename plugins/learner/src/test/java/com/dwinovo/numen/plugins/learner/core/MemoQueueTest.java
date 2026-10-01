package com.dwinovo.numen.plugins.learner.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 队列与携带器的行为契约。
 *
 * <p>重点覆盖 2026-09-29 Codex 审出的三个真 bug 的回归：
 * ①读失败被当成空队列 → 覆盖原文件丢数据
 * ②restore 无上限
 * ③armor=none 被 contains 判成「有护甲」
 */
class MemoQueueTest {

    private static Memo memo(String id, String problem) {
        return new Memo(id, problem, "stage-a", "tried something", "hp=10/20", 1L);
    }

    @Test
    void appendAndDrainRoundTrip(@TempDir Path dir) {
        MemoQueue q = new MemoQueue(dir.resolve("m.json"));
        assertTrue(q.append(memo("m-1", "could not mine iron")));
        assertTrue(q.append(memo("m-2", "creeper blew up the tunnel")));
        assertEquals(2, q.size());

        List<Memo> taken = q.drain(5);
        assertEquals(2, taken.size());
        // drain 不删：只有 commit 才删
        assertEquals(2, q.size());

        assertEquals(2, q.commitDrain(taken));
        assertEquals(0, q.size());
    }

    @Test
    void commitDrainRemovesOnlyTakenMemos(@TempDir Path dir) {
        MemoQueue q = new MemoQueue(dir.resolve("m.json"));
        q.append(memo("m-1", "a"));
        q.append(memo("m-2", "b"));
        q.append(memo("m-3", "c"));

        List<Memo> taken = q.drain(2);
        assertEquals(2, q.commitDrain(taken));
        // 第三条不该被动
        assertEquals(1, q.size());
        assertEquals("m-3", q.all().get(0).id());
    }

    /** 复盘失败必须把数据放回去 —— 「清空队列」不等于「丢数据」。 */
    @Test
    void restorePutsTakenMemosBack(@TempDir Path dir) {
        MemoQueue q = new MemoQueue(dir.resolve("m.json"));
        q.append(memo("m-1", "a"));
        q.append(memo("m-2", "b"));
        List<Memo> taken = q.drain(5);

        assertEquals(2, q.restore(taken));
        assertEquals(2, q.size());
    }

    @Test
    void restoreIsIdempotent(@TempDir Path dir) {
        MemoQueue q = new MemoQueue(dir.resolve("m.json"));
        q.append(memo("m-1", "a"));
        List<Memo> taken = q.drain(5);
        q.restore(taken);
        q.restore(taken);
        assertEquals(1, q.size(), "restore 两次不该产生重复条目");
    }

    @Test
    void queueRespectsCapacity(@TempDir Path dir) {
        MemoQueue q = new MemoQueue(dir.resolve("m.json"));
        // ★ 下面的 64 是【字面量】，刻意不写 MemoQueue.MAX_QUEUE。
        //   变异测试实测：写成常量时把 MAX_QUEUE 改成 63，本测试【不会红】——
        //   期望值和被测值一起变，断言成了「队列遵守它自己的容量」而不是「容量是 64」。
        //   要改容量：先改这里（让它红，确认你知道在改契约），再改常量。
        for (int i = 0; i < 64; i++) {
            assertTrue(q.append(memo("m-" + i, "p" + i)));
        }
        assertFalse(q.append(memo("overflow", "one too many")), "超出上限必须拒收，不能静默丢");
        assertEquals(64, q.size());
    }

    /** Codex 审出的 P1：restore 原本无上限，可能突破 MAX_QUEUE。 */
    @Test
    void restoreDoesNotExceedCapacity(@TempDir Path dir) {
        MemoQueue q = new MemoQueue(dir.resolve("m.json"));
        for (int i = 0; i < 64; i++) {
            q.append(memo("m-" + i, "p" + i));
        }
        List<Memo> all = q.all();
        int depth = q.restore(all);
        assertTrue(depth <= 64, "restore 后深度不得超过上限，实际 " + depth);
    }

    /**
     * ★ 契约钉死点：把「容量常量应该是多少」写成一条独立断言。
     *
     * <p>有了这条，任何人改 {@link MemoQueue#MAX_QUEUE} 都会立刻红，
     * 逼他 consciously 确认「64 是不是该改」，而不是悄悄改掉、
     * 让上面两个行为测试的期望值跟着一起漂移。
     */
    @Test
    void capacityConstantIsPinnedTo64() {
        assertEquals(64, MemoQueue.MAX_QUEUE, "队列容量契约是 64。要改就改这里并同步改行为测试里的字面量");
        assertEquals(4000, MemoQueue.MAX_FIELD_CHARS, "单条最大长度契约是 4000");
    }

    @Test
    void oversizedMemoIsRejected(@TempDir Path dir) {
        MemoQueue q = new MemoQueue(dir.resolve("m.json"));
        String huge = "x".repeat(MemoQueue.MAX_FIELD_CHARS + 1);
        assertFalse(q.append(new Memo("m-big", huge, "s", "t", "", 1L)));
        assertEquals(0, q.size());
    }

    /**
     * Codex 审出的 P1（最危险的一条）：读失败曾被当成空队列，
     * 紧接着 append 的全量重写会覆盖原文件 → 全部备忘录静默丢失。
     * 修复后读失败必须抛，且原文件内容一字不改。
     */
    @Test
    void corruptQueueThrowsAndDoesNotOverwrite(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("m.json");
        Files.writeString(file, "{ this is not valid json");
        MemoQueue q = new MemoQueue(file);

        assertThrows(IllegalStateException.class, q::size);
        assertThrows(IllegalStateException.class, () -> q.append(memo("m-1", "new")));

        assertEquals("{ this is not valid json", Files.readString(file),
                "读失败后原文件必须原样保留，绝不能被空队列覆盖");
    }

    @Test
    void stateSurvivesReopen(@TempDir Path dir) {
        Path file = dir.resolve("m.json");
        MemoQueue first = new MemoQueue(file);
        first.append(memo("m-1", "persisted"));
        // 换一个实例（新会话/重启）读同一文件
        MemoQueue second = new MemoQueue(file);
        assertEquals(1, second.size());
        assertEquals("persisted", second.all().get(0).problem());
    }

    @Test
    void emptyFileIsTreatedAsEmptyQueue(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("m.json");
        Files.writeString(file, "");
        MemoQueue q = new MemoQueue(file);
        assertEquals(0, q.size());
        assertTrue(q.append(memo("m-1", "ok")));
    }

    // ---------- 携带器三段分级 ----------

    /**
     * Codex 审出的 P1：{@code contains("armor")} 会把 "armor=none" 判成有护甲。
     * 携带器给出的清单会正好反过来 —— 假事实。
     */
    @Test
    void carrierDoesNotTreatArmorNoneAsEquipped(@TempDir Path unused) {
        Memo m = new Memo("m-1", "p", "s", "t", "hp=6/20, armor=none, nearby=zombie", 1L);
        Memo.CarrierAssessment a = m.assessCarrier();
        assertEquals("HOSTILE_NEARBY", a.target());
        assertTrue(a.why().contains("护甲=无"), "armor=none 必须判为无护甲，实际: " + a.why());
        assertTrue(a.carryList().contains("护甲获取类工具"));
    }

    @Test
    void carrierRecognisesRealArmor(@TempDir Path unused) {
        Memo m = new Memo("m-1", "p", "s", "t", "hp=20/20, armor=iron_chestplate, weapon=iron_sword", 1L);
        Memo.CarrierAssessment a = m.assessCarrier();
        assertTrue(a.why().contains("护甲=有"), a.why());
        assertTrue(a.why().contains("武器=有"), a.why());
        assertEquals("HIGH", a.why().split("血量=")[1].split("\\(")[0]);
    }

    @Test
    void carrierGradesHealth(@TempDir Path unused) {
        assertEquals("CRITICAL", bandOf("hp=3/20"));
        assertEquals("LOW", bandOf("hp=8/20"));
        assertEquals("MID", bandOf("hp=12/20"));
        assertEquals("HIGH", bandOf("hp=18/20"));
    }

    @Test
    void carrierHandlesJsonStyleSnapshot(@TempDir Path unused) {
        // 修过的解析要吃 '"hp": 7' 这种带分隔符的写法（Codex 指出原实现在 ':' 处解析失败）
        Memo m = new Memo("m-1", "p", "s", "t", "{\"hp\": 7, \"armor\": \"none\"}", 1L);
        Memo.CarrierAssessment a = m.assessCarrier();
        assertEquals(7, a.hp(), "应能从 JSON 风格快照抓出血量");
        assertTrue(a.why().contains("护甲=无"), a.why());
    }

    @Test
    void carrierReportsUnknownWhenNoSnapshot(@TempDir Path unused) {
        Memo m = new Memo("m-1", "p", "s", "t", "", 1L);
        Memo.CarrierAssessment a = m.assessCarrier();
        assertEquals("UNKNOWN", a.target());
        assertEquals(-1, a.hp());
        assertNotNull(a.carryList());
    }

    private static String bandOf(String snapshot) {
        Memo m = new Memo("m-1", "p", "s", "t", snapshot, 1L);
        String why = m.assessCarrier().why();
        return why.split("血量=")[1].split("\\(")[0];
    }
}
