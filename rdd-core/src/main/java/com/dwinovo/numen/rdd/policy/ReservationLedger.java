package com.dwinovo.numen.rdd.policy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 关键资源<b>软预留账本</b>（G1c，75号 §15.4 Reservation / IC05 / D11）。
 *
 * <p><b>为什么需要它</b>：需求侧只说「这个目标要 32 个火把」，但没有任何结构记录
 * 「这 32 个已经许给谁」。于是两个目标可以同时把同一批库存当成自己的可用量 ——
 * 到了执行现场才发现不够。D11 用户已确认：<b>关键资源软预留，普通材料按需分配</b>；
 * 软预留可协商、不锁死，但<b>不能被静默抢走</b>。
 *
 * <p><b>三条刻意的语义</b>：
 * <ol>
 *   <li><b>全有或全无</b>：申请量超过「现有 − 他人有效预留」直接 DENIED，不给部分授予。
 *       部分授予会让调用方以为「够用」而实际只拿到一半（假安全比拒绝更糟）。</li>
 *   <li><b>优先级是信息不是权力</b>：{@code priority} 只记录谁更该先拿（冲突时供协商），
 *       本类<b>不</b>抢占、不驱逐 —— 软预留不是锁（D11）。</li>
 *   <li><b>过期只释放承诺</b>：{@link #releaseExpired} 只把条目移出有效集，
 *       <b>不</b>产生任何「资产丢失」语义（IC05：过期释放不记物理丢失）。</li>
 * </ol>
 *
 * <p>线程安全（synchronized）；纯 JVM、无 MC 依赖，可单测。持久化由调用方决定 ——
 * 预留是「当前计划的承诺」，换计划即失效，刻意不进存档（重启后由新计划重新申请）。
 */
public final class ReservationLedger {

    /** 一条有效预留。{@code leaseDeadlineMillis <= 0} 表示不设到期。 */
    public record Reservation(long id, String demandKey, String resourceKey, int quantity,
                              int priority, long leaseDeadlineMillis) {}

    /**
     * 申请结果。{@code ok=false} 时 {@code reason} 说明缺多少（给日志/事件用，不是给用户看的判决）。
     */
    public record Grant(boolean ok, long id, int granted, int available, String reason) {}

    private final Map<Long, Reservation> active = new LinkedHashMap<>();
    private long nextId = 1;

    /**
     * 申请（全有或全无）。
     *
     * <p>同一 {@code (demandKey, resourceKey)} 重复申请是<b>幂等替换</b>：先把自己的旧预留
     * 从「他人占用」里排除，再按新数量重算 —— 否则每 30s tick 都申请一次会把自己越算越穷。
     *
     * @param held 该资源的当前真实持有量（缺 key = 0，不猜）
     */
    public synchronized Grant reserve(String demandKey, String resourceKey, int requested,
                                      int priority, long leaseDeadlineMillis, Map<String, Integer> held) {
        if (demandKey == null || demandKey.isBlank() || resourceKey == null || resourceKey.isBlank()) {
            return new Grant(false, -1, 0, 0, "demandKey and resourceKey required");
        }
        int want = Math.max(0, requested);
        int heldQty = held == null ? 0 : Math.max(0, held.getOrDefault(resourceKey, 0));
        int own = activeForResource(resourceKey, demandKey);
        int others = activeTotal(resourceKey) - own;
        int available = Math.max(0, heldQty - others);
        if (want > available) {
            // 不部分授予：调用方要 25、只剩 20 时给 20 会让它以为够了
            return new Grant(false, -1, 0, available,
                    "requested " + want + " > available " + available
                            + " (held " + heldQty + ", others reserved " + others + ")");
        }
        // 幂等：同股同量直接返回原身份，避免每次 tick 产生新条目
        Long existing = findActive(demandKey, resourceKey);
        if (existing != null && active.get(existing).quantity() == want) {
            return new Grant(true, existing, want, available, "");
        }
        if (existing != null) {
            active.remove(existing);
        }
        if (want == 0) {
            return new Grant(true, -1, 0, available, "");
        }
        long id = nextId++;
        active.put(id, new Reservation(id, demandKey, resourceKey, want, priority, leaseDeadlineMillis));
        return new Grant(true, id, want, available, "");
    }

    /** 主动释放（计划结束/换阶段时调用）。返回是否真的释放了一条。 */
    public synchronized boolean release(long id) {
        return active.remove(id) != null;
    }

    /** 释放某需求名的全部预留（换 primary / 计划作废）。返回释放条数。 */
    public synchronized int releaseAllOf(String demandKey) {
        if (demandKey == null) {
            return 0;
        }
        List<Long> ids = new ArrayList<>();
        for (Reservation r : active.values()) {
            if (demandKey.equals(r.demandKey())) {
                ids.add(r.id());
            }
        }
        ids.forEach(active::remove);
        return ids.size();
    }

    /** 释放除 {@code keepDemandKey} 之外的全部预留（换 primary 时用）。返回释放条数。 */
    public synchronized int retainOnly(String keepDemandKey) {
        List<Long> ids = new ArrayList<>();
        for (Reservation r : active.values()) {
            if (keepDemandKey != null && !keepDemandKey.equals(r.demandKey())) {
                ids.add(r.id());
            }
        }
        ids.forEach(active::remove);
        return ids.size();
    }

    /**
     * 过期释放：只把条目移出有效集，<b>不</b>写「丢失」（IC05）。
     *
     * @return 释放条数
     */
    public synchronized int releaseExpired(long nowMillis) {
        List<Long> expired = new ArrayList<>();
        for (Reservation r : active.values()) {
            if (r.leaseDeadlineMillis() > 0 && nowMillis >= r.leaseDeadlineMillis()) {
                expired.add(r.id());
            }
        }
        expired.forEach(active::remove);
        return expired.size();
    }

    /** 有效预留总量（resourceKey → 件数）。 */
    public synchronized Map<String, Integer> reservedAmounts() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Reservation r : active.values()) {
            out.merge(r.resourceKey(), r.quantity(), Integer::sum);
        }
        return Collections.unmodifiableMap(out);
    }

    /** 某需求在某资源上的有效预留量。 */
    public synchronized int reservedBy(String demandKey, String resourceKey) {
        int total = 0;
        for (Reservation r : active.values()) {
            if (r.resourceKey().equals(resourceKey) && r.demandKey().equals(demandKey)) {
                total += r.quantity();
            }
        }
        return total;
    }

    /**
     * 「对某个需求而言的可用量」：现有 − <b>其他需求</b>的有效预留。
     *
     * <p>自己的预留不扣自己的（否则当前目标会把自己算穷）；未知资源 = 0，不猜。
     */
    public synchronized Map<String, Integer> available(Map<String, Integer> held, String demandKey) {
        if (held == null || held.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : held.entrySet()) {
            int others = activeTotal(e.getKey()) - (demandKey == null ? 0 : reservedBy(demandKey, e.getKey()));
            out.put(e.getKey(), Math.max(0, (e.getValue() == null ? 0 : e.getValue()) - others));
        }
        return Collections.unmodifiableMap(out);
    }

    /** 有效预留快照（测试/事件用）。 */
    public synchronized List<Reservation> reservations() {
        return List.copyOf(active.values());
    }

    // ---- 内部 ----

    private int activeTotal(String resourceKey) {
        int total = 0;
        for (Reservation r : active.values()) {
            if (r.resourceKey().equals(resourceKey)) {
                total += r.quantity();
            }
        }
        return total;
    }

    private int activeForResource(String resourceKey, String demandKey) {
        return reservedBy(demandKey, resourceKey);
    }

    private Long findActive(String demandKey, String resourceKey) {
        for (Reservation r : active.values()) {
            if (r.resourceKey().equals(resourceKey) && r.demandKey().equals(demandKey)) {
                return r.id();
            }
        }
        return null;
    }
}
