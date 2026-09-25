package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.fact.CompletedFactStore;
import com.dwinovo.numen.rdd.api.Goal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 事实门面（职责簇 E）：一个同伴的"完成事实仓库"统一入口。
 *
 * <p>把 {@link RddPlugin} 里的完成事实职责（P0 跨重绑/跨重启保留的"哪些战略阶段已被可靠完成"）
 * 整体搬到这里：字段 + 方法逐字迁移，行为不变（阶段恢复依赖本类口径）。
 *
 * <p>本类只承担<b>数据持有与落盘</b>，不碰链状态、不投 MCP。变化点：
 * <ul>
 *   <li>{@link #facts}：完成事实仓库（载盘，uuid→CompletedFactStore），由 RddFactStore 落地。</li>
 *   <li>{@link #recordStageFact}/{@link #recordSubtaskFact}：可靠完成阶段/留档细节并落盘。</li>
 * </ul>
 *
 * <p>入口：{@link RddPlugin#setup} 传入 configDir，随后各调用点直接静态调用本类方法。
 */
public final class RddFactFacade {
    private static final Logger LOG = LoggerFactory.getLogger(RddFactFacade.class);

    /** P0 完成事实目录 config/numen/rdd-facts（每个同伴一个 <uuid>.json）；跨重绑/跨重启保留。 */
    private static volatile Path factsDir;
    /** 内存完成事实仓库（uuid→store）；磁盘为真身，清世界状态只清内存。 */
    private static final Map<UUID, CompletedFactStore> FACTS = new ConcurrentHashMap<>();

    private RddFactFacade() {
    }

    /** 初始化目录（由 RddPlugin.setup 调用）。 */
    static void init(Path configDir) {
        factsDir = configDir.resolve("rdd-facts");
    }

    /** 清世界状态：只清内存缓存，磁盘真身保留；由 RddPlugin ServerStopped 时调用。 */
    static void clearWorldState() {
        FACTS.clear();
    }

    /** 清除单个同伴的内存缓存（REMOVE 事件时调用）。 */
    static void remove(UUID companionId) {
        if (companionId == null) return;
        FACTS.remove(companionId);
    }

    /** P0 完成事实仓库（磁盘为真身，内存缓存）。 */
    public static CompletedFactStore facts(UUID companionId) {
        if (companionId == null) return new CompletedFactStore();
        return FACTS.computeIfAbsent(companionId, id -> RddFactStore.load(factsDir, id));
    }

    /** 记录"某战略阶段已被可靠完成"并落盘（P0）。失败只记日志，不影响主流程。 */
    static void recordStageFact(UUID companionId, Goal goal, String primaryDescription) {
        if (companionId == null || goal == null || primaryDescription == null) return;
        try {
            CompletedFactStore store = facts(companionId);
            store.recordStage(goal, primaryDescription, System.currentTimeMillis(), "primary confirmed");
            RddFactStore.save(factsDir, companionId, store);
        } catch (IOException ex) {
            LOG.warn("[rdd] 保存完成事实失败 {}: {}", companionId, ex.toString());
        }
    }

    /** 留档一条二级完成细节（辅助，不用于恢复）；随阶段落盘一起持久化。 */
    static void recordSubtaskFact(UUID companionId, Goal goal, String primaryDescription, String subtaskDescription) {
        if (companionId == null || goal == null) return;
        facts(companionId).recordSubtask(goal, primaryDescription, subtaskDescription, System.currentTimeMillis());
    }
}