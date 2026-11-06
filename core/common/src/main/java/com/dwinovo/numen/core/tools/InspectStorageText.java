package com.dwinovo.numen.core.tools;

/** inspect_block_storage 的文案（2026-11-06）：air 时明确要求作废记忆坐标。 */
public final class InspectStorageText {

    private InspectStorageText() {}

    public static String airAt(String coord) {
        return "block at " + coord + " is air — nothing to read. "
                + "This memory coordinate is dead: do not retry it; use scan_blocks / inspect_block to re-locate the container first.";
    }
}
