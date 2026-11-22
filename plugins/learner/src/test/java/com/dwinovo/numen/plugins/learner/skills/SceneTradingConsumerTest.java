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

/** Offline production registry/catalog/loader seams, not proof of game action or model adoption. */
class SceneTradingConsumerTest {
    @TempDir Path dir;
    private final UUID companion = UUID.randomUUID();
    private static final String ID = "trade-component";
    private static final String REF = "skill:trade-component@v1";
    private static final String BODY = "核对村民报价与库存；售罄时不交易。";

    private void register() throws Exception {
        String bodyRef = SkillBodyStore.write(dir, ID, 1, "trading-fixture", BODY);
        var draft = new ArtifactDraft(ArtifactKind.EXPERIENCE, "村民交易检查", "交易前核报价与库存", BODY,
                List.of("交易"), List.of(), List.of("scene:trading:component"), "UNKNOWN",
                List.of(), List.of("offline-trade-fixture"), "EXECUTOR");
        assertEquals(SkillRegistry.Outcome.REGISTERED,
                SkillRegistry.open(dir.resolve("skills.jsonl")).register(draft, ID, bodyRef, true).outcome());
    }

    private NumenPlugins.KnowledgeFragment catalog() {
        var contributor = new SkillsKnowledgeContributor(dir, "EXECUTOR", 0);
        return NumenPlugins.aggregateExecutorKnowledgeFragment(
                List.of(contributor::contributeCatalog), companion, "村民交易补货");
    }

    private JsonObject load(String ref) {
        var resolver = new LearnedSkillBodyResolverImpl(dir);
        var tool = new com.dwinovo.numen.core.tools.agent.LoadSkillTool(
                new com.dwinovo.numen.core.tools.AgentOps(() -> resolver));
        var args = new JsonObject();
        args.addProperty("ref", ref);
        var reply = new AtomicReference<String>();
        tool.invoke(new com.dwinovo.numen.agent.tool.ToolCall("trade-read", "load_skill",
                args.toString(), () -> companion, reply::set));
        return JsonParser.parseString(reply.get()).getAsJsonObject();
    }

    @Test void catalogReferenceLoadsExactBodyButWrongVersionIsRejected() throws Exception {
        register();
        var fragment = catalog();
        assertTrue(fragment.text().contains(REF));
        assertTrue(fragment.text().contains("conditions=\"UNKNOWN\""));
        assertFalse(fragment.text().contains(BODY));
        assertTrue(fragment.refs().isEmpty(), "catalog is not a body delivery");
        var correct = load(REF);
        assertTrue(correct.get("success").getAsBoolean());
        assertEquals(BODY, correct.get("body").getAsString());
        assertFalse(load("skill:trade-component@v2").get("success").getAsBoolean());
    }

    @Test void retiredComponentDisappearsAndPreviouslySeenReferenceCannotLoad() throws Exception {
        register();
        assertTrue(catalog().text().contains(REF));
        SkillRegistry.open(dir.resolve("skills.jsonl")).retire(ID, "offline negative control");
        var after = catalog();
        assertTrue(after == null || !after.text().contains(REF));
        assertFalse(load(REF).get("success").getAsBoolean());
    }
}
