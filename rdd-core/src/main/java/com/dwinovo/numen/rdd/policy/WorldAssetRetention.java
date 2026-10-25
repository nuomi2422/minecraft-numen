package com.dwinovo.numen.rdd.policy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 世界资产的"留谁、扔谁"（P0-B，81 号 §5.1 收窄版）。
 *
 * <h2>为什么需要它（实测代价）</h2>
 * 旧实现是"按 observedAt 丢最旧，总数 128"。实机两只同伴都顶到 128/128，
 * 里面 {@code world_entity} 占 64/44、村庄 25/16 —— <b>路过一次村民就能把基地挤出窗口</b>，
 * 而基地是"她记得这是自己家"的唯一凭据（丢了就真找不回来了，因为落盘文件里也已经是裁剪后的）。
 *
 * <h2>本批只做三件事（其余明确不做）</h2>
 * <ol>
 *   <li><b>受害者顺序</b>：实体 → 机器 → 村庄 → 结构 → 其它；<b>基地永不入选</b>。</li>
 *   <li><b>过期</b>：实体连续 {@code ttlTicks}（默认 24000 = 1 游戏日）没被重新观测 → 过期。</li>
 *   <li><b>原因</b>：每条动作都带 {@code reason}，供监测台/事件对账（"删了但不知道为什么"= 最坏）。</li>
 * </ol>
 *
 * <p><b>不做</b>（写在这里是为了防止下一个人以为忘了）：机器 7 日的 STALE 标记、
 * 持久层/活跃视图两层拆分、分页找回旧记录 —— 那三件要动存储形状，见 81 号 §5.1 的 H2 更正。
 *
 * <p><b>为什么不用墙钟</b>：睡眠、{@code /time set}、停服都会让墙钟与游戏时间脱钩；
 * 这里只吃调用方传进来的<b>单调游戏 tick</b>。没有 tick 元数据的旧条目（重启后）
 * 一律 {@code observed=false} → 本拍不接受淘汰，从"首次看到"开始重新计龄（不一口气全删）。
 *
 * <p>纯函数、零 MC 依赖，可离线单测。
 */
public final class WorldAssetRetention {

    /** 资产大类；声明顺序 = 受害优先级（越靠前越先被扔）。 */
    public enum Kind {
        ENTITY(0), MACHINE(1), VILLAGE(2), STRUCTURE(3), OTHER(4), BASE(5);

        private final int victimRank;

        Kind(int victimRank) {
            this.victimRank = victimRank;
        }

        public int victimRank() {
            return victimRank;
        }
    }

    public enum Action {
        KEEP,
        /** 超龄：实体超过 TTL。 */
        EXPIRE_TTL,
        /** 超额：总数超过上限，按受害者顺序清。 */
        EVICT_OVER_CAPACITY
    }

    /**
     * 一条待裁决的资产。
     *
     * @param observedTick 上次被真实观测到的游戏 tick；{@code observed=false} 表示没有这个元数据
     */
    public record Item(String assetId, Kind kind, long observedTick, boolean observed) {
        public Item {
            if (assetId == null || assetId.isBlank()) throw new IllegalArgumentException("assetId required");
            if (kind == null) kind = Kind.OTHER;
        }
    }

    /** 一条裁决；{@code KEEP} 也返回，方便调用方把"为什么留"一起记下来。 */
    public record Decision(String assetId, Kind kind, Action action, String reason, long ageTicks) {
    }

    private WorldAssetRetention() {
    }

    /** 观测类型 → 大类。未知类型归 {@code OTHER}（保守：排在基地之后、不会首轮被扔）。 */
    public static Kind kindOf(String observationType) {
        if (observationType == null) return Kind.OTHER;
        return switch (observationType) {
            case "world_entity" -> Kind.ENTITY;
            case "world_machine" -> Kind.MACHINE;
            case "world_village" -> Kind.VILLAGE;
            case "world_structure" -> Kind.STRUCTURE;
            case "world_base" -> Kind.BASE;
            default -> Kind.OTHER;
        };
    }

    /** 默认实体 TTL：1 游戏日 = 24000 tick。 */
    public static final long DEFAULT_ENTITY_TTL_TICKS = 24_000L;

    /** 默认活跃上限（与旧实现保持一致，避免同一批里又改容量又改顺序，出问题分不清因果）。 */
    public static final int DEFAULT_MAX_ACTIVE = 128;

    public static List<Decision> decide(List<Item> items, int maxActive, long nowTick, long entityTtlTicks) {
        List<Item> safe = items == null ? List.of() : items;
        int cap = maxActive <= 0 ? DEFAULT_MAX_ACTIVE : maxActive;
        long ttl = entityTtlTicks <= 0 ? DEFAULT_ENTITY_TTL_TICKS : entityTtlTicks;

        List<Decision> out = new ArrayList<>(safe.size());
        List<Item> alive = new ArrayList<>(safe.size());
        for (Item it : safe) {
            long age = it.observed() ? Math.max(0, nowTick - it.observedTick()) : -1L;
            if (it.kind() == Kind.ENTITY && it.observed() && age >= ttl) {
                out.add(new Decision(it.assetId(), it.kind(), Action.EXPIRE_TTL,
                        "entity_ttl_expired: no re-observation for " + age + " ticks (ttl=" + ttl + ")", age));
            } else {
                alive.add(it);
            }
        }

        if (alive.size() <= cap) {
            for (Item it : alive) {
                long age = it.observed() ? Math.max(0, nowTick - it.observedTick()) : -1L;
                out.add(new Decision(it.assetId(), it.kind(), Action.KEEP,
                        it.observed() ? "within_capacity" : "age_unknown_grace", age));
            }
            return out;
        }

        // 超额：受害者顺序 = 大类优先级 → 同大类内更旧先走。基地 rank 最高，永远排最后，且下面显式跳过。
        List<Item> victims = new ArrayList<>(alive);
        victims.sort(Comparator
                .comparingInt((Item it) -> it.kind().victimRank())
                // 同大类里：老的先走；没有 tick 元数据的（重启后）排最后 = 给一次"重新计龄"的宽限。
                .thenComparingLong(it -> it.observed() ? it.observedTick() : Long.MAX_VALUE));

        int toDrop = alive.size() - cap;
        for (Item it : victims) {
            long age = it.observed() ? Math.max(0, nowTick - it.observedTick()) : -1L;
            if (toDrop > 0 && it.kind() != Kind.BASE) {
                out.add(new Decision(it.assetId(), it.kind(), Action.EVICT_OVER_CAPACITY,
                        "over_capacity: victims=" + it.kind() + " rank=" + it.kind().victimRank()
                                + " (cap=" + cap + ", over=" + toDrop + ")", age));
                toDrop--;
            } else {
                out.add(new Decision(it.assetId(), it.kind(), Action.KEEP,
                        it.kind() == Kind.BASE ? "base_is_pinned" : "survived_victim_order", age));
            }
        }
        return out;
    }
}
