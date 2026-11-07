package com.dwinovo.numen.acx.test;

import com.google.gson.Gson;
import com.dwinovo.numen.acx.api.*;
import com.dwinovo.numen.acx.core.*;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Bounded farm feasibility candidate (2026-11-07), not a game-effect test.
 * Reuses the offline suite's Fake/FakePorts and production loader/runner/adapter.
 * Run this JUnit class alone, or main after plain-JDK compilation of api/core.
 * Why not AcxTestMain: its 217+ checks are unrelated and exit the JVM.
 * Bridge boundary: raw TaskResult JSON has data.harvested; NumenToolBridgeX
 * extracts data before PortToolAdapter. This fixture models that extraction,
 * NOT the MC-dependent bridge or actual farming. No collect inside the loop.
 */
public class AcxFarmContinueOfflineTest {
    private static final Gson GSON = new Gson();
    private static final String SCRIPT = """
        {"name":"farm_continue_offline","version":"1","steps":[
          {"id":"init","block":"set","params":{"continue":1}},
          {"id":"cycle","block":"while","params":{
            "condition":{"field":"$var.continue","op":">","value":0},
            "max_iters":8,"stagnant_limit":9},"children":[
              {"id":"harvest","block":"farm_nearby","params":{"radius":12}},
              {"id":"save","block":"set","params":{
                "continue":{"$calc":"$prev.harvested"}}},
              {"id":"valid","block":"guard","params":{
                "condition":{"field":"$var.continue","op":">=","value":0},
                "on_fail":"fail"}}
          ]},
          {"id":"zero","block":"guard","params":{
            "condition":{"field":"$var.continue","op":"==","value":0},
            "on_fail":"fail"}},
          {"id":"done","block":"business_marker","params":{}}
        ]}
        """;


    /**
     * 2026-11-07 integration: load the ACTUAL offline candidate file (same shape as SCRIPT but
     * with read/sow/done steps) and run it through production loader + runner with fakes.
     */
    @Test
    void candidateFileRunsWithVerifiedShape() throws Exception {
        Path file = Path.of(System.getProperty("acx.candidate",
                "scripts/stable/farm_maintain_loop.ac"));
        check(Files.isRegularFile(file), "candidate missing: " + file);
        Fake.resetCalls();
        FakePorts.Scripted farmPort = port(3, 1, 0);
        Fake.Registry tools = new Fake.Registry()
                .add(PortToolAdapter.builder(farmPort).build())
                .add(Fake.fixed("get_self_status", Map.of("hp", 20)))
                .add(Fake.fixed("sow_nearby", Map.of("sown", 0, "seeds_left", 1, "empty_cells", 0)));
        AcxLoader.LoadReport loaded = AcxLoader.loadAll(file.getParent(), tools, false, false);
        check(loaded.errors().isEmpty(), "loader errors: " + loaded.errors());
        AcxDefinition def = loaded.registered().get("farm_maintain_loop");
        check(def != null, "farm_maintain_loop not registered");
        AcxRunRecord r = AcxRunner.builder().tools(tools).build().run(def, Map.of());
        System.out.println("DBG def=" + def.name() + " v=" + def.version() + " blocks=" + def.allStepsDeep().stream().map(st -> st.id() + ":" + st.block()).toList());
        System.out.println("DBG calls=" + Fake.calls.order + " status=" + r.status() + " steps=" + r.completedStepIndex());
        check(r.status() == AcxStatus.SUCCESS, "status=" + r.status() + " reason=" + r.errorMessage());
        check(farmPort.calls.size() == 3, "farmPort=" + farmPort.calls.size() + " status=" + r.status() + " vars=" + r.vars() + " reason=" + r.errorMessage());
        check(((Number) r.vars().get("continue")).doubleValue() == 0, "continue=" + r.vars().get("continue"));
        System.out.println("CANDIDATE_FILE ok file=" + file + " sha256=" + hex(file) + " version=" + def.version()
                + " status=" + r.status() + " farmPort=" + farmPort.calls.size()
                + " continue=" + r.vars().get("continue"));
    }

    @Test
    void boundedRunnerFeasibility() {
        verify("positive_then_zero", port(3, 1, 0), SCRIPT, AcxStatus.SUCCESS, 3, 1);
        verify("zero_first", port(0), SCRIPT, AcxStatus.SUCCESS, 1, 1);
        verify("eight_positive", port(1,1,1,1,1,1,1,1), SCRIPT, AcxStatus.PAUSED, 8, 0);
        verify("zero_on_eighth", port(1,1,1,1,1,1,1,0), SCRIPT, AcxStatus.SUCCESS, 8, 1);
        verify("missing_harvested", FakePorts.Scripted.of("farm_nearby")
                .then(fromTaskResult(GSON.toJson(Map.of("success", true, "data", Map.of())))),
                SCRIPT, AcxStatus.FAIL, 1, 0);
        verify("tool_failed", FakePorts.Scripted.of("farm_nearby")
                .then(fromTaskResult(GSON.toJson(Map.of("success", false, "message", "farm failed", "data", Map.of("harvested", 0))))),
                SCRIPT, AcxStatus.FAIL, 1, 0);
        verify("wrong_nested_reference", port(2),
                SCRIPT.replace("$prev.harvested", "$prev.data.harvested"), AcxStatus.FAIL, 1, 0);
        verify("negative_invalid", port(-1), SCRIPT, AcxStatus.FAIL, 1, 0);
        // Characterize the unsafe alternative, rather than silently calling it safe.
        verify("unsafe_plain_set_missing", FakePorts.Scripted.of("farm_nearby")
                .thenCompleted(Map.of()), SCRIPT.replace(
                        GSON.toJson(Map.of("$calc", "$prev.harvested")), GSON.toJson("$prev.harvested")),
                AcxStatus.SUCCESS, 1, 1);
        System.out.println("ACX_FARM_OFFLINE cases=9 failures=0 errors=0; unsafe_plain_set is an observed counterexample, not approval");
    }

    private static FakePorts.Scripted port(int... harvested) {
        FakePorts.Scripted port = FakePorts.Scripted.of("farm_nearby");
        for (int n : harvested) {
            port.then(fromTaskResult(GSON.toJson(Map.of("success", true, "message", "ok", "data", Map.of("harvested", n)))));
        }
        return port;
    }

    /** Mirrors ONLY NumenToolBridgeX's synchronous TaskResult extraction. */
    @SuppressWarnings("unchecked")
    private static AcxToolPort.Result fromTaskResult(String json) {
        Map<String, Object> raw = GSON.fromJson(json, Map.class);
        if (!Boolean.TRUE.equals(raw.get("success"))) {
            return AcxToolPort.Result.failed(String.valueOf(raw.get("message")));
        }
        return AcxToolPort.Result.completed((Map<String, Object>) raw.get("data"));
    }

    private static void verify(String name, FakePorts.Scripted port, String script,
                               AcxStatus expected, int farms, int markers) {
        Fake.resetCalls();
        Fake.Registry tools = new Fake.Registry()
                .add(PortToolAdapter.builder(port).build())
                .add(Fake.fixed("business_marker", Map.of("local_zero_observed", true)));
        AcxDefinition definition = AcxLoader.parseJson(script, tools);
        AcxRunRecord result = AcxRunner.builder().tools(tools).build().run(definition, Map.of());
        check(result.status() == expected, name + " status " + result);
        check(port.calls.size() == farms && farms <= 8, name + " farms=" + port.calls.size());
        check(Fake.calls.count("business_marker") == markers, name + " marker must not run on FAIL/PAUSED");
        if (expected == AcxStatus.SUCCESS && !name.startsWith("unsafe")) {
            check(result.vars().get("continue") instanceof Number, name + " saved numeric variable");
            check(((Number) result.vars().get("continue")).doubleValue() == 0, name + " saved zero");
        }
        System.out.println(name + " status=" + result.status() + " farm=" + port.calls.size()
                + " marker=" + Fake.calls.count("business_marker") + " continue=" + result.vars().get("continue"));
    }

    private static String hex(Path file) throws Exception {
        byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
        StringBuilder b = new StringBuilder();
        for (byte x : d) b.append(String.format("%02X", x));
        return b.toString();
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        new AcxFarmContinueOfflineTest().boundedRunnerFeasibility();
    }
}
