package com.dwinovo.numen.plugins.rdd;

import net.neoforged.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
 */
final class RddSurvivalDoctrine {

    /** 文件里的内容上限：规划提示本身还有别的块，这里只切一个安全上限。 */
    private static final int MAX_CHARS = 6000;

    private static volatile String cached;
    private static volatile long cachedStamp = Long.MIN_VALUE;

    private RddSurvivalDoctrine() {
    }

    static Path file() {
        return FMLPaths.GAMEDIR.get().resolve("config").resolve("numen").resolve("survival-doctrine.md");
    }

    /**
     * 渲染成可直接贴进规划提示的块；文件不存在 → 返回空串（**不注入空标题**）。
     *
     * <p>缓存按文件 mtime 失效：主人改完文件不用重启。
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
        } catch (RuntimeException | java.io.IOException ex) {
            // 读不到就当没有：规划不能因为一份指南挂掉
            return "";
        }
    }

    /** 测试/诊断用：强制丢弃缓存。 */
    static void invalidate() {
        cached = null;
        cachedStamp = Long.MIN_VALUE;
    }
}
