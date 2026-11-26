package com.dwinovo.numen.plugins.learner.skills;

import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.plugins.learner.contract.ArtifactDraft;
import com.dwinovo.numen.plugins.learner.contract.ArtifactKind;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** Offline registry/catalog/loader integration; reads do not execute or prove world effects. */
class SceneFoodPackageConsumerTest {
    @TempDir Path dir;
    private final UUID companion = UUID.randomUUID();
    private static final String FOOD = "skill:food-supply-component@v1";
    private static final String PACK = "skill:trade-supply-package@v1";
    private static final String BODY = "检查当前食物储备，缺测时不假定为零。";

    private void register(String id, String name, String body, List<String> conditions) throws Exception {
        String bodyRef = SkillBodyStore.write(dir, id, 1, "offline-" + id, body);
        var draft = new ArtifactDraft(ArtifactKind.EXPERIENCE, name, "用途：" + name, body,
                List.of("供食", "交易"), List.of(), conditions, "UNKNOWN", List.of(),
                List.of("offline-cross-scene-fixture"), "EXECUTOR");
        assertEquals(SkillRegistry.Outcome.REGISTERED,
                SkillRegistry.open(dir.resolve("skills.jsonl")).register(draft, id, bodyRef, true).outcome());
    }
    private void food() throws Exception {
        register("food-supply-component", "供食组件", BODY, List.of("scene:food:component"));
    }
    private void pack() throws Exception {
        register("trade-supply-package", "交易补给包", "先核供食，再处理交易。",
                List.of("scene:trading:package", "requires:" + FOOD));
    }
    private NumenPlugins.KnowledgeFragment catalog(int budget) {
        var contributor = new SkillsKnowledgeContributor(dir, "EXECUTOR", budget);
        return NumenPlugins.aggregateExecutorKnowledgeFragment(List.of(contributor::contributeCatalog),
                companion, "村民交易与供食");
    }
    private JsonObject load(String ref, String packageRef) {
        var resolver = new LearnedSkillBodyResolverImpl(dir);
        var tool = new com.dwinovo.numen.core.tools.agent.LoadSkillTool(new com.dwinovo.numen.core.tools.AgentOps(() -> resolver));
        var args = new JsonObject();
        if (packageRef == null) args.addProperty("ref", ref);
        else { args.addProperty("package_ref", packageRef); args.addProperty("component_ref", ref); }
        var reply = new AtomicReference<String>();
        tool.invoke(new com.dwinovo.numen.agent.tool.ToolCall("food-read", "load_skill", args.toString(), () -> companion, reply::set));
        return JsonParser.parseString(reply.get()).getAsJsonObject();
    }
    @Test void sameFoodComponentReadableAloneAndThroughTradingPackage() throws Exception {
        food(); pack(); var c = catalog(0);
        assertTrue(c.text().contains(FOOD)); assertTrue(c.text().contains(PACK));
        assertFalse(c.text().contains(BODY)); assertTrue(c.refs().isEmpty());
        var direct = load(FOOD, null); var viaPack = load(FOOD, PACK);
        assertTrue(direct.get("success").getAsBoolean()); assertTrue(viaPack.get("success").getAsBoolean());
        assertEquals(BODY, direct.get("body").getAsString()); assertEquals(BODY, viaPack.get("body").getAsString());
    }
    @Test void missingOrRetiredRequiredComponentCannotMakePackageAvailable() throws Exception {
        pack(); var missing = catalog(0);
        assertFalse(missing.text().contains(PACK));
        assertFalse(load(FOOD, PACK).get("success").getAsBoolean());
        food(); assertTrue(catalog(0).text().contains(PACK));
        SkillRegistry.open(dir.resolve("skills.jsonl")).retire("food-supply-component", "offline negative control");
        assertFalse(catalog(0).text().contains(PACK));
        assertFalse(load(FOOD, PACK).get("success").getAsBoolean());
    }
    @Test void budgetRejectionDoesNotClaimBodyDelivery() throws Exception {
        food(); pack(); var c = catalog(1);
        assertTrue(c.refs().isEmpty()); assertFalse(c.text().contains(BODY));
        assertFalse(c.text().contains(FOOD)); assertFalse(c.text().contains(PACK));
    }
}
