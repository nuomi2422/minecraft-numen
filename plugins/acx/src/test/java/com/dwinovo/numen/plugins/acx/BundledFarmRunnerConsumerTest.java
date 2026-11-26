package com.dwinovo.numen.plugins.acx;

import com.dwinovo.numen.acx.api.*;
import com.dwinovo.numen.acx.core.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real loader and runner; host ports are deterministic sentinels, not Minecraft. */
class BundledFarmRunnerConsumerTest {
    private static final class Registry implements AcxToolRegistry {
        final Map<String, AcxTool> tools = new LinkedHashMap<>();
        public Optional<AcxTool> find(String name) { return Optional.ofNullable(tools.get(name)); }
        public boolean contains(String name) { return tools.containsKey(name); }
        public Collection<String> names() { return tools.keySet(); }
        public void register(AcxTool tool) { if (tools.putIfAbsent(tool.name(), tool) != null) throw new IllegalArgumentException("duplicate"); }
    }
    private static final class Fixture {
        final Registry registry = new Registry();
        final List<String> calls = new ArrayList<>();
        final List<AcxEvent> events = new ArrayList<>();
        void port(String name, boolean fail) {
            AcxToolPort port = new AcxToolPort() {
                public String name() { return name; }
                public AcxPortSchema schema() { return AcxPortSchema.builder().allowUnknown(true).build(); }
                public Result invoke(Map<String, Object> params) {
                    calls.add(name);
                    return fail ? Result.failed("sentinel failure") : Result.completed(Map.of());
                }
            };
            registry.register(PortToolAdapter.builder(port).build());
        }
        AcxRunRecord run(String resource) throws Exception {
            try (var in = BundledFarmRunnerConsumerTest.class.getResourceAsStream("/acx-lib/stable/" + resource)) {
                assertNotNull(in, "bundled resource missing");
                var def = AcxLoader.parseJson(new String(in.readAllBytes(), StandardCharsets.UTF_8), registry);
                return AcxRunner.builder().tools(registry).events(events::add).build().run(def, Map.of());
            }
        }
        List<String> started() { return events.stream().filter(e -> e.kind() == AcxEvent.Kind.STEP_STARTED).map(AcxEvent::stepId).toList(); }
        void base() { port("get_self_status", false); port("farm_nearby", false); port("collect_items", false); }
    }
    @Test void bundledFarmRunsHarvestSowAndPickupInOrder() throws Exception {
        var f = new Fixture(); f.base(); f.port("sow_nearby", false);
        var r = f.run("farm_maintain_loop.ac");
        assertEquals(AcxStatus.SUCCESS, r.status(), r.errorMessage());
        assertEquals(List.of("read", "harvest", "sow", "pickup", "done"), f.started());
        assertEquals(List.of("get_self_status", "farm_nearby", "sow_nearby", "collect_items", "get_self_status"), f.calls);
    }
    @Test void missingSowCannotReachPickupOrSucceed() throws Exception {
        var f = new Fixture(); f.base(); var r = f.run("farm_maintain_loop.ac");
        assertEquals(AcxStatus.FAIL, r.status()); assertEquals("sow", r.failedStep());
        assertEquals(List.of("get_self_status", "farm_nearby"), f.calls);
    }
    @Test void failedSowCannotReachPickupOrSucceed() throws Exception {
        var f = new Fixture(); f.base(); f.port("sow_nearby", true); var r = f.run("farm_maintain_loop.ac");
        assertEquals(AcxStatus.FAIL, r.status()); assertEquals("sow", r.failedStep());
        assertEquals(List.of("get_self_status", "farm_nearby", "sow_nearby"), f.calls);
    }
    @Test void existingHarvestOnlyScriptDoesNotRequireSow() throws Exception {
        var f = new Fixture(); f.base(); var r = f.run("farm_harvest_loop.ac");
        assertEquals(AcxStatus.SUCCESS, r.status(), r.errorMessage());
        assertEquals(List.of("read", "tend", "pickup", "done"), f.started());
        assertEquals(List.of("get_self_status", "farm_nearby", "collect_items", "get_self_status"), f.calls);
    }
}
