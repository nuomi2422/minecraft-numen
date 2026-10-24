package com.dwinovo.numen.plugins.rdd;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * 主人亲授的**生存路线**（最高级经验）——每一轮规划都必须注入，且**所有同伴通用**。
 *
 * <p><b>为什么放在文件里而不是放进某个同伴的经验库</b>（2026-10-23 主人要求）：
 * 经验库是<b>按同伴</b>存的（{@code experience-<uuid>.jsonl}），新同伴拿不到旧同伴的东西；
 * 而这份是"开局怎么活到有铁有房有交易站"的**通识路线**，必须对每一个 AI 生效。
 * 所以真源是 {@code config/numen/survival-doctrine.md}：一份文件，所有同伴共用，改完立即生效。
 *
 * <p><b>为什么每次都要注入</b>：主人明确要求"每一次规划都直接注入"。
 * 规划器最容易犯的错就是忘记长期目标（先挖木头 → 木镐 → 石头 → 村庄安家），
 * 这份就是那个不会被压缩掉的目标锚点。
 *
 * <h2>为什么 GAME_DIR 是注入的接缝（2026-10-24 修复轮，RL-24）</h2>
 *
 * <p>第一版直接 {@code import FMLPaths} 并在 {@code file()} 里调 {@code FMLPaths.GAMEDIR}——
 * 单测 JVM 没有 NeoForge 运行时，类加载在<b>第一次碰到这个类</b>时抛 {@code NoClassDefFoundError}，
 * 而 {@code Error} 不在 {@code RuntimeException|IOException} 的 catch 范围里，直接穿透
 * 「规划不能因为一份指南挂掉」的承诺（audit-sweep-20261024 实测 14 个测试全炸）。
 *
 * <p>修法不是「把 catch 改宽」一条就完事——那样测试环境还是拿不到可测的 {@code file()}。
 * 所以把「游戏目录从哪来」做成 {@code Supplier<Path>} 接缝：生产默认<b>方法内懒加载</b>
 * FMLPaths（类加载被隔离在这一行 lambda 里），单测注入 {@code Files.createTempDirectory} 的假实现。
 * {@code block()} 整体 {@code try + catch Throwable}：任何失败（含类加载 Error）都降级为空串。
 */
final class RddSurvivalDoctrine {

    /** 文件里的内容上限：规划提示本身还有别的块，这里只切一个安全上限。 */
    private static final int MAX_CHARS = 6000;

    /** 游戏目录接缝：测试注入临时目录；生产懒加载 FMLPaths（类加载隔离在 lambda 内）。 */
    private static volatile Supplier<Path> gameDirProvider = RddSurvivalDoctrine::neoForgeGameDir;

    private static volatile String cached;
    private static volatile long cachedStamp = Long.MIN_VALUE;

    private RddSurvivalDoctrine() {
    }

    /** 生产实现：NeoForge 运行时的游戏目录。单测 JVM 没有这个类——所以绝不能在字段/静态初始化里碰它。 */
    private static Path neoForgeGameDir() {
        return net.neoforged.fml.loading.FMLPaths.GAMEDIR.get();
    }

    /** 测试接缝：注入假游戏目录。传 null 恢复生产实现。 */
    static void setGameDirProvider(Supplier<Path> provider) {
        gameDirProvider = provider == null ? RddSurvivalDoctrine::neoForgeGameDir : provider;
        invalidate();
    }

    static Path file() {
        return gameDirProvider.get().resolve("config").resolve("numen").resolve("survival-doctrine.md");
    }

    /**
     * 渲染成可直接贴进规划提示的块；文件不存在或任何读取失败（含类加载 Error）→ 返回空串。
     *
     * <p>缓存按文件 mtime 失效：主人改完文件不用重启。
     *
     * <p><b>catch Throwable 的边界</b>：这里兜的是「指南这份文件读不到」，不是「世界塌了」。
     * {@code InterruptedException} / {@code OutOfMemoryError} 这类不该吞的，让 JVM 自己的
     * uncaught handler 处理——但类加载失败（{@code NoClassDefFoundError}）是「环境没有这扇门」，
     * 属于必须降级的形态。为简单起见 Throwable 全兜 + 空串，理由：这个方法的唯一职责就是
     * 「返回可以贴的块或空串」，它没有资格让规划进程死掉。
     */
    static String block() {
        try {
            Path p = file();
            if (!Files.isRegularFile(p)) {
                return "";
            }
            long stamp = Files.getLastModifiedTime(p).toMillis();
            String body = cached;
            if (body == null || stamp != cachedStamp) {
                String raw = Files.readString(p, StandardCharsets.UTF_8).strip();
                if (raw.isEmpty()) {
                    cached = "";
                    cachedStamp = stamp;
                    return "";
                }
                if (raw.length() > MAX_CHARS) {
                    raw = raw.substring(0, MAX_CHARS) + "\n…（指南过长已截断）";
                }
                body = "## 最高级经验 · 主人亲授的生存路线（**每次规划都必须遵守**）\n\n" + raw;
                cached = body;
                cachedStamp = stamp;
            }
            return body;
        } catch (Throwable t) {
            // 读不到就当没有：规划不能因为一份指南挂掉——这条承诺对 Error 同样成立（RL-24）。
            return "";
        }
    }

    /** 测试/诊断用：强制丢弃缓存。 */
    static void invalidate() {
        cached = null;
        cachedStamp = Long.MIN_VALUE;
    }
}
