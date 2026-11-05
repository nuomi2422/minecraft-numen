package com.dwinovo.numen.plugins.rdd;

import com.google.gson.Gson;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RDD 任务链的观测出口：往 {@code config/numen/monitor/rdd.jsonl} 追加 JSON 行。
 *
 * <p>不依赖引擎内部类（MonitoringJournal 不在瘦 API jar），插件自己写文件，
 * 行格式与 MonitoringJournal 的 envelope 对齐，监测台可原样解析。
 *
 * <p>带轮转与归档上限：rdd.jsonl 曾因「每条 taskchain_snapshot 内嵌全量资产表 + 懒边界每秒重发」
 * 涨到 288 MB。事件侧已各自收敛；这里再兜一层——单文件超 {@link #MAX_FILE_BYTES} 即轮转，
 * 只保留最近 {@link #MAX_ROTATED_FILES} 个归档，保证观测目录总量有界，长时间实机不会撑爆磁盘。
 */
public final class RddMonitor {

    private static final Gson GSON = new Gson();
    /** 单文件上限，与 MonitoringJournal 的 16 MB 约定一致。 */
    private static final long MAX_FILE_BYTES = 16L * 1024 * 1024;
    /** 保留的轮转归档数量上限；更旧的删除（纯观测产物，不影响任务链状态）。 */
    private static final int MAX_ROTATED_FILES = 5;
    private static final String FILE_NAME = "rdd.jsonl";
    private static final String ROTATED_PREFIX = "rdd-";

    private RddMonitor() {}

    /**
     * 按内容去重发布：**内容没变就不写**。
     *
     * <p>为什么（审计实测）：taskchain_snapshot 348 条里只有 8 种不同的 data（340 条逐字节重复，≈7.6 MB 纯冗余）；
     * asset_snapshot 每条的 value 完全相同，只有 observationId/observedAt 在跳。一轮 12 分钟测试就占了
     * 16 MB 轮转额度的 76% —— 证据还没被读就被轮转走了。
     *
     * <p>语义：**没有新事件 = 内容没变**。这本来就和 condition_evaluated 的纪律一致（状态没变只发一条），
     * 不加字段、不改 schema、消费方不用动。
     *
     * <p>签名会**剥掉易变字段**（observationId / observedAt），否则每次都不同，去重永远不命中。
     */
    public static synchronized boolean publishIfChanged(String type, Map<String, ?> data) {
        String sig = signature(data);
        long now = System.currentTimeMillis();
        boolean emit = GATE.shouldEmit(type, sig, now);
        if (DEBUG_GATE && DEBUG_COUNT.getAndIncrement() < 40) {
            LOG.info("[rdd-monitor] gate type={} sig={} emit={} len={}", type, sig.hashCode(), emit, sig.length());
        }
        if (!emit) {
            return false;
        }
        // 2026-11-05（审计）：**先发成功再记账**。反过来的话，写盘失败也会留下指纹，
        // 下次相同内容被判「没变」→ 事件永久丢失且无痕（publish 内部是 catch Throwable ignored）。
        if (!publish(type, data)) {
            return false;
        }
        GATE.commit(type, sig, now);
        return true;
    }

    /** 临时诊断（2026-11-05）：现场去重未生效时用来对比签名。确认后可删。 */
    private static final boolean DEBUG_GATE = Boolean.getBoolean("rdd.monitor.debug");
    private static final java.util.concurrent.atomic.AtomicInteger DEBUG_COUNT =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("rdd-monitor");

    /** 内容长时间不变时，每 10 分钟强制发一条全量心跳——否则日志里没有该同伴的痕迹，会被误判成卡死。 */
    static final long HEARTBEAT_MS = 10L * 60 * 1000;

    /** 判定逻辑抽在 RddMonitorGate（纯类），这里只保留实例。 */
    private static final RddMonitorGate GATE = new RddMonitorGate(HEARTBEAT_MS);
    /** 内容指纹：递归剥掉 observationId / observedAt（它们每拍都在变，不代表内容变化）。 */
    static String signature(Object value) {
        return GSON.toJson(stripVolatile(value));
    }

    /**
     * 递归剥掉易变字段，并**按 key 排序**。
     *
     * <p>为什么排序：签名靠 GSON 序列化字符串比较，而 Map 的迭代顺序受实现/插入顺序影响
     * （HashMap vs LinkedHashMap、Map.of 的随机 salt）。内容一样但 key 顺序不同 → 签名不同 →
     * 去重永不命中。实机第一次部署就是这么翻车的：asset_snapshot 仍然每 30 秒一条。
     */
    private static Object stripVolatile(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new java.util.TreeMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String k = String.valueOf(e.getKey());
                if ("observationId".equals(k) || "observedAt".equals(k)) continue;
                out.put(k, stripVolatile(e.getValue()));
            }
            return out;
        }
        if (value instanceof java.util.Collection<?> collection) {
            // 2026-11-05 现场：签名长度不变但 hash 每拍都变 → 内容相同、**集合顺序**不同
            //（HashSet/多来源拼装）。签名只用来判"内容是否变化"，排序不影响这个判断，
            // 所以统一按规范化后的字符串排序，彻底消除顺序影响。
            List<String> parts = new java.util.ArrayList<>(collection.size());
            for (Object o : collection) parts.add(GSON.toJson(stripVolatile(o)));
            parts.sort(java.util.Comparator.naturalOrder());
            List<Object> out = new java.util.ArrayList<>(parts.size());
            for (String part : parts) out.add(part);
            return out;
        }
        return value;
    }

    /** @return true=确实写盘成功（调用方据此决定要不要记账） */
    public static synchronized boolean publish(String type, Map<String, ?> data) {
        try {
            Path dir = FMLPaths.GAMEDIR.get().resolve("config").resolve("numen").resolve("monitor");
            Files.createDirectories(dir);
            Path file = dir.resolve(FILE_NAME);

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("schema_version", 1);
            envelope.put("event_id", "rdd-" + System.nanoTime());
            envelope.put("timestamp", Instant.now().toString());
            envelope.put("source", "numen");
            envelope.put("category", "rdd");
            envelope.put("type", type);
            envelope.put("data", data == null ? Map.of() : data);

            if (Files.exists(file) && Files.size(file) >= MAX_FILE_BYTES) {
                rotate(dir, file);
            }

            Files.writeString(file, GSON.toJson(envelope) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return true;
        } catch (Throwable ignored) {
            // 观测失败不影响 RDD 主流程。
            // ★ D20-C1：从 Exception 升到 Throwable —— FMLPaths 在纯 JVM 单测环境
            //   抛的是 NoClassDefFoundError（Error 不是 Exception），原 catch 接不住，
            //   任何碰 RddMonitor.publish 的单测直接炸在类加载上。
            //   观测是旁路（fail-soft 是本类的设计自述），Error 也必须吞 ——
            //   让任务链核心逻辑被观测层的类缺失拖垮，正是「fail-soft」的反面。
        }
        return false;
    }

    /** 把写满的 rdd.jsonl 挪成带时间戳的归档，再清出目录里的超量归档。 */
    private static void rotate(Path dir, Path file) throws IOException {
        Files.move(file, dir.resolve(ROTATED_PREFIX + System.currentTimeMillis() + ".jsonl"),
                StandardCopyOption.REPLACE_EXISTING);
        prune(dir);
    }

    /** 只保留最近 MAX_ROTATED_FILES 个归档（按最后修改时间倒序）。 */
    private static void prune(Path dir) throws IOException {
        List<Path> stale;
        try (var stream = Files.list(dir)) {
            stale = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith(ROTATED_PREFIX) && name.endsWith(".jsonl")
                                && !name.equals(FILE_NAME);
                    })
                    .sorted(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed())
                    .skip(MAX_ROTATED_FILES)
                    .toList();
        }
        for (Path p : stale) {
            Files.deleteIfExists(p);
        }
    }
}
