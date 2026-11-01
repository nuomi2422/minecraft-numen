package com.dwinovo.numen.rdd.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 备用箱缺口 -> **下一步到底该做什么**（2026-11-01，167 §B5 的补口）。
 *
 * <p>为什么需要：`blocking=reserve_short:torch 0/8` 只说了「箱子里缺 8 个火把」，
 * 但背包里可能正躺着 30 个火把——这时正确的下一步是「放进箱子」，不是「去做火把」。
 * 实机里它长期背着一包东西、基地箱子却是空的，这条歧义会让人（和模型）白跑一趟。
 *
 * <p>只产出建议措辞与数量，不动作、不判定（动作归执行 AI）。
 */
public final class ReserveAction {

    private ReserveAction() {}

    /** 一项该做的补齐动作。 */
    public record Step(String itemId, int need, int inInventory, boolean fromInventory) {}

    /**
     * @param shortfall ReserveKit.shortfall() 的形状：形如 {@code cooked_beef 0/8}
     * @param inventory 当前背包计数（可为 null）
     */
    public static List<Step> plan(List<String> shortfall, Map<String, Integer> inventory) {
        if (shortfall == null || shortfall.isEmpty()) return List.of();
        List<Step> out = new ArrayList<>();
        for (String s : shortfall) {
            if (s == null || s.isBlank()) continue;
            // 形如 "cooked_beef 0/8"
            int sp = s.lastIndexOf(' ');
            if (sp <= 0) continue;
            String name = s.substring(0, sp).trim();
            String frac = s.substring(sp + 1).trim();
            String[] parts = frac.split("/");
            if (parts.length != 2) continue;
            int have;
            int need;
            try {
                have = Integer.parseInt(parts[0].trim());
                need = Integer.parseInt(parts[1].trim());
            } catch (NumberFormatException notNumbers) {
                continue;
            }
            int gap = Math.max(0, need - have);
            if (gap == 0) continue;
            String id = Resolve.id(name, inventory);
            int inv = Resolve.count(id, inventory);
            out.add(new Step(id, gap, inv, inv >= gap));
        }
        return List.copyOf(out);
    }

    /** 一行渲染：能从背包补的写「放」，不能的写「取」。 */
    public static String describe(List<Step> steps) {
        if (steps == null || steps.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Step s : steps) {
            if (sb.length() > 0) sb.append("; ");
            String shortName = s.itemId().startsWith("minecraft:")
                    ? s.itemId().substring("minecraft:".length()) : s.itemId();
            if (s.fromInventory()) {
                sb.append("put ").append(shortName).append(' ').append(s.need())
                        .append(" into the base chest (背包有 ").append(s.inInventory()).append(')');
            } else {
                sb.append("gather ").append(shortName).append(' ').append(s.need())
                        .append(" (背包 ").append(s.inInventory()).append(')');
            }
        }
        return sb.toString();
    }

    /** 名字<->id 的极小工具：shortfall 用短名，背包用完整 id。 */
    private static final class Resolve {
        static String id(String shortName, Map<String, Integer> inventory) {
            String full = shortName.contains(":") ? shortName : "minecraft:" + shortName;
            if (inventory != null && !inventory.containsKey(full) && inventory.containsKey(shortName)) {
                return shortName;
            }
            return full;
        }

        static int count(String id, Map<String, Integer> inventory) {
            if (inventory == null) return 0;
            Integer v = inventory.get(id);
            if (v != null) return v;
            Integer shortName = inventory.get(id.replace("minecraft:", ""));
            return shortName == null ? 0 : shortName;
        }
    }
}