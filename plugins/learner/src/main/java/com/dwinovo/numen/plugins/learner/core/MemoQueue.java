package com.dwinovo.numen.plugins.learner.core;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 备忘录队列：干活 AI 往里写，学习者从里取。
 *
 * <p><b>关键不变量</b>（对应规格 §3「复盘失败不丢队列」）：
 * {@link #drain} 采用「先取快照 → 外部处理 → 成功才删除」的协议。
 * 真正删除只发生在 {@link #commitDrain}；失败时 {@link #restore} 把条目原样写回。
 * 也就是说「学习者清空队列」在语义上是「取走并复盘」，不是「丢掉」。
 *
 * <p>落盘 {@code config/numen/learner-memos-&lt;uuid&gt;.json}，内容是一个 JSON 数组
 * （<b>不是逐行 JSONL</b> —— 早期命名带 .jsonl 是误导，2026-09-29 已改正，
 * 因为全量重写无法保证「一行一条」的原子性）。
 * 写入用 temp + ATOMIC_MOVE 全量重写（抄 ExperienceStore 的做法）。
 *
 * <p>纯 JVM，可独立单测。
 */
public final class MemoQueue {

    /** 队列上限：超出拒收并回报，不静默丢。 */
    public static final int MAX_QUEUE = 64;

    /** 单条正文字段上限（problem/tried/snapshot 各算一份），防一条爆量把全量重写拖垮。 */
    public static final int MAX_FIELD_CHARS = 4000;

    private static final Gson GSON = new Gson();
    private static final Type LIST_TYPE = new TypeToken<List<Memo>>() {}.getType();

    /**
     * 已入队过的 memo id 上限（持久化，防换代重扫重复入队）。
     *
     * <p>2026-11-02 现场：重启→存档代际变→自动入队游标归零→重扫旧 instrumentation，
     * 内存幂等集已丢，队列又只按「当前在队列里」去重 ⇒ 16:52:09 一次回灌 +11 death、
     * +5 loop；手动隔离不持久。
     *
     * <p><b>保证范围</b>：seen 文件建立**之后**入过队的 id 不会再来（含复核移除、手动隔离）。
     * 建立之前的历史（隔离清单、更早的 reviewed id）由一次性迁移脚本
     * {@code evidence/seed-seen.cjs} 并进 seen 文件；不迁移就只能覆盖新数据。
     * 上限 {@link #SEEN_CAP}：被淘汰的最旧 id 理论上可再回来，这是有界内存的已知代价。
     */
    private static final int SEEN_CAP = 4096;

    private final Path file;
    private final ReentrantLock lock = new ReentrantLock();
    /** 懒加载的「曾经入队过」集合；null = 还没读。 */
    private java.util.LinkedHashSet<String> seen;

    public MemoQueue(Path file) {
        this.file = file;
    }

    private Path seenFile() {
        return file.resolveSibling(file.getFileName() + ".seen.json");
    }

    private java.util.LinkedHashSet<String> seen() {
        if (seen == null) {
            java.util.LinkedHashSet<String> s = new java.util.LinkedHashSet<>();
            boolean loaded = false;
            try {
                Path sf = seenFile();
                if (Files.isRegularFile(sf)) {
                    String raw = Files.readString(sf, StandardCharsets.UTF_8);
                    if (raw.isBlank()) {
                        loaded = true;   // 空文件是合法状态（从没入队过）
                    } else {
                        java.util.List<String> ids = GSON.fromJson(raw,
                                new TypeToken<java.util.List<String>>() {}.getType());
                        if (ids != null) {
                            for (String id : ids) {
                                if (id != null && !id.isBlank()) s.add(id);
                            }
                        }
                        loaded = ids != null;
                    }
                }
            } catch (IOException | RuntimeException ignored) {
                // 读不到/解析坏：下面按「未加载」走播种，幂等退化为旧行为，绝不拒绝入队。
            }
            if (!loaded) {
                // 首次上线：把当前队列里的 id 播进 seen，避免「升级前就在队列里的条目」
                // 被复核移除后又以同一 id 回灌。坏队列不阻断入队（沿用 readAll 的失败语义）。
                try {
                    for (Memo m : readAll()) {
                        if (m != null && m.id() != null && !m.id().isBlank()) s.add(m.id());
                    }
                } catch (RuntimeException ignored) {
                    // 队列坏了：留给 append/commit 的既有失败路径处理，这里不猜。
                }
                if (!s.isEmpty()) {
                    try { Files.writeString(seenFile(), GSON.toJson(java.util.List.copyOf(s), java.util.List.class), StandardCharsets.UTF_8); } catch (IOException ignored) { }
                }
            }
            seen = s;
        }
        return seen;
    }

    /**
     * 这个 id 现在是否真的在队列里。
     *
     * <p>为什么需要：{@link #append} 对「曾经入队过」也返回 true（语义=不必再放），
     * 调用方若把 true 当「新增了一条」会把重复事件记成新样本/新 memo。用这个做二次确认。
     */
    public boolean contains(String id) {
        if (id == null || id.isBlank()) return false;
        lock.lock();
        try {
            for (Memo m : readAll()) {
                if (m != null && id.equals(m.id())) return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    private void remember(String id) {
        if (id == null || id.isBlank()) return;
        java.util.LinkedHashSet<String> s = seen();
        s.remove(id);
        s.add(id);
        while (s.size() > SEEN_CAP) {
            java.util.Iterator<String> it = s.iterator();
            it.next();
            it.remove();
        }
        Path sf = seenFile();
        Path tmp = sf.resolveSibling(sf.getFileName() + "." + ProcessHandle.current().pid()
                + "-" + System.nanoTime() + ".tmp");
        try {
            Files.createDirectories(sf.getParent() == null ? Path.of(".") : sf.getParent());
            Files.writeString(tmp, GSON.toJson(java.util.List.copyOf(s), java.util.List.class),
                    StandardCharsets.UTF_8);
            try {
                Files.move(tmp, sf, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, sf, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored2) { }
        }
    }

    public Path file() {
        return file;
    }

    /** 当前队列深度。 */
    public int size() {
        lock.lock();
        try {
            return readAll().size();
        } finally {
            lock.unlock();
        }
    }

    /** 写一条备忘录。队列满、或字段超长时返回 false（调用方须回报，不要假装成功）。 */
    public boolean append(Memo memo) {
        if (oversized(memo)) {
            return false;
        }
        lock.lock();
        try {
            List<Memo> all = readAll();
            if (all.size() >= MAX_QUEUE) {
                return false;
            }
            // 2026-11-02：曾经入队过的 id 不再回来（含复核后已移除的、手动隔离的）。
            // 只查「当前队列」挡不住换代重扫：被复核移除的旧 memo 会以同一 id 重新入队。
            if (memo.id() != null && seen().contains(memo.id())) {
                return true;
            }
            // 幂等键（2026-10-25 换代重扫事故）：同一 memo id 在队列里只允许存在一份。
            // 事故形态：自动入队的幂等集只在内存，世界重载=新代=游标归零=全量重扫，
            // 新 JVM 空集 → 同 8 条事件入队 4 次（e44 队列 27 条、去重后只剩 9）。
            // 这道闸放在**队列本身**而不是调用方：任何入口（手动/自动/未来新增）
            // 都不可能再把同一 id 塞两遍——队列是唯一真源，幂等是它的不变量。
            // 返回 true（假装成功）：对调用方而言"这条已在队列里"与"刚放进去"
            // 语义等价，都不需要再放；返回 false 会让自动口误报 queue_full。
            for (Memo existing : all) {
                if (existing.id() != null && existing.id().equals(memo.id())) {
                    return true;
                }
            }
            all.add(memo);
            writeAll(all);
            remember(memo.id());
            return true;
        } finally {
            lock.unlock();
        }
    }

    private static boolean oversized(Memo m) {
        if (m == null) {
            return true;
        }
        return (m.problem() != null && m.problem().length() > MAX_FIELD_CHARS)
                || (m.tried() != null && m.tried().length() > MAX_FIELD_CHARS)
                || (m.snapshot() != null && m.snapshot().length() > MAX_FIELD_CHARS)
                || (m.stage() != null && m.stage().length() > 500);
    }

    /**
     * 取走最多 limit 条（不移除！）。
     *
     * <p>调用方复盘成功后必须 {@link #commitDrain}，失败必须 {@link #restore}。
     * 这样「清空队列」不等于「数据丢失」。
     */
    public List<Memo> drain(int limit) {
        lock.lock();
        try {
            List<Memo> all = readAll();
            if (limit <= 0 || all.isEmpty()) {
                return List.of();
            }
            return List.copyOf(all.subList(0, Math.min(limit, all.size())));
        } finally {
            lock.unlock();
        }
    }

    /** 按 id 指定取批（2026-11-06）：只取请求的 memo，未找到的忽略；顺序保持队列原序。 */
    public List<Memo> drainByIds(java.util.List<String> ids) {
        lock.lock();
        try {
            java.util.Set<String> want = new java.util.LinkedHashSet<>();
            if (ids != null) for (String id : ids) if (id != null && !id.isBlank()) want.add(id.trim());
            java.util.List<Memo> out = new ArrayList<>();
            for (Memo m : readAll()) {
                if (m.id() != null && want.contains(m.id())) out.add(m);
            }
            return out;
        } finally {
            lock.unlock();
        }
    }

    /** 复盘成功：把这批 memoId 真正从队列移除。 */
    public int commitDrain(List<Memo> taken) {
        lock.lock();
        try {
            List<Memo> all = readAll();
            List<Memo> kept = new ArrayList<>();
            for (Memo m : all) {
                boolean wasTaken = false;
                for (Memo t : taken) {
                    if (t.id() != null && t.id().equals(m.id())) {
                        wasTaken = true;
                        break;
                    }
                }
                if (!wasTaken) {
                    kept.add(m);
                }
            }
            writeAll(kept);
            return all.size() - kept.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 复盘失败：把没处理掉的条目原样写回队列尾部。
     *
     * <p>遵守 {@link #MAX_QUEUE}：restore 只补到上限，绝不为了「不丢数据」
     * 把队列撑爆（Codex 2026-09-29 审出：原实现无上限，可能突破 64）。
     * 超限的条目数会被返回，调用方据此报「仍有 N 条待处理」。
     */
    public int restore(List<Memo> taken) {
        lock.lock();
        try {
            List<Memo> all = readAll();
            int dropped = 0;
            for (Memo m : taken) {
                if (m == null || m.id() == null || oversized(m)) {
                    continue;
                }
                boolean present = false;
                for (Memo e : all) {
                    if (m.id().equals(e.id())) {
                        present = true;
                        break;
                    }
                }
                if (present) {
                    continue;
                }
                if (all.size() >= MAX_QUEUE) {
                    dropped++;
                    continue;
                }
                all.add(m);
            }
            writeAll(all);
            return all.size();
        } finally {
            lock.unlock();
        }
    }

    public List<Memo> all() {
        lock.lock();
        try {
            return List.copyOf(readAll());
        } finally {
            lock.unlock();
        }
    }

    /**
     * 读队列。
     *
     * <p><b>读取失败绝不按「空队列」继续</b>（2026-09-29 P1 修复，Codex 审出）：
     * 原实现在 IOException/解析异常时返回空 list，紧接着 {@code append} 的
     * 全量重写会把原文件覆盖掉 —— 一条读失败 = 全部备忘录静默丢失。
     * 现在读失败直接抛，调用方（工具层）会如实回报失败，坏文件原地保留待查。
     */
    private List<Memo> readAll() {
        try {
            if (!Files.exists(file)) {
                return new ArrayList<>();
            }
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            if (raw.isBlank()) {
                return new ArrayList<>();
            }
            List<Memo> parsed = GSON.fromJson(raw, LIST_TYPE);
            return parsed == null ? new ArrayList<>() : new ArrayList<>(parsed);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException(
                    "cannot read memo queue (file left untouched, nothing overwritten): " + file, e);
        }
    }

    private void writeAll(List<Memo> all) {
        // 临时名带 PID+纳秒：同一路径的多个进程/实例不会互相覆盖 tmp
        Path tmp = file.resolveSibling(file.getFileName() + "." + ProcessHandle.current().pid()
                + "-" + System.nanoTime() + ".tmp");
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(tmp, GSON.toJson(all, LIST_TYPE), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // 临时文件删不掉不该盖掉真正的失败原因
            }
            throw new IllegalStateException("cannot persist memo queue: " + file, e);
        }
    }
}
