package com.dwinovo.numen.plugins.learner.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回放 2026-11-01T19:39:59Z 那批死亡 memo 的检索文本（fixture 来自真实日志与经验库条目）。
 *
 * <p>当时 3 条好草稿（lava/fall/zombie）全被判成与 {@code policy|debug_kill 走原版死亡流程}
 * 同族 → REVISE → topicMismatch 丢弃。根因是 debug_kill 条目 trigger_strings 里的裸 {@code death}。
 * 本回放断言：修复后这些文本不再命中 debug_kill；命中其他条目或 MISSING 都可接受。
 */
class RetrievalReplay193959Test {

    private static final String CID = "a43d753e-790d-472b-8d5b-dc18ac0afbc0";

    /** 真实 experience 库里 debug_kill 条目（第 3/4/5 条）的触发词与 tags，原样搬来。 */
    private static final String DEBUG_KILL_ENTRY =
            "{\"id\":\"policy|debug_kill 走原版死亡流程（entity.kill()，当刻结算）\","
                    + "\"title\":\"debug_kill 走原版死亡流程（entity.kill()，当刻结算）\","
                    + "\"trigger_strings\":[\"需要验证死亡/重生/资产回收链路，或需要一条干净的死亡事件与死亡账本对账时。\","
                    + "\"death\",\"debug_kill 死亡资产失效\",\"genericKill 自己被杀死 归因\",\"死亡前清点背包 验证闭环\"],"
                    + "\"tags\":[\"learner\",\"memo:ai-instr-884890204532900-5\",\"audience:executor\"]}";

    private static void writeStore(Path dir) throws Exception {
        Files.writeString(dir.resolve("experience-" + CID + ".jsonl"),
                DEBUG_KILL_ENTRY + "\n", StandardCharsets.UTF_8);
    }

    private static String deathText(String problem, String snapshot) {
        return problem + "\n" + snapshot;
    }

    private void assertNoDebugKillHit(Path dir, String text) {
        List<RetrievalDecision.Hit> hits = ExperienceLibraryLookup.lookupByText(dir, CID, text);
        assertTrue(hits.stream().noneMatch(h -> h.entryRef().contains("debug_kill")),
                "不再被 debug_kill 误命中：" + hits);
    }

    @Test
    void death44LavaNoLongerHitsDebugKill(@TempDir Path dir) throws Exception {
        writeStore(dir);
        assertNoDebugKillHit(dir, deathText(
                "[T2/death]  / task=primary-a43d753e-0-r4-0 / deathCauseId=lava / deathKind=lava / reason=lava",
                "context.bedZ=841, context.lastObservedInventory.minecraft:iron_helmet=1, context.blockLight=15, "
                        + "context.dayTime=6534, deathCauseMsg=RDD被岩浆烧死, deathSeq=44"));
    }

    @Test
    void death45FallNoLongerHitsDebugKill(@TempDir Path dir) throws Exception {
        writeStore(dir);
        assertNoDebugKillHit(dir, deathText(
                "[T2/death]  / task=primary-a43d753e-3-r10-2 / deathCauseId=fall / deathKind=fall / reason=fall",
                "context.dimension=minecraft:the_nether, context.bedDistance=-1.0, trace_len=21, deathSeq=45"));
    }

    @Test
    void death46ZombieNoLongerHitsDebugKill(@TempDir Path dir) throws Exception {
        writeStore(dir);
        assertNoDebugKillHit(dir, deathText(
                "[T2/death]  / task=primary-a43d753e-3-r12-2 / deathCauseId=mob / deathAttacker=僵尸 / deathKind=mob",
                "context.isNight=true, context.sleeping=false, context.bedX=-1, context.bedY=-1, context.bedZ=-1, deathSeq=46"));
    }

    @Test
    void genericDeathTextNoLongerHitsDebugKill(@TempDir Path dir) throws Exception {
        writeStore(dir);
        assertNoDebugKillHit(dir, deathText(
                "[T2/death]  / task=primary-a43d753e-3-r8-2 / deathCauseId=arrow / deathAttacker=骷髅",
                "context.isNight=true, context.sleeping=false, deathCauseMsg=RDD被骷髅射杀"));
    }
}
