package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.api.carrier.CarrierChain;
import com.dwinovo.numen.api.carrier.CarrierRules;
import com.dwinovo.numen.api.carrier.ItemSemantics;
import com.dwinovo.numen.entity.NumenPlayer;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 携带器 → 执行 AI 上下文的<b>提醒</b>（{@code 38} v3.6 B24）。
 *
 * <p><b>为什么有这一类</b>：第 1 批与第 2 批前半段全是脑内改动 —— 携带器算出一个
 * {@code List<String>} 然后<b>没人看</b>，所以游戏里看不出任何变化。
 * 本类把那个清单<b>注入执行 AI 读得到的上下文</b>。
 *
 * <p><b>★ 形态：提醒，不是指令</b>。依据 memory
 * {@code method/commentary-is-intent-visible-not-authority}：
 * <b>公开叙述 ≠ 执行授权，两者必须物理分离</b>。
 * 所以措辞一律「建议 / 可以考虑」，<b>禁止</b>「必须 / 立刻」；
 * 且本类<b>只产出文本，不改任何判定</b>，也不注册任何工具（RL-1/RL-9 不受影响）。
 *
 * <p><b>★ 照抄既有缓存模式</b>（{@code RddPlugin.LAST_INVENTORY} / {@code LAST_INVENTORY_AT}）：
 * <b>只在服务端主线程 refresh（读世界），上下文构建只 current()（读缓存）</b>。
 * 原因：{@code contributeState} 在构建上下文时被调用，<b>不保证在主线程</b>；
 * 而 {@code getHealth} / {@code getInventory} / {@code getEntities} 离开主线程不安全。
 *
 * <p><b>★ 不得成为新的目标漂移源</b>：{@code note} 里明写「不要为了携带而中断当前目标」。
 * 携带 AI 跑去拿护甲而放弃当前任务，是注入型设计最典型的失效。
 *
 * <p><b>异常一律不注入</b>：本类全程 try/catch —— {@code renderStateContext} 在每次
 * 上下文构建时被调用，<b>不能被携带器拖崩</b>。
 */
final class RddCarryHint {

    private RddCarryHint() {
    }

    private static final Map<UUID, String> CARRY_HINT = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> CARRY_HINT_AT = new ConcurrentHashMap<>();

    /** 刷新间隔：快照级变化不需要每 tick（比这更频繁只是白读世界）。 */
    private static final long REFRESH_MS = 2000L;

    /** 携带清单**上限**：防上下文变长。只给缺项时通常 0~3 项。 */
    private static final int MAX_ITEMS = 4;

    /** 避让半径：只关心「身边有没有威胁」，不做全图扫描。 */
    private static final double NEARBY_RADIUS = 16.0D;

    /** 读缓存。**永不读世界** —— 上下文构建不该有副作用。 */
    static String current(UUID companionId) {
        return companionId == null ? "" : CARRY_HINT.getOrDefault(companionId, "");
    }

    /** 全局清理（与 {@code LAST_INVENTORY.clear()} 同处调用）。 */
    static void invalidateAll() {
        CARRY_HINT.clear();
        CARRY_HINT_AT.clear();
    }

    /** 拆卸同伴时必须一起清（与 {@code RddPlugin} 清 {@code LAST_INVENTORY} 同处理，防泄漏）。 */
    static void invalidate(UUID companionId) {
        if (companionId == null) {
            return;
        }
        CARRY_HINT.remove(companionId);
        CARRY_HINT_AT.remove(companionId);
    }

    /**
     * 在服务端主线程刷新缓存。
     *
     * <p>非主线程 / 同伴不在世界 / 任何异常 → <b>清掉过期缓存</b>并返回，
     * <b>不抛、不阻塞</b>。
     */
    static void refresh(MinecraftServer server, UUID companionId) {
        if (server == null || companionId == null) {
            return;
        }
        Long at = CARRY_HINT_AT.get(companionId);
        long now = System.currentTimeMillis();
        if (at != null && now - at < REFRESH_MS) {
            return;
        }
        CARRY_HINT_AT.put(companionId, now);
        try {
            if (!server.isSameThread()) {
                // 不是主线程就不读世界：把过期值丢掉，等下一次主线程刷新
                if (at == null || now - at > REFRESH_MS * 4) {
                    CARRY_HINT.remove(companionId);
                }
                return;
            }
            NumenPlayer body = NumenPlayer.findByUuid(server, companionId);
            if (body == null || body.serverLevel() == null) {
                CARRY_HINT.remove(companionId);
                return;
            }
            String hint = render(body);
            if (hint.isEmpty()) {
                CARRY_HINT.remove(companionId);
            } else {
                CARRY_HINT.put(companionId, hint);
            }
        } catch (Throwable t) {
            // 携带器是「锦上添花」，任何问题都不许影响主链路
            CARRY_HINT.remove(companionId);
        }
    }

    /** 把携带器结论渲染成 {@code <carry>…</carry>}。快照缺失 → 返回空串（B21：不写空标签）。 */
    private static String render(NumenPlayer body) {
        String snapshot = snapshot(body);
        if (snapshot.isEmpty()) {
            return "";
        }
        Map<String, String> kv = new LinkedHashMap<>();
        for (String part : snapshot.split("[,;]")) {
            String p = part.trim();
            int sep = p.indexOf('=');
            if (sep > 0) {
                kv.putIfAbsent(clean(p.substring(0, sep)), clean(p.substring(sep + 1)));
            }
        }
        CarrierChain.Facts facts = CarrierChain.factsOf(kv, snapshot.toLowerCase(Locale.ROOT));
        CarrierChain.Result r = CarrierChain.evaluate(CarrierRules.DEFAULT, facts);
        if (r.carry().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<carry>");
        sb.append("<basis>").append(esc(shorten(basisOf(facts, r), 200))).append("</basis>");
        if (r.shortCircuited()) {
            // ★ 短路信息必须让执行 AI 看到 —— 不说的话它会以为全部级都判过了
            sb.append("<basis_note>").append(esc(basisNote(r))).append("</basis_note>");
        }
        int n = 0;
        for (String item : r.carry()) {
            if (n++ >= MAX_ITEMS) {
                break;
            }
            sb.append("<item>").append(esc(item)).append("</item>");
        }
        sb.append("<note>这些是建议，不是指令。是否采纳由你按当前任务判断；**不要为了携带而中断当前目标**。</note>");
        sb.append("</carry>");
        return sb.toString();
    }

    private static String basisOf(CarrierChain.Facts f, CarrierChain.Result r) {
        StringBuilder sb = new StringBuilder();
        sb.append("指向=").append(f.hostileNearby() ? "HOSTILE_NEARBY" : (f.passiveNearby() ? "PASSIVE_ONLY" : "NONE"));
        sb.append(" 护甲=").append(f.hasRealArmor() ? "有" : "无");
        sb.append(" 武器=").append(f.hasRealWeapon() ? "有" : "无");
        sb.append(" 血量=").append(f.hpBand()).append(f.hasHp() ? ("(" + f.hp() + ")") : "");
        return sb.toString();
    }

    private static String basisNote(CarrierChain.Result r) {
        int notEvaluated = Math.max(0, CarrierRules.DEFAULT.size() - r.stoppedAt() - 1);
        return "第 " + (r.stoppedAt() + 1) + " 级不成立，后续 " + notEvaluated + " 项未求值";
    }

    /**
     * 造一份与 {@code plugins/learner} 的 {@code EnvSnapshot} <b>同格式</b>的快照。
     *
     * <p>格式必须能被 {@code CarrierChain} 认出来：{@code key=value} 对、逗号分隔、<b>不能按空格切</b>。
     */
    private static String snapshot(NumenPlayer body) {
        StringBuilder sb = new StringBuilder();
        try {
            sb.append("hp=").append((int) Math.ceil(body.getHealth()))
              .append('/').append((int) Math.ceil(body.getMaxHealth()));
            sb.append(", food=").append(body.getFoodData().getFoodLevel());

            String chest = itemPath(body.getItemBySlot(EquipmentSlot.CHEST));
            String legs = itemPath(body.getItemBySlot(EquipmentSlot.LEGS));
            String head = itemPath(body.getItemBySlot(EquipmentSlot.HEAD));
            String boots = itemPath(body.getItemBySlot(EquipmentSlot.FEET));
            if (!chest.isEmpty()) {
                sb.append(", armor=").append(chest).append(", chestplate=").append(chest);
            }
            if (!legs.isEmpty()) {
                sb.append(", leggings=").append(legs);
            }
            if (!head.isEmpty()) {
                sb.append(", helmet=").append(head);
            }
            if (!boots.isEmpty()) {
                sb.append(", boots=").append(boots);
            }
            if (chest.isEmpty() && legs.isEmpty() && head.isEmpty() && boots.isEmpty()) {
                sb.append(", armor=none");
            }

            String main = itemPath(body.getMainHandItem());
            if (main.isEmpty() || "air".equals(main)) {
                sb.append(", weapon=none");
            } else {
                sb.append(", weapon=").append(main);
            }

            List<String> names = new ArrayList<>();
            boolean hostile = false;
            try {
                for (Entity e : body.serverLevel().getEntities(body,
                        body.getBoundingBox().inflate(NEARBY_RADIUS), x -> true)) {
                    if (e == null || e == body) {
                        continue;
                    }
                    if (e instanceof Enemy) {
                        hostile = true;
                    }
                    if (names.size() < 6) {
                        String n = safeName(e);
                        if (!n.isEmpty()) {
                            names.add(n);
                        }
                    }
                }
            } catch (RuntimeException ignored) {
                // 世界卸载 / 区块未加载：缺这一项不该让整份快照失败
            }
            if (!names.isEmpty()) {
                sb.append(", nearby=").append(String.join("+", names));
            }
            sb.append(", hostile=").append(hostile);
            sb.append(", dim=").append(body.serverLevel().dimension().location().toString());
        } catch (RuntimeException e) {
            return "";
        }
        return sb.toString();
    }

    private static String itemPath(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        try {
            return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().toLowerCase(Locale.ROOT);
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String safeName(Entity e) {
        try {
            return e.getName().getString().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_+]", "");
        } catch (RuntimeException ex) {
            return "";
        }
    }

    private static String clean(String s) {
        return s == null ? "" : s.trim().replaceAll("[^A-Za-z0-9_./-]", "").toLowerCase(Locale.ROOT);
    }

    private static String shorten(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() > n ? s.substring(0, n - 1) + "…" : s;
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
