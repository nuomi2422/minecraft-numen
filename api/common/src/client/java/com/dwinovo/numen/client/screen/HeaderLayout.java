package com.dwinovo.numen.client.screen;

import java.util.function.ToIntFunction;

/**
 * Pure pixel calculations used by NumenScreen's real header.
 * Extracted unchanged on 2026-11-16 so header bounds can be tested without Minecraft.
 * This RED checkpoint deliberately keeps unrestricted tabs/usage, the 24px name floor,
 * and the unconditional ellipsis; it is not the bounds fix.
 * Font widths are supplied by the caller instead of mocking or loading the game.
 */
final class HeaderLayout {
    private HeaderLayout() {}

    static void layoutTabs(int left, int availableWidth, int[] labelWidths, int[] tabX, int[] tabW) {
        int x = left + availableWidth;
        for (int i = labelWidths.length - 1; i >= 0; i--) {
            int w = labelWidths[i] + 10;
            x -= w;
            tabX[i] = x;
            tabW[i] = w;
            x -= 4;
        }
    }

    record Usage(int x, int count) {}

    static Usage usage(int left, int availableWidth, int[] partWidths, int gap) {
        int tabsLeft = left + availableWidth;
        if (partWidths.length == 0) return new Usage(tabsLeft, 0);
        int total = -gap;
        for (int width : partWidths) {
            total += width + gap;
        }
        return new Usage(tabsLeft - 10 - total, partWidths.length);
    }

    static int nameWidth(int remainingWidth) {
        return Math.max(24, remainingWidth);
    }

    static String clip(String s, int maxW, ToIntFunction<String> width) {
        if (width.applyAsInt(s) <= maxW) return s;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (width.applyAsInt(b.toString() + s.charAt(i) + "…") > maxW) break;
            b.append(s.charAt(i));
        }
        return b + "…";
    }
}
