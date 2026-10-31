package com.dwinovo.numen.plugins.learner.blocks;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 读 `monitor/context.jsonl` 的<strong>尾部</strong>（159 D 的取数层）。
 *
 * <h2>为什么只读尾部</h2>
 * 这份文件是逐条追加的观测日志，长跑里会到几十万行。学习样本只关心「最近这次事故前后」，
 * 把整份读进内存既慢又会把内存打满。所以按字节取尾（2MB，与 {@code PriorRoundReader}
 * 同一量级），再按行切、只留最后 N 行。
 *
 * <h2>为什么必须真的 skip</h2>
 * {@code PriorRoundReader} 的注释记着一次真实事故：开了流却从 0 读，
 * 结果「两条只读通道从上线起就一直在读文件头」。跳转没生效是**静默**的 ——
 * 读到的还是合法 JSON，只是全是旧的。所以这里显式循环 skip，并有用例钉住。
 *
 * <h2>读不到 ≠ 没有</h2>
 * 文件不存在（第一次跑）与读失败必须分开：前者是常态，后者是故障。
 * 两者都返回空列表，但 {@code ok} 与 {@code error} 不同 —— 面板据此显示，不许都写成「0 条」。
 *
 * <p>纯 JVM（只有文件 IO，无 MC 依赖），可用临时目录离线测试。
 */
public final class ContextFileReader {

    /** 读尾部多少字节。够覆盖最近若干轮请求，又不把整份日志读进内存。 */
    public static final int TAIL_BYTES = 2 * 1024 * 1024;

    /** 最多保留多少行（尾部字节切出来的行可能很多）。 */
    public static final int MAX_LINES = 4_000;

    /** 相对 configDir 的固定位置。 */
    public static final String RELATIVE_PATH = "monitor/context.jsonl";

    private ContextFileReader() {
    }

    /**
     * 读数。
     *
     * @param ok    {@code false} 表示<b>没读到</b>（文件不存在或读失败），不是"没有内容"
     * @param error 为什么没读到；{@code ok=true} 时为空
     */
    public record Read(List<String> lines, boolean ok, String error) {
        public Read {
            lines = lines == null ? List.of() : List.copyOf(lines);
            error = error == null ? "" : error;
        }
    }

    public static Read read(Path configDir) {
        return read(configDir, TAIL_BYTES, MAX_LINES);
    }

    public static Read read(Path configDir, int tailBytes, int maxLines) {
        return readAt(configDir, RELATIVE_PATH, tailBytes, maxLines);
    }

    /**
     * 读<b>任意</b> JSONL 的尾部（`monitor/expmem.jsonl` 等处复用同一套逻辑）。
     *
     * <p>为什么不复制一份：跳转没生效是静默故障（从文件头读到的也是合法 JSON），
     * 复制一份等于把同一颗雷埋两遍。
     */
    public static Read readAt(Path configDir, String relativePath, int tailBytes, int maxLines) {
        if (configDir == null) {
            return new Read(List.of(), false, "configDir 为空");
        }
        if (relativePath == null || relativePath.isBlank()) {
            return new Read(List.of(), false, "relativePath 为空");
        }
        Path file = configDir.resolve(relativePath);
        if (!Files.isRegularFile(file)) {
            // 第一次跑就是这样：如实说"还没有这份日志"，不当故障也不当空数据。
            return new Read(List.of(), false, "no " + relativePath + " yet (" + file + ")");
        }
        try {
            long size = Files.size(file);
            int want = (int) Math.min(size, Math.max(1, tailBytes));
            byte[] buf;
            try (var in = Files.newInputStream(file)) {
                long skip = size - want;
                long skipped = 0;
                while (skipped < skip) {
                    // skip 可能返回 0：不处理会变成死循环或静默读头。
                    long n = in.skip(skip - skipped);
                    if (n <= 0) {
                        break;
                    }
                    skipped += n;
                }
                buf = in.readNBytes(want);
            }
            String text = new String(buf, StandardCharsets.UTF_8);
            List<String> lines = new ArrayList<>();
            for (String l : text.split("\r?\n")) {
                if (!l.isBlank()) {
                    lines.add(l);
                }
            }
            if (lines.size() > maxLines) {
                lines = new ArrayList<>(lines.subList(lines.size() - maxLines, lines.size()));
            }
            return new Read(lines, true, "");
        } catch (IOException | RuntimeException e) {
            return new Read(List.of(), false,
                    "read failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
