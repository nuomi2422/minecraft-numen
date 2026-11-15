package com.dwinovo.numen.client.screen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bounds regression for the pixel calculations actually called by NumenScreen.
 * Uses explicit glyph widths, not Minecraft bootstrap or a second layout implementation.
 * The equivalent-extraction checkpoint intentionally fails the overflow cases;
 * a missing translation must not let tabs, usage, or the title cross the left rail.
 */
class HeaderLayoutTest {
    private static final int LEFT = 46 + 8; // panel after the unchanged rail, plus PAD
    private static final int HEADER_WIDTH = 380 - 8 * 2;
    private static final int GAP = 4;
    private static final String NAME = "A very long companion name that must stay inside its own remaining header space";
    private static final String[] KEYS = {
            "numen.tab.chat", "numen.tab.status", "numen.tab.settings"};

    @Test
    void untranslatedTabsAndLargeUsageCannotCrossRailOrTitle() {
        int[] tabX = new int[3], tabW = new int[3];
        HeaderLayout.layoutTabs(LEFT, HEADER_WIDTH, widths(KEYS), tabX, tabW);
        int[] parts = widths("↑1234567", "↓7654321", "R1234567", "W7654321", "CH99.9%", "99%/1234567");
        HeaderLayout.Usage usage = HeaderLayout.usage(LEFT, tabX[0] - LEFT, parts, GAP);
        int limit = usage.x() - 8;
        String title = HeaderLayout.clip(NAME, HeaderLayout.nameWidth(limit - LEFT), HeaderLayoutTest::width);
        assertAll(
                () -> assertTrue(tabX[0] >= LEFT, "raw keys must stay in the panel"),
                () -> assertTrue(usage.x() >= LEFT, "usage must never escape into the rail"),
                () -> assertTrue(usage.count() == 0 || usage.x() + prefixWidth(parts, usage.count()) <= tabX[0] - 10,
                        "usage must not be shifted right into tabs"),
                () -> assertTrue(title.isEmpty() || LEFT + width(title) <= limit,
                        "the title must not paint over usage or tabs"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 23, 24, 50, 364})
    void tabBoundsDoNotDependOnTranslatedLabelLength(int available) {
        int[] tabX = new int[3], tabW = new int[3];
        HeaderLayout.layoutTabs(LEFT, available, widths(KEYS), tabX, tabW);
        for (int i = 0; i < tabX.length; i++) {
            assertTrue(tabW[i] >= 0, "tab width must be nonnegative");
            assertTrue(tabX[i] >= LEFT, "tab " + i + " crosses the left rail at " + available + "px");
            assertTrue(tabX[i] + tabW[i] <= LEFT + available, "tab exceeds the header's right edge");
            if (i > 0) assertTrue(tabX[i - 1] + tabW[i - 1] <= tabX[i], "tabs overlap");
        }
    }

    @ParameterizedTest
    @CsvSource({"0, 0", "1, 0", "23, 0", "24, 0", "50, 2", "364, 6"})
    void usageShowsOnlyTheWholePrefixThatFitsBeforeTabs(int available, int expectedCount) {
        int[] parts = {18, 18, 24, 24, 30, 40};
        HeaderLayout.Usage usage = HeaderLayout.usage(LEFT, available, parts, GAP);
        assertAll(
                () -> assertEquals(expectedCount, usage.count(), "whole prefix, preserving segment/color order"),
                () -> assertTrue(usage.x() >= LEFT, "usage must not start in the rail"),
                () -> assertTrue(usage.x() <= LEFT + available, "empty usage must not consume tab space"),
                () -> assertTrue(usage.count() == 0 || usage.x() + prefixWidth(parts, usage.count()) <= LEFT + available - 10,
                        "do not merely shift the full usage list right"));
    }

    @Test
    void oversizedFirstUsagePartDoesNotSkipAheadToLaterParts() {
        HeaderLayout.Usage usage = HeaderLayout.usage(LEFT, HEADER_WIDTH, new int[]{400, 12}, GAP);
        assertEquals(0, usage.count(), "a visible prefix cannot discard its first segment and show a later one");
        assertTrue(usage.x() >= LEFT);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 23, 24, 50, 364})
    void titleUsesOnlyItsActualRemainingPixels(int remaining) {
        int allocated = HeaderLayout.nameWidth(remaining);
        String title = HeaderLayout.clip(NAME, allocated, HeaderLayoutTest::width);
        assertAll(
                () -> assertEquals(remaining, allocated, "no mandatory 24px when the remaining width is smaller"),
                () -> assertTrue(width(title) <= remaining, "visible title must not overlap usage"),
                () -> { if (remaining == 0) assertEquals("", title, "no title when no pixels remain"); });
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 23, 24, 50, 364})
    void clippingNeverDrawsAnEllipsisWiderThanTheAvailableSpace(int available) {
        String clipped = HeaderLayout.clip(NAME, available, HeaderLayoutTest::width);
        assertTrue(width(clipped) <= available, "even the ellipsis must fit");
    }

    @Test
    void negativeRemainingSpaceCannotForceAVisibleTitle() {
        assertEquals(0, HeaderLayout.nameWidth(-100));
        assertEquals("", HeaderLayout.clip(NAME, -100, HeaderLayoutTest::width));
    }

    @Test
    void normalLocalizedTabsAndNoUsageKeepExistingHeaderPositions() {
        int[] tabX = new int[3], tabW = new int[3];
        HeaderLayout.layoutTabs(LEFT, HEADER_WIDTH, new int[]{18, 18, 24}, tabX, tabW);
        assertArrayEquals(new int[]{320, 352, 384}, tabX);
        assertArrayEquals(new int[]{28, 28, 34}, tabW);
        HeaderLayout.Usage usage = HeaderLayout.usage(LEFT, tabX[0] - LEFT, new int[0], GAP);
        assertEquals(0, usage.count());
        assertEquals(tabX[0], usage.x(), "no usage keeps the original title limit");
        String title = HeaderLayout.clip("Alice", HeaderLayout.nameWidth(usage.x() - 8 - LEFT), HeaderLayoutTest::width);
        assertEquals("Alice", title);
        assertTrue(LEFT + width(title) <= usage.x() - 8);
    }

    @Test
    void normalUsageKeepsEverySegmentAndTheExistingRightGap() {
        int[] parts = {18, 24, 30};
        int[] original = parts.clone();
        HeaderLayout.Usage usage = HeaderLayout.usage(LEFT, 160, parts, GAP);
        assertEquals(3, usage.count());
        assertEquals(124, usage.x());
        assertEquals(LEFT + 160 - 10, usage.x() + prefixWidth(parts, usage.count()));
        assertArrayEquals(original, parts, "never reorder segments and their parallel colors");
    }

    private static int[] widths(String... labels) {
        return Arrays.stream(labels).mapToInt(HeaderLayoutTest::width).toArray();
    }

    private static int width(String text) {
        return text.codePoints().map(c -> c == ' ' ? 4 : 6).sum();
    }

    private static int prefixWidth(int[] parts, int count) {
        int total = 0;
        for (int i = 0; i < count; i++) total += parts[i] + (i == 0 ? 0 : GAP);
        return total;
    }

    @Test
    void renderedTabTextFitsItsAllocatedWidthIncludingPadding() {
        String[] labels = {KEYS[0] + ".untranslated.long.key", KEYS[1] + ".untranslated.long.key",
                KEYS[2] + ".untranslated.long.key"};
        for (int available : new int[]{0, 1, 23, 24, 50, 364}) {
            int[] tabX = new int[3], tabW = new int[3];
            HeaderLayout.layoutTabs(LEFT, available, widths(labels), tabX, tabW);
            for (int i = 0; i < labels.length; i++) {
                // This is the exact text producer consumed by renderTabs, not just tab geometry.
                String text = HeaderLayout.tabLabel(labels[i], tabW[i], HeaderLayoutTest::width);
                assertTrue(width(text) <= Math.max(0, tabW[i] - 10), "actual label exceeds its padded tab");
                if (!text.isEmpty()) {
                    assertTrue(tabX[i] + 5 >= LEFT);
                    assertTrue(tabX[i] + 5 + width(text) <= tabX[i] + tabW[i] - 5);
                    assertTrue(tabX[i] + 5 + width(text) <= LEFT + available);
                }
            }
        }
        assertEquals("Chat", HeaderLayout.tabLabel("Chat", 34, HeaderLayoutTest::width));
        assertEquals("", HeaderLayout.tabLabel("Chat", 0, HeaderLayoutTest::width));
        assertEquals("", HeaderLayout.tabLabel("Chat", 15, HeaderLayoutTest::width));
        assertEquals("…", HeaderLayout.tabLabel("Chat", 16, HeaderLayoutTest::width));
        assertEquals("Persona", HeaderLayout.clip("Persona", 50, HeaderLayoutTest::width));
        assertEquals("Re…", HeaderLayout.clip("Respawn 123s", 23, HeaderLayoutTest::width));
    }
}
