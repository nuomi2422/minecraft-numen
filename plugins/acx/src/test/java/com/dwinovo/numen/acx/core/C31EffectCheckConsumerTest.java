package com.dwinovo.numen.acx.core;

import com.dwinovo.numen.acx.api.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Consumer of the real AcxRunner + AcxLoader against the C31 effect-check candidate JSON
 * (mirrors .../g2/c31-effect-check-candidate.json). Scripted fake tools only; no product
 * change, no MC bootstrap. Proves the guard sees the resolved $var.$after_block and
 * $input.expected_block:
 *  - positive: target air -> crafting_table and table count 1 -> 0 -> SUCCESS, craft target runs;
 *  - negative: target stays air -> FAIL, craft target never runs;
 *  - old block already there -> FAIL before interact;
 *  - wrong expected_block input -> FAIL (input resolution is real, not silently equal).
 */
class C31EffectCheckConsumerTest {

    static final String CANDIDATE = """
        {
          "name": "craft_table_then_3x3_effect_checked",
          "version": "1",
          "description": "consumer fixture mirror of g2 candidate",
          "tags": ["tmp","c31"],
          "steps": [
            {"id":"make_table","block":"craft","params":{"item_id":"$input.expected_block","count":1.0}},
            {"id":"inspect_before","block":"inspect_block","params":{"x":"$input.target.x","y":"$input.target.y","z":"$input.target.z"}},
            {"id":"save_before","block":"set","params":{"before_block":"$inspect_before.block"}},
            {"id":"require_not_old","block":"guard","params":{"condition":{"field":"$var.before_block","op":"!=","value":"$input.expected_block"},"on_fail":"fail"}},
            {"id":"inventory_before","block":"rdd_get_inventory","params":{}},
            {"id":"save_counts_before","block":"set","params":{"tables_before":{"$from":"$inventory_before.items.minecraft:crafting_table","$default":0.0}}},
            {"id":"place_table","block":"interact_at","params":{"button":"right","x":"$input.support.x","y":"$input.support.y","z":"$input.support.z","item_id":"$input.expected_block"}},
            {"id":"inspect_after","block":"inspect_block","params":{"x":"$input.target.x","y":"$input.target.y","z":"$input.target.z"}},
            {"id":"save_after","block":"set","params":{"after_block":"$inspect_after.block"}},
            {"id":"inventory_after","block":"rdd_get_inventory","params":{}},
            {"id":"save_counts_after","block":"set","params":{"tables_after":{"$from":"$inventory_after.items.minecraft:crafting_table","$default":0.0}}},
            {"id":"verify_placed","block":"guard","params":{"condition":{"field":"$var.after_block","op":"==","value":"$input.expected_block"},"on_fail":"fail"}},
            {"id":"craft_target","block":"craft","params":{"item_id":"$input.target_item","count":1.0}}
          ],
          "limits": {"max_steps": 20, "max_timeout_ms": 120000}
        }
        """;

    static AcxToolSchema schema(String... fields) {
        AcxToolSchema.Builder b = AcxToolSchema.builder();
        for (String f : fields) {
            b.param(f, AcxToolSchema.ParamType.ANY);
        }
        return b.build();
    }

    static final class Scripted implements AcxTool {
        final String name;
        final AcxToolSchema schema;
        final Deque<Map<String, Object>> replies = new ArrayDeque<>();
        final List<Map<String, Object>> calls = new ArrayList<>();

        Scripted(String name, AcxToolSchema schema, Map<String, Object>... rs) {
            this.name = name;
            this.schema = schema;
            Collections.addAll(replies, rs);
        }

        public String name() { return name; }
        public AcxToolSchema schema() { return schema; }

        public AcxStepOutcome execute(Map<String, Object> params, AcxCallContext ctx) {
            calls.add(new LinkedHashMap<>(params));
            Map<String, Object> out = replies.isEmpty() ? Map.of() : replies.poll();
            return AcxStepOutcome.success(out);
        }
    }

    static final class Registry implements AcxToolRegistry {
        final Map<String, AcxTool> tools = new LinkedHashMap<>();
        public Optional<AcxTool> find(String n) { return Optional.ofNullable(tools.get(n)); }
        public boolean contains(String n) { return tools.containsKey(n); }
        public Collection<String> names() { return tools.keySet(); }
        public void register(AcxTool t) { tools.put(t.name(), t); }
    }

    static final class Run {
        AcxRunRecord record;
        Scripted craft, inspect, interact, inventory;
        int craftTargets;
    }

    static Run run(String beforeBlock, String afterBlock, String expectedInput) {
        Registry reg = new Registry();
        Run r = new Run();
        r.craft = new Scripted("craft", schema("item_id", "count", "crafted", "carrying"),
                payload("crafted", 1, "carrying", 1),
                payload("crafted", 1, "carrying", 1));
        r.inspect = new Scripted("inspect_block", schema("x", "y", "z", "block"),
                payload("block", beforeBlock),
                payload("block", afterBlock));
        r.interact = new Scripted("interact_at", schema("button", "x", "y", "z", "item_id", "changes"),
                payload("button", "right", "changes", List.of()));
        r.inventory = new Scripted("rdd_get_inventory", schema("items"),
                payload("items", Map.of("minecraft:crafting_table", 1)),
                payload("items", Map.of()));
        reg.register(r.craft);
        reg.register(r.inspect);
        reg.register(r.interact);
        reg.register(r.inventory);
        AcxDefinition def = AcxLoader.parseJson(CANDIDATE, reg);
        AcxRunner runner = AcxRunner.builder()
                .tools(reg)
                .catalog(AcxCatalog.empty())
                .events(AcxEventSink.noop())
                .store(AcxRecordStore.noop())
                .build();
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("support", Map.of("x", 10, "y", 64, "z", 20));
        input.put("target", Map.of("x", 10, "y", 65, "z", 20));
        input.put("expected_block", expectedInput);
        input.put("target_item", "minecraft:chest");
        r.record = runner.run(def, input);
        r.craftTargets = r.craft.calls.size() - 1;
        return r;
    }

    static Map<String, Object> payload(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    @Test void positivePlacementReachesCraftTarget() {
        Run r = run("minecraft:air", "minecraft:crafting_table", "minecraft:crafting_table");
        assertEquals(AcxStatus.SUCCESS, r.record.status(), r.record.errorMessage());
        assertEquals(2, r.craft.calls.size(), "make_table + craft_target");
        assertEquals(1, r.interact.calls.size());
        assertEquals(2, r.inspect.calls.size());
        assertEquals("minecraft:crafting_table", r.record.vars().get("after_block"));
        assertEquals(1, ((Number) r.record.vars().get("tables_before")).intValue());
        assertEquals(0, ((Number) r.record.vars().get("tables_after")).intValue());
    }

    @Test void targetStillAirFailsBeforeCraftTarget() {
        Run r = run("minecraft:air", "minecraft:air", "minecraft:crafting_table");
        assertEquals(AcxStatus.FAIL, r.record.status());
        assertEquals("verify_placed", r.record.failedStep(), r.record.errorMessage());
        assertEquals(1, r.craft.calls.size(), "craft target must not run");
        assertEquals(1, r.interact.calls.size(), "the click itself still happened");
    }

    @Test void existingBlockIsNotCountedAsNewPlacement() {
        Run r = run("minecraft:crafting_table", "minecraft:crafting_table", "minecraft:crafting_table");
        assertEquals(AcxStatus.FAIL, r.record.status());
        assertEquals("require_not_old", r.record.failedStep(), r.record.errorMessage());
        assertEquals(1, r.craft.calls.size());
        assertEquals(0, r.interact.calls.size(), "no click after the old-block guard fails");
    }

    @Test void wrongExpectedInputIsNotSilentlyAccepted() {
        Run r = run("minecraft:air", "minecraft:crafting_table", "minecraft:furnace");
        assertEquals(AcxStatus.FAIL, r.record.status());
        assertEquals("verify_placed", r.record.failedStep(), r.record.errorMessage());
        assertEquals(1, r.craft.calls.size());
    }
}
