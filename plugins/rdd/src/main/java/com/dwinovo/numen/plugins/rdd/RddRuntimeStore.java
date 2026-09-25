package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.Subtask;
import com.dwinovo.numen.rdd.core.RddRuntime;
import com.dwinovo.numen.rdd.core.TaskChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/**
 * 任务链持久化（副簇，文档四.4 修正）：把活动任务链落盘/重启恢复。
 *
 * <p>原本在 {@link RddPlugin} 里，与"事实门面"无关（完成事实是 rdd-facts，这里是 rdd-tasks）。
 * 搬出后 {@link RddPlugin#saveRuntimes}/{@link RddPlugin#restoreRuntimes} 变薄委托，行为逐字不变。
 *
 * <ul>
 *   <li>{@link #save}：全部活跃 Runtime 的链原子写盘（.tmp→rename）；失败只记日志。</li>
 *   <li>{@link #restore}：磁盘有链但内存没有 → 加载为 RddRuntime（幂等，跳过已加载）。</li>
 * </ul>
 *
 * <p>依赖：需要传入 RUNTIMES 的 <b>live 引用</b>（不复制），assets 走 {@link RddAssetFacade}。
 */
final class RddRuntimeStore {
    private static final Logger LOG = LoggerFactory.getLogger(RddRuntimeStore.class);

    private RddRuntimeStore() {
    }

    /** 原子写盘全部活跃链。runtimes 传 live Map（不复制），tasksDir 为目标目录。 */
    static void save(Map<UUID, RddRuntime> runtimes, Path tasksDir) {
        if (tasksDir == null || runtimes.isEmpty()) return;
        try {
            Files.createDirectories(tasksDir);
            for (Map.Entry<UUID, RddRuntime> e : runtimes.entrySet()) {
                try {
                    Path tmp = tasksDir.resolve(e.getKey() + ".json.tmp");
                    Files.writeString(tmp, e.getValue().chain().toJson(), StandardCharsets.UTF_8);
                    Files.move(tmp, tasksDir.resolve(e.getKey() + ".json"),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ex) {
                    LOG.warn("[rdd] 保存任务失败 {}: {}", e.getKey(), ex.toString());
                }
            }
        } catch (IOException ex) {
            LOG.warn("[rdd] 创建任务目录失败: {}", ex.toString());
        }
    }

    /** 游戏重启恢复：磁盘有任务但内存没有 → 加载为 RddRuntime（幂等）。runtimes 传 live Map（可原地 put）。 */
    static void restore(Map<UUID, RddRuntime> runtimes, Path tasksDir) {
        if (tasksDir == null || !Files.isDirectory(tasksDir)) return;
        try (var stream = Files.list(tasksDir)) {
            stream.filter(f -> f.getFileName().toString().endsWith(".json")).forEach(f -> {
                try {
                    UUID uuid = UUID.fromString(f.getFileName().toString().replace(".json", ""));
                    if (runtimes.containsKey(uuid)) return;
                    String json = Files.readString(f, StandardCharsets.UTF_8);
                    TaskChain chain = TaskChain.fromJson(json);
                    runtimes.put(uuid, new RddRuntime(chain, RddAssetFacade.assets(uuid)));
                    // 懒链可能停靠在未展开一级：currentSubtask()=null，报阶段而非 NPE
                    Subtask restored = chain.currentSubtask();
                    String curLabel = restored != null
                            ? restored.id() : ("unexpanded:" + chain.currentPrimary().id());
                    LOG.info("[rdd] 恢复任务链 {}（当前二级 {}）", uuid, curLabel);
                } catch (Exception ex) {
                    LOG.warn("[rdd] 恢复任务失败 {}: {}", f.getFileName(), ex.toString());
                }
            });
        } catch (IOException ex) {
            LOG.warn("[rdd] 扫描任务目录失败: {}", ex.toString());
        }
    }
}