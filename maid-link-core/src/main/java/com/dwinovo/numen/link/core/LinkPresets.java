package com.dwinovo.numen.link.core;

import java.util.*;
import static com.dwinovo.numen.link.core.LinkTypes.*;
import static com.dwinovo.numen.link.core.LinkConfiguration.*;

/**
 * 052/P1, 2026-11-11: templates over trusted native selections, not provider declarations.
 * Caller supplies exact ids/versions/profiles. No built-in claim about MSC cross-host support.
 * Spokesperson disables Numen CHAT/VOICE only; execution and memories stay selected.
 * Runtime use still goes through LinkPolicy drain/apply; generating data never stops bodies.
 * Current consumers are offline fixtures, not a registered game preset menu.
 */
public final class LinkPresets {
    public enum Preset { NATIVE_NUMEN, NATIVE_MAID, COEXIST, SPOKESPERSON }
    private LinkPresets() {}

    public static Document create(Preset preset, long revision, Binding binding,
                                  Map<Slot, Selection> nativeSelections,
                                  CapabilityCatalog catalog, Set<String> available) {
        Objects.requireNonNull(preset);
        Set<Side> active = switch (preset) {
            case NATIVE_NUMEN -> Set.of(Side.NUMEN);
            case NATIVE_MAID -> Set.of(Side.MAID);
            case COEXIST, SPOKESPERSON -> Set.of(Side.NUMEN, Side.MAID);
        };
        if (!binding.bodies().keySet().containsAll(active))
            throw new IllegalArgumentException("preset-body-missing");
        var boundNative = new HashMap<Slot, Selection>();
        nativeSelections.forEach((slot, selection) -> {
            if (active.contains(slot.host())) boundNative.put(slot, selection);
        });
        Check nativeCheck = validate(new Document(SCHEMA, revision, boundNative), binding, catalog, available);
        if (!nativeCheck.ok()) throw new IllegalArgumentException(nativeCheck.reason());
        var result = new HashMap<Slot, Selection>();
        nativeSelections.forEach((slot, selection) -> {
            if (!binding.bodies().containsKey(slot.host())) return;
            Choice c = selection.choice();
            if (c.mode() != Mode.NATIVE) throw new IllegalArgumentException("native-template-required");
            boolean muted = !active.contains(slot.host())
                    || (preset == Preset.SPOKESPERSON && slot.host() == Side.NUMEN
                        && (slot.capability() == Capability.CHAT || slot.capability() == Capability.VOICE));
            result.put(slot, muted ? new Selection(
                    new Choice(false, c.provider(), c.mode(), c.profile()), selection.providerVersion()) : selection);
        });
        for (Side side : active) {
            Selection chat = nativeSelections.get(new Slot(side, Capability.CHAT));
            if (chat == null || !chat.choice().enabled())
                throw new IllegalArgumentException("native-chat-selection-required");
        }
        var document = new Document(SCHEMA, revision, result);
        Check check = validate(document, binding, catalog, available);
        if (!check.ok()) throw new IllegalArgumentException(check.reason());
        return document;
    }
}
