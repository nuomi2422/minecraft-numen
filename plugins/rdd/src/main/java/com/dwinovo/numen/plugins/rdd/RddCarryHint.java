package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.ai.AiLog;
import com.dwinovo.numen.api.carrier.CarrierChain;
import com.dwinovo.numen.api.carrier.CarrierRuleStore;
import com.dwinovo.numen.api.carrier.CarrierRules;
import com.dwinovo.numen.api.carrier.ItemSemantics;
import com.dwinovo.numen.entity.NumenPlayer;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponents;
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
        // ⚠️ 2026-10-01 实机抓到（0 命中 <carry> 的真凶）：**时间戳必须先判断线程、再写**。
        //    原来我在判 isSameThread 之前就 CARRY_HINT_AT.put(...)，于是
        //    「在非主线程被调」→ 写完时间戳就 return → 缓存永远填不上，
        //    而 findByUuid 根本没执行，而且**不报错**。
        //    这是我今天第三次犯「兜底加到把功能关掉」—— 顺序错了，兜底就变成永久 no-op。
        if (!server.isSameThread()) {
            // 非主线程：**不写时间戳**（否则下次在主线程也会被节流窗口挡住），只丢掉过期的
            if (at != null && now - at > REFRESH_MS * 4) {
                CARRY_HINT.remove(companionId);
                CARRY_HINT_AT.remove(companionId);
            }
            AiLog.LOG.debug("[rdd] carry refresh 跳过：不在服务端主线程 {}", companionId);
            return;
        }
        CARRY_HINT_AT.put(companionId, now);
        try {
            NumenPlayer body = NumenPlayer.findByUuid(server, companionId);
            if (body == null || body.serverLevel() == null) {
                CARRY_HINT.remove(companionId);
                CARRY_HINT_AT.remove(companionId);
                AiLog.LOG.info("[rdd] carry refresh：同伴不在世界里（body={}），本轮不注入 <carry> {}",
                        body == null ? "null" : "level-null", companionId);
                return;
            }
            String hint = render(body);
            if (hint.isEmpty()) {
                CARRY_HINT.remove(companionId);
                AiLog.LOG.info("[rdd] carry refresh：快照或分级结果为空（无 <carry> 可注入）{}", companionId);
            } else {
                CARRY_HINT.put(companionId, hint);
                AiLog.LOG.info("[rdd] carry refresh：已注入 <carry> {} 字符 {}", hint.length(), companionId);
            }
        } catch (Throwable t) {
            // 携带器是「锦上添花」，任何问题都不许影响主链路。
            // ⚠️ 这里也**不写时间戳** —— 失败了要让下次还能重试，不能被节流锁死。
            CARRY_HINT.remove(companionId);
            CARRY_HINT_AT.remove(companionId);
            AiLog.LOG.info("[rdd] carry refresh 异常（本轮不注入）: {}", t.toString());
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
        // B6/S2：走 effective()（DEFAULT + 已批准的携带器规则），不再写死 DEFAULT。
        // 「已批准但运行时不生效」是最坏的一种状态：审批界面显示成功，游戏里没变化，
        // 没人查得出来。所以生效链必须与审批落点读同一处。
        CarrierChain.Result r = CarrierChain.evaluate(CarrierRuleStore.effective(), facts);
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
        int notEvaluated = Math.max(0, CarrierRuleStore.effective().size() - r.stoppedAt() - 1);
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
            // ★ food = **饱食度**（0-20），不是「背包里有没有吃的」。
            //   两者是两件事：饱食度高也可能一颗食物都没有（刚吃完），
            //   饱食度低也可能背包塞满面包。想让规则按「有没有吃的」判，
            //   用 food_items（下面那个），别把 food 当库存读。
            sb.append(", food=").append(body.getFoodData().getFoodLevel());
            // ★ 2026-10-06 补：背包（含副手）里**可食用**物品的总个数。
            //   取不到写 -1（= 未知），**不写 0** —— 0 是「真的没有」这个真值，
            //   把「没读到」写成 0 会让规则在「其实有食物」时也触发（DL-4：UNKNOWN ≠ 0）。
            sb.append(", food_items=").append(edibleCount(body));

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

    /**
     * 背包（含副手）里<b>可食用</b>物品的总个数。
     *
     * <p><b>为什么按食物数据组件判而不是按名字</b>：这是模组整合包
     * （暮色森林等），食物大多不叫 {@code *_food} / {@code bread}。
     * 按名字猜会漏掉一整片模组食物，而漏掉的后果是「系统说没有食物，其实有一背包」——
     * 正是 DL-4 要禁的那类假事实。{@code DataComponents.FOOD} 走的是物品自己注册的
     * {@code FoodProperties}，模组食物只要正常注册就一并覆盖。
     *
     * <p>数的是<b>个数</b>（{@code getCount()} 累加），不是槽位数 ——
     * 「还剩 3 个面包」和「还剩 1 格面包」对「有没有得吃」是两回事。
     *
     * <p>范围：{@code getContainerSize()} 覆盖快捷栏 + 主背包 + 盔甲 + <b>副手</b>
     * （见 {@code PlayerInv} 的注释），所以副手里的食物也算得到。
     *
     * @return 可食用物品总数；读世界失败返回 <b>-1</b>（未知，不是 0）
     */
    private static int edibleCount(NumenPlayer body) {
        try {
            var inv = body.getInventory();
            int n = 0;
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack st = inv.getItem(i);
                if (!st.isEmpty() && st.get(DataComponents.FOOD) != null) {
                    n += st.getCount();
                }
            }
            return n;
        } catch (RuntimeException e) {
            // 世界卸载 / 区块未加载：缺这一项不该让整份快照失败，但也**不许**写成 0
            return -1;
        }
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
