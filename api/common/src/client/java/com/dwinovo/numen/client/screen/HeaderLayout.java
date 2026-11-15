package com.dwinovo.numen.client.screen;

import java.util.function.ToIntFunction;

/**
 * Pure pixel calculations used by NumenScreen's real header.
 * Keeps tabs, whole usage segments, and the remaining title inside their own bounds
 * even when translated labels are unavailable or longer than the panel.
 * Font widths are supplied by the caller instead of mocking or loading Minecraft;
 * shifting the full usage list right would only trade rail overflow for tab overlap.
 */
final class HeaderLayout {
    private HeaderLayout() {}

    static void layoutTabs(int left, int availableWidth, int[] labelWidths, int[] tabX, int[] tabW) {
        int x = left + Math.max(0, availableWidth);
        int maxTabW = Math.max(0, (availableWidth - 4 * (labelWidths.length - 1)) / labelWidths.length);
        for (int i = labelWidths.length - 1; i >= 0; i--) {
            int w = Math.min(labelWidths[i] + 10, maxTabW);
            x -= w;
            tabX[i] = x;
            tabW[i] = w;
            x = Math.max(left, x - 4);
        }
    }

    static String tabLabel(String label, int tabWidth, ToIntFunction<String> width) {
        return clip(label, tabWidth - 10, width);
    }

    record Usage(int x, int count) {}

    static Usage usage(int left, int availableWidth, int[] partWidths, int gap) {
        int tabsLeft = left + Math.max(0, availableWidth);
        int total = 0, count = 0;
        for (int width : partWidths) {
            int next = total + (count == 0 ? 0 : gap) + width;
            if (next > availableWidth - 10) break;
            total = next;
            count++;
        }
        return new Usage(count == 0 ? tabsLeft : tabsLeft - 10 - total, count);
    }

    static int nameWidth(int remainingWidth) {
        return Math.max(0, remainingWidth);
    }

    static String clip(String s, int maxW, ToIntFunction<String> width) {
        if (maxW <= 0) return "";
        if (width.applyAsInt(s) <= maxW) return s;
        if (width.applyAsInt("…") > maxW) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (width.applyAsInt(b.toString() + s.charAt(i) + "…") > maxW) break;
            b.append(s.charAt(i));
        }
        return b + "…";
    }
}
