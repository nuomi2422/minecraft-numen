package com.dwinovo.numen.monitor;

import com.dwinovo.numen.Constants;
import com.google.gson.Gson;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Numen 的低侵入观测出口。单写线程、分区文件、有界队列；监测台不可用时不拖住游戏。
 *
 * <p>这是观察日志，不是任务状态库。消费者可以直接 tail {@code config/numen/monitor/}
 * 下按类别划分的 JSONL 文件，后续再由外部 Monitoring Adapter 转发到 AUI。</p>
 */
public final class MonitoringJournal implements AutoCloseable {
    public static final int DEFAULT_CAPACITY = 2048;
    private static final long MAX_FILE_BYTES = 16L * 1024 * 1024;
    private static final Gson GSON = new Gson();
    private static final MonitoringJournal INSTANCE = new MonitoringJournal();
    /** 进程级运行标识：tools/events 同由本 writer 落盘，消费者可据此跨文件关联（旧行缺字段=unknown）。 */
    private static final String SESSION_ID = java.util.UUID.randomUUID().toString();

    private record Entry(String category, String type, Map<String, Object> data, long time) {}

    private final BlockingQueue<Entry> queue = new ArrayBlockingQueue<>(DEFAULT_CAPACITY);
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Map<String, BufferedWriter> writers = new LinkedHashMap<>();
    private final Thread writerThread;
    private final java.util.function.Supplier<Path> configResolver;
    private volatile Path root;
    private volatile long dropped;

    private MonitoringJournal() {
        this(com.dwinovo.numen.NumenPaths::config, null);
    }

    /** Package-local async fixture seam: only platform path resolution is replaceable. */
    MonitoringJournal(java.util.function.Supplier<Path> configResolver,
                      Thread.UncaughtExceptionHandler uncaughtHandler) {
        this.configResolver = Objects.requireNonNull(configResolver, "configResolver");
        writerThread = new Thread(this::drain, "numen-monitor-writer");
        writerThread.setDaemon(true);
        if (uncaughtHandler != null) writerThread.setUncaughtExceptionHandler(uncaughtHandler);
        writerThread.start();
    }

    /** Bounded fixture cleanup; does not alter production close/drain semantics. */
    boolean awaitStopped(long timeoutMillis) throws InterruptedException {
        writerThread.join(timeoutMillis);
        return !writerThread.isAlive();
    }

    public static MonitoringJournal get() {
        return INSTANCE;
    }

    /** 设置运行目录；默认使用 Numen 的统一 config/numen 目录。 */
    public synchronized void configure(Path configDir) {
        root = Objects.requireNonNull(configDir, "configDir").resolve("monitor");
    }

    /** 非阻塞投递；队列满时只丢弃观测，不阻塞 Numen 主流程。 */
    public void publish(String category, String type, Map<String, ?> data) {
        if (!running.get() || category == null || type == null) return;
        String safeCategory = safeToken(category);
        String safeType = safeToken(type);
        Map<String, Object> copy = new LinkedHashMap<>();
        if (data != null) copy.putAll(data);
        Entry entry = new Entry(safeCategory, safeType, copy, System.currentTimeMillis());
        if (!queue.offer(entry)) {
            dropped++;
        }
    }

    public long droppedCount() {
        return dropped;
    }

    public int queueSize() {
        return queue.size();
    }

    private void drain() {
        while (running.get() || !queue.isEmpty()) {
            try {
                Entry entry = queue.poll(250, TimeUnit.MILLISECONDS);
                if (entry != null) write(entry);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            } catch (RuntimeException ex) {
                Constants.LOG.warn("[numen-monitor] 写入观测失败: {}", ex.toString());
            }
        }
        closeWriters();
    }

    private synchronized void write(Entry entry) {
        Path base = root;
        if (base == null) {
            try {
                base = configResolver.get().resolve("monitor");
                root = base;
            } catch (RuntimeException | LinkageError ex) {
                // 2026-11-07: unavailable platform initialization can raise a linkage
                // error before configure(). Drop this observation, not the worker;
                // later explicit configuration must still recover. Fatal VM errors
                // are deliberately not caught. Evidence: full API test XML worker death.
                return;
            }
        }
        try {
            Files.createDirectories(base);
            BufferedWriter writer = writers.get(entry.category());
            Path file = base.resolve(entry.category() + ".jsonl");
            if (writer == null) {
                writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                writers.put(entry.category(), writer);
            }
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("schema_version", 1);
            envelope.put("event_id", entry.category() + "-" + entry.time() + "-" + queue.size());
            envelope.put("session", SESSION_ID);
            envelope.put("timestamp", Instant.ofEpochMilli(entry.time()).toString());
            envelope.put("source", "numen");
            envelope.put("category", entry.category());
            envelope.put("type", entry.type());
            envelope.put("data", entry.data());
            writer.write(GSON.toJson(envelope));
            writer.newLine();
            writer.flush();
            if (Files.size(file) >= MAX_FILE_BYTES) rotate(writer, file, entry.category());
        } catch (IOException ex) {
            Constants.LOG.warn("[numen-monitor] 观测文件不可写 {}: {}", entry.category(), ex.getMessage());
            closeWriter(entry.category());
        }
    }

    /**
     * 每个类别保留的轮转归档上限（2026-11-05）。
     *
     * <p>为什么必须加：rotate 原来只负责「改名归档」，**从不清理**。实机目录里 context-* 已经
     * 362 份 × 16 MB ≈ **5.8 GB**，占整个 monitor 目录的 94%，而 rdd.jsonl 只占 0.2%。
     * 这不是「证据保留」，是无上限增长。
     */
    static final int MAX_ROTATED_FILES = 20;

    private void rotate(BufferedWriter writer, Path file, String category) throws IOException {
        writer.flush();
        writer.close();
        Path rotated = file.resolveSibling(category + "-" + System.currentTimeMillis() + ".jsonl");
        Files.move(file, rotated, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        writers.remove(category);
        pruneRotated(file.getParent(), category, MAX_ROTATED_FILES);
    }

    /**
     * 删掉某类别超出保留数的旧归档；**只认 category-数字.jsonl**，绝不碰正在写的
     * category.jsonl，也不碰别的类别。
     *
     * @return 删除的文件数
     */
    static int pruneRotated(Path dir, String category, int keep) throws IOException {
        if (dir == null || category == null || keep <= 0) return 0;
        java.util.List<Path> rotated = new java.util.ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile).forEach(path -> {
                String name = path.getFileName().toString();
                if (name.startsWith(category + "-") && name.endsWith(".jsonl")) {
                    String mid = name.substring(category.length() + 1, name.length() - ".jsonl".length());
                    if (!mid.isEmpty() && mid.chars().allMatch(Character::isDigit)) rotated.add(path);
                }
            });
        }
        if (rotated.size() <= keep) return 0;
        rotated.sort(java.util.Comparator.comparingLong(path -> path.toFile().lastModified()));
        int removed = 0;
        for (int i = 0; i < rotated.size() - keep; i++) {
            try {
                Files.deleteIfExists(rotated.get(i));
                removed++;
            } catch (IOException ignored) {
                // 删不掉就算了，别让观测层影响主流程
            }
        }
        return removed;
    }

    private synchronized void closeWriter(String category) {
        BufferedWriter writer = writers.remove(category);
        if (writer != null) try { writer.close(); } catch (IOException ignored) {}
    }

    private synchronized void closeWriters() {
        for (BufferedWriter writer : writers.values()) {
            try { writer.close(); } catch (IOException ignored) {}
        }
        writers.clear();
    }

    private static String safeToken(String value) {
        String token = value.replaceAll("[^A-Za-z0-9_.-]", "_");
        return token.isEmpty() ? "unknown" : token.substring(0, Math.min(token.length(), 48));
    }

    @Override
    public void close() {
        if (running.compareAndSet(true, false)) writerThread.interrupt();
    }
}
