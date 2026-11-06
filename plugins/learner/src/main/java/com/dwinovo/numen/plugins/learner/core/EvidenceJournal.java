package com.dwinovo.numen.plugins.learner.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 证据 id 的**真伪核对**：id 必须能在监测日志里查到，否则不算证据。
 *
 * <p>为什么需要（2026-11-06 实测）：复盘契约只校验证据的**形状**（event:/block: 前缀、
 * 可解析），形状合法但根本不存在的事件 id 一样能过——那会让"有证据"变成假象，
 * 比"没写证据"更危险。库里已有一条同类教训（authored-but-unverifiable）。
 *
 * <p>边界：只读 {@code config/numen/monitor/*.jsonl}（含轮转文件），按修改时间新→旧扫，
 * 单文件只读末尾 {@link #TAIL_BYTES}，总预算 {@link #BUDGET_BYTES}。查不到就是查不到，
 * 不因为"可能很久以前"而放过——调用方据此丢弃并记账。
 *
 * <p>纯 JVM（只碰文件），可独立单测。
 */
public final class EvidenceJournal {

    /** 单个日志文件只看末尾这么多字节：事件 id 基本都在近期。 */
    static final long TAIL_BYTES = 8L * 1024 * 1024;
    /** 一轮核对最多读这么多字节，防止一次复盘把磁盘读爆。 */
    static final long BUDGET_BYTES = 64L * 1024 * 1024;

    private EvidenceJournal() {
    }

    /** 返回 {@code ids} 里**查不到**的那些（保持入参顺序）。 */
    public static List<String> unresolved(Path monitorDir, Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> pending = new LinkedHashSet<>();
        for (String id : ids) {
            if (id != null && !id.isBlank()) pending.add(id.trim());
        }
        if (pending.isEmpty()) {
            return List.of();
        }
        if (monitorDir == null || !Files.isDirectory(monitorDir)) {
            return List.copyOf(pending);   // 连日志都没有 ⇒ 全部无法核实
        }
        long budget = BUDGET_BYTES;
        for (Path file : recentFiles(monitorDir)) {
            if (pending.isEmpty() || budget <= 0) break;
            String text;
            try {
                text = tail(file, Math.min(TAIL_BYTES, budget));
            } catch (IOException | RuntimeException e) {
                continue;
            }
            budget -= Math.min(TAIL_BYTES, budget);
            pending.removeIf(id -> text.contains("\"event_id\":\"" + id + "\""));
        }
        return new ArrayList<>(pending);
    }

    private static List<Path> recentFiles(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            List<Path> files = new ArrayList<>();
            s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .forEach(files::add);
            files.sort(Comparator.comparingLong(EvidenceJournal::mtime).reversed());
            return files;
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    private static long mtime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException | RuntimeException e) {
            return 0L;
        }
    }

    private static String tail(Path file, long bytes) throws IOException {
        long size = Files.size(file);
        long from = Math.max(0L, size - bytes);
        byte[] buf;
        try (var ch = java.nio.channels.FileChannel.open(file, java.nio.file.StandardOpenOption.READ)) {
            ch.position(from);
            int len = (int) Math.min(size - from, bytes);
            buf = new byte[len];
            var bb = java.nio.ByteBuffer.wrap(buf);
            while (bb.hasRemaining() && ch.read(bb) > 0) {
                // 读满或读到 EOF
            }
        }
        return new String(buf, StandardCharsets.UTF_8);
    }
}
