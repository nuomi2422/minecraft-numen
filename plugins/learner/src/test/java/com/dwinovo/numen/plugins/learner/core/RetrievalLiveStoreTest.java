package com.dwinovo.numen.plugins.learner.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 用**真实经验库**回放（2026-11-02）：不是 fixture，读本机 live 库，跑真实检索代码。
 *
 * <p>若换机器/没有该文件，用例自动 skip（assumeTrue）——它是现场取证，不是 CI 依赖。
 */
class RetrievalLiveStoreTest {

    private static final String CID = "a43d753e-790d-472b-8d5b-dc18ac0afbc0";
    private static final Path LIVE = Path.of(
            "D:/rdd/mc/The Best of Twilight Forest/config/numen/experience-" + CID + ".jsonl");

    private static void copyLive(Path dir) throws Exception {
        assumeTrue(Files.isRegularFile(LIVE), "本机没有 live 经验库，跳过");
        Files.copy(LIVE, dir.resolve("experience-" + CID + ".jsonl"));
    }

    private void assertNoDebugKill(Path dir, String text) {
        var hits = ExperienceLibraryLookup.lookupByText(dir, CID, text);
        assertTrue(hits.stream().noneMatch(h -> h.entryRef().contains("debug_kill")),
                "真实库里 debug_kill 不得再命中死亡文本：" + hits);
    }

    @Test
    void liveStoreNoLongerMatchesDeathTextsToDebugKill(@TempDir Path dir) throws Exception {
        copyLive(dir);
        assertNoDebugKill(dir, "[T2/death] deathCauseId=lava deathKind=lava context.bedZ=841");
        assertNoDebugKill(dir, "[T2/death] deathCauseId=fall context.dimension=minecraft:the_nether");
        assertNoDebugKill(dir, "[T2/death] deathCauseId=mob deathAttacker=僵尸 context.isNight=true");
        assertNoDebugKill(dir, "[T2/death] deathCauseId=arrow deathAttacker=骷髅");
    }
}
