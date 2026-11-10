package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.Goal;
import com.dwinovo.numen.rdd.fact.CompletedFactStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

/**
 * 「verified 结算 → 完成库」的唯一写入点（2026-11-10 抽出）。
 *
 * <p>为什么单独一个类：写入动作原来埋在 {@code RddPlugin.recordStageFact} 里，
 * 而 {@code RddPlugin} 一加载就要 Minecraft 服务端类，离线测试根本触达不到这条生产链，
 * 于是"完成事实到底有没有真的写"长期只有读代码的证据。抽出来以后生产与测试走同一条路径：
 * 不手填 verified、不另写一份写入逻辑。
 *
 * <p>失败约定：入参缺失直接返回 false（不写半个文件）；磁盘异常由调用方决定如何告警，
 * 本类只如实返回 false，绝不吞掉后假装成功。
 */
final class CompletionFactWriter {
    private CompletionFactWriter() {}

    static boolean persist(Path factsDir, UUID companionId, CompletedFactStore store,
                           Goal goal, String primaryDescription, long atMillis) {
        if (factsDir == null || companionId == null || store == null
                || goal == null || primaryDescription == null) {
            return false;
        }
        synchronized (store) {
            CompletedFactStore candidate = store.copyForCompletionCommit();
            candidate.recordStage(goal, primaryDescription, atMillis, "primary confirmed");
            try {
                RddFactStore.save(factsDir, companionId, candidate);
                store.publishCompletionCommit(candidate);
                return true;
            } catch (IOException ex) {
                return false;
            }
        }
    }
}
