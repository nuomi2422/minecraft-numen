package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.UUID;

/**
 * 常驻环境块 {@code <env>}（2026-10-28 提案落地，见 rdd架构\常驻环境块-轻量方案-20261028.md）。
 *
 * <p><b>为什么要它</b>：她以前只能靠工具主动去探环境（scan_blocks / get_world_info），
 * 而一个回合往往只调一两个工具——**多数决定是在"不知道周围有什么"的情况下做的**。
 * 实机代价：判据指向身边不存在的箱子、站在熄灭的门框里等、手上握着石头却以为能打、
 * 走远 500 格后不知道门在哪。人不是这样干活的：**先看见，再决定**。
 *
 * <p><b>边界</b>：只写事实，不写建议；固定 ≤12 行、≤400 字符；内容不变就返回同样的字符串
 * （稳前缀、省 token）。超长时按"上一步 → 危险 → 背包"的顺序丢行。
 */
final class RddEnvBlock {

    private RddEnvBlock() {}

    /** 上一次受伤的游戏时间（按同伴）。 */
    private static final Map<UUID, Long> LAST_HURT_TICK = new java.util.concurrent.ConcurrentHashMap<>();
    /** 上次看到的血量，用来判断"刚才是不是挨打了"。 */
    private static final Map<UUID, Float> LAST_HP = new java.util.concurrent.ConcurrentHashMap<>();

    /** 每 tick 调一次（很便宜：只比较血量和时间）。 */
    static void observeHealth(NumenPlayer ap) {
        if (ap == null) return;
        UUID id = ap.getUUID();
        float hp = ap.getHealth();
        Float prev = LAST_HP.put(id, hp);
        if (prev != null && hp < prev - 0.01f) {
            LAST_HURT_TICK.put(id, ap.level().getGameTime());
        }
    }

    /**
     * 组装环境块。返回空串表示"拿不到身体"（不硬编一块假的）。
     *
     * @param counts 本拍背包统计（可为 null）
     */
    static String build(NumenPlayer ap, Map<String, Integer> counts) {
        if (ap == null || ap.level() == null) return "";
        try {
            StringBuilder sb = new StringBuilder(420);
            sb.append("<env>\n");

            // ① 我在哪：维度 + 坐标 + 眼高/脚下
            BlockPos p = ap.blockPosition();
            String dim = shortDim(ap.level().dimension().location().toString());
            BlockState feet = ap.level().getBlockState(p);
            BlockState eye = ap.level().getBlockState(p.above());
            sb.append("位置 ").append(dim).append(" (").append(p.getX()).append(',')
              .append(p.getY()).append(',').append(p.getZ()).append(") 脚下")
              .append(shortBlock(feet)).append(" 眼高").append(shortBlock(eye)).append('\n');

            // ② 身边 8 格：门(亮/熄) · 箱子 · 岩浆 · 悬崖
            int portals = 0, litPortals = 0, chests = 0, lava = 0;
            // 2026-10-28 修：原来垂直只扫 ±4，而她在 y=39、门框在 y=58（差 19）⇒ 永远报"门无"。
            //   水平 ±8、垂直 ±12，够看见"上方那扇门"。
            for (BlockPos q : BlockPos.betweenClosed(p.offset(-8, -12, -8), p.offset(8, 12, 8))) {
                BlockState st = ap.level().getBlockState(q);
                if (st.is(Blocks.NETHER_PORTAL)) { portals++; litPortals++; }
                else if (st.is(Blocks.OBSIDIAN) || st.is(Blocks.CRYING_OBSIDIAN)) { portals++; }
                else if (st.is(Blocks.CHEST) || st.is(Blocks.BARREL) || st.is(Blocks.TRAPPED_CHEST)) chests++;
                else if (st.is(Blocks.LAVA)) lava++;
            }
            boolean cliff = !ap.onGround() && ap.fallDistance > 2.0f;
            sb.append("身边 门").append(portals == 0 ? "无" : (litPortals > 0 ? "亮" : "(熄)"))
              .append(" · 箱子").append(chests)
              .append(" · 岩浆").append(lava).append("格")
              .append(" · 悬崖").append(cliff ? "脚下悬空" : (ap.onGround() ? "无" : "高处")).append('\n');

            // ③ 手上
            String main = shortItem(ap.getMainHandItem());
            String off = shortItem(ap.getOffhandItem());
            boolean toolOk = isTool(ap.getMainHandItem());
            sb.append("手上 ").append(main).append(off.isEmpty() ? "" : (" · 副手" + off))
              .append(toolOk ? " · 可挖" : " · 不是工具").append('\n');

            // ④ 背包（用本拍真实统计）
            int kinds = 0, total = 0;
            if (counts == null) {
                // 调用方没给统计就从背包现数（同口径：非空槽位）
                counts = new java.util.LinkedHashMap<>();
                for (net.minecraft.world.item.ItemStack s : ap.getInventory().items) {
                    if (s == null || s.isEmpty()) continue;
                    String k = s.getItem().builtInRegistryHolder().key().location().toString();
                    counts.merge(k, s.getCount(), Integer::sum);
                }
            }
            if (counts != null) {
                for (Map.Entry<String, Integer> e : counts.entrySet()) {
                    kinds++;
                    total += e.getValue() == null ? 0 : e.getValue();

                }
            }
            sb.append("背包 ").append(kinds).append("种 ").append(total).append("件")
              .append("").append('\n');

            // ⑤ 危险：最近受伤多久前
            Long hurt = LAST_HURT_TICK.get(ap.getUUID());
            long now = ap.level().getGameTime();
            String hurtText = hurt == null ? "无记录" : (Math.max(0, (now - hurt) / 20L) + "秒前");
            sb.append("危险 最近受伤").append(hurtText)
              .append(" · 血量").append(Math.round(ap.getHealth()))
              // 2026-10-29（经验库 #37 的配套）：低血只报数字没用，要直接说该干什么。
              //   实机：下界 hp 18.95→2.5 只用一分钟、hunger 满，她既没撤也没吃。
              .append(ap.getHealth() <= 12.0f
                      ? " · **正在持续掉血：先撤到安全处（退回门/回主世界），别继续打**"
                      : "")
              .append('\n');

            // 2026-10-29 实机：她进末地后站在平台上一动不动 3 分钟，只反复查状态 ——
            //   目标(杀水晶)陌生、环境(虚空+飞龙)全新，检索没命中「末地怎么打」。
            //   env 是**每回合强制在场**的通道，所以这里直接给末地该干什么（不依赖她主动检索）。
            if (ap.level().dimension() == net.minecraft.world.level.Level.END) {
                sb.append("末地 10 根黑曜石柱顶各有末影水晶（**不清完龙不掉血**，用弓箭射）")
                  .append(" · 龙落到中央基岩喷泉时才能砍到 · **别站平台边缘**，被撞下去就死")
                  .append('\n');
            }


            // ⑥ 回家：门在哪、亮不亮（来自锚点记忆）
            // 2026-10-28 修：原来把整段"动作指引"塞进来（120 字），占了块体积的一半。
            //   这里只留**事实**：门在哪、多远、亮不亮；动作指引仍然只留在 <way_home>。
            // 2026-11-03（外部学习者）：原来**优先取"下界"那一侧的锚点**，且行里不写维度 ——
            //   实测（20:25 请求）：人站在主世界基地旁 (399,60,643)，env 却写「回家 门在 (38,38,32) 约 710格」，
            //   那是**下界侧**的门。照字面走就是往主世界 710 格外走。
            //   RddWaypoints 的注释也写着"回家要走向当前维度这一侧的门"，所以改成：优先当前维度，
            //   取不到再退回另一侧，并且**永远标出维度**（坐标脱离维度就是误导）。
            String hereDim = ap.level().dimension().location().toString();
            String otherDim = "minecraft:the_nether".equals(hereDim) ? "minecraft:overworld" : "minecraft:the_nether";
            int[] anchor = RddWaypoints.anchorPos(ap.getUUID(), hereDim);
            String anchorDim = hereDim;
            if (anchor == null) {
                anchor = RddWaypoints.anchorPos(ap.getUUID(), otherDim);
                anchorDim = otherDim;
            }
            if (anchor != null) {
                double dx = anchor[0] - p.getX(), dy = anchor[1] - p.getY(), dz = anchor[2] - p.getZ();
                long dist = Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz));
                String lit = RddWaypoints.litState(ap.getUUID(), anchorDim);
                sb.append("回家 门在 (").append(anchor[0]).append(',').append(anchor[1]).append(',')
                  .append(anchor[2]).append(")（").append(dimLabel(anchorDim)).append("）约 ")
                  .append(dist).append("格")
                  .append(lit.isEmpty() ? "" : (" · " + lit)).append('\n');
            }
            sb.append("</env>");
            return compact(sb.toString());
        } catch (RuntimeException ignored) {
            return "";   // 环境块不许拖垮上下文组装
        }
    }

    /**
     * Keep the environment block well-formed while preserving safety facts.
     * The old substring(0, 200) could cut inside a line/tag and discard the
     * danger and home lines, exactly the facts that justify conservative action.
     */
    private static String compact(String raw) {
        if (raw.length() <= 400) return raw;
        String[] lines = raw.split("\\n");
        StringBuilder out = new StringBuilder(400);
        out.append(lines[0]).append('\n');
        for (int i = 1; i < lines.length - 1; i++) {
            String line = lines[i];
            // Never silently drop these semantic lines; trim only at a line boundary.
            boolean critical = line.startsWith("位置 ") || line.startsWith("身边 ")
                    || line.startsWith("手上 ") || line.startsWith("背包 ")
                    || line.startsWith("危险 ") || line.startsWith("末地 ")
                    || line.startsWith("回家 ");
            if (!critical) continue;
            int room = 400 - out.length() - 16; // marker + closing tag
            if (room <= 4) break;
            String safe = line.length() <= room ? line : line.substring(0, Math.max(1, room - 1)) + "…";
            out.append(safe).append('\n');
        }
        out.append("…(略)…\n</env>");
        return out.length() <= 400 ? out.toString() : out.substring(0, 400 - "</env>".length()) + "</env>";
    }

    private static String shortDim(String id) {
        int i = id.indexOf(':');
        return i >= 0 ? id.substring(i + 1) : id;
    }

    private static String shortBlock(BlockState st) {
        String id = st.getBlock().builtInRegistryHolder().key().location().getPath();
        if (id.equals("air")) return "空气";
        return id;
    }

    private static String shortItem(net.minecraft.world.item.ItemStack st) {
        if (st == null || st.isEmpty()) return "空手";
        String id = st.getItem().builtInRegistryHolder().key().location().getPath();
        return id + (st.getCount() > 1 ? ("×" + st.getCount()) : "");
    }

    private static boolean isTool(net.minecraft.world.item.ItemStack st) {
        if (st == null || st.isEmpty()) return false;
        var item = st.getItem();
        return item instanceof net.minecraft.world.item.DiggerItem
                || item instanceof net.minecraft.world.item.SwordItem;
    }

    private static String oneLine(String s) {
        String t = s.replace("<way_home>", "").replace("</way_home>", "")
                    .replace("\n", " ").trim();
        return t.length() > 120 ? t.substring(0, 120) + "…" : t;
    }

    /** 维度 id → 人话（只用于 env 的可读标注，不改任何判定）。 */
    private static String dimLabel(String dim) {
        if (dim == null) return "?维度";
        return switch (dim) {
            case "minecraft:overworld" -> "主世界";
            case "minecraft:the_nether" -> "下界";
            case "minecraft:the_end" -> "末地";
            default -> dim;
        };
    }
}
