package com.dwinovo.numen.client.agent;

import com.dwinovo.numen.client.data.ClientNumenState;

import java.util.List;

/** Stateless rendering of the caller's current body snapshot; no cache reads or lifecycle. */
final class BodySnapshotRenderer {

    private BodySnapshotRenderer() {}

    static String renderEffects(ClientNumenState.Snapshot snapshot, long nowMs) {
        StringBuilder out = new StringBuilder();
        for (var effect : snapshot.effects()) {
            int left = snapshot.remainingTicks(effect, nowMs);
            if (left == 0) {
                continue;   // 收到之后已经走完了
            }
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(effect.getEffect().unwrapKey()
                    .map(key -> key.location().getPath()).orElse("unknown"));
            if (effect.getAmplifier() > 0) {
                out.append(" ").append(effect.getAmplifier() + 1);   // 原版 UI 的口径:0 级显示 I
            }
            out.append(left < 0 ? " (infinite)" : " (" + (left / 20) + "s left)");
        }
        return out.toString();
    }

    static String renderInventory(ClientNumenState.Snapshot snapshot) {
        java.util.Map<String, Integer> totals = new java.util.TreeMap<>();
        for (net.minecraft.world.item.ItemStack stack : snapshot.items()) {
            if (!stack.isEmpty()) {
                totals.merge(itemId(stack), stack.getCount(), Integer::sum);
            }
        }
        return formatInventory(totals,
                snapshot.mainHand().isEmpty() ? null : itemId(snapshot.mainHand()),
                snapshot.offhand().isEmpty() ? null : itemId(snapshot.offhand()));
    }

    /** Null hand ids mean empty slots; all inputs come from the supplied snapshot, never a cache. */
    private static String formatInventory(java.util.Map<String, Integer> totals,
                                          String mainHand, String offhand) {
        // 2026-10-02 保留的唯一一处改动：按数量降序。原来的字母序（TreeMap 顺序）让
        // 「只有 1 个的低价值堆」和「113 个圆石」混在一起看不出轻重；降序不丢任何信息，
        // 只是把「手上主要有什么」放到一眼能看见的位置。**不截断**。
        List<java.util.Map.Entry<String, Integer>> ranked = new java.util.ArrayList<>(totals.entrySet());
        ranked.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

        StringBuilder items = new StringBuilder();
        for (java.util.Map.Entry<String, Integer> e : ranked) {
            if (items.length() > 0) items.append(", ");
            items.append(e.getKey()).append(" x").append(e.getValue());
        }
        // 手上那份不带数量,是刻意的:它本来就是 carrying 里的一堆,写上数量她会当成另一堆
        // 加起来(实测她把主手 64 个熔炉和清单里同一批数成了 128)。总数只有一处,手只指
        // 向它,结构上就没什么可重复计的。
        return "<inventory>Your backpack right now, totalled across all 36 backpack slots — trust it, "
                + "do not spend a call on get_self_status to rediscover it. "
                + "Call inspect_gui only when exact slots matter. A newer tool result wins over this."
                + "\ncarrying=" + (items.length() == 0 ? "nothing" : items)
                + "\nholding (already counted above)=main " + describe(mainHand)
                + ", off " + describe(offhand)
                + "</inventory>";
    }

    /** 手上拿的<b>是什么</b>,不含数量——数量归 {@code carrying} 一处管。 */
    private static String describe(String itemId) {
        return itemId == null ? "(empty)" : xml(itemId);
    }

    private static String itemId(net.minecraft.world.item.ItemStack stack) {
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(stack.getItem()).toString();
        String brew = brewLabel(stack);
        return brew.isEmpty() ? id : id + "[" + brew + "]";
    }

    /**
     * 瓶子里装的是什么。<b>治疗、剧毒、夜视的 item id 全都是 {@code minecraft:potion}</b> ——
     * 内容在 {@code POTION_CONTENTS} 组件里,只印 id 的话她背包里三瓶完全不同的东西长得
     * 一模一样,选不出该喝哪瓶。药箭同理。
     *
     * <p>印的是原版药水的<b>注册名</b>({@code strong_healing}、{@code long_poison}),不是
     * "安全/危险"那种结论 —— 该不该喝是她的判断,身体只负责说清楚这是什么。喷溅型和滞留型
     * 本来就是另外的 item id,照实印就分开了,不用另写判据。
     */
    private static String brewLabel(net.minecraft.world.item.ItemStack stack) {
        var contents = stack.get(net.minecraft.core.component.DataComponents.POTION_CONTENTS);
        if (contents == null) {
            return "";
        }
        StringBuilder label = new StringBuilder();
        contents.potion().ifPresent(held -> label.append(held.unwrapKey()
                .map(key -> key.location().getPath()).orElse("unknown")));
        // 酿造出来的、模组的药水没有预设名,效果只在自定义列表里 —— 两处都读,不用维护白名单。
        for (var effect : contents.customEffects()) {
            if (label.length() > 0) {
                label.append('+');
            }
            label.append(effect.getEffect().unwrapKey()
                    .map(key -> key.location().getPath()).orElse("unknown"));
        }
        return label.toString();
    }

    private static String xml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
