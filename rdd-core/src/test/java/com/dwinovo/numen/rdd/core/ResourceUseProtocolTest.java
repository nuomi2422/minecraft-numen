package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.*;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ResourceUseProtocolTest {
    private static final Map<String,Object> FURNACE = Map.of("type", "block_nearby", "block", "minecraft:furnace", "radius", 8);
    private static ResourceUse use(String key, int quantity, ResourceUse.Kind kind) { return new ResourceUse("minecraft:" + key, quantity, kind); }

    @Test void declarationSurvivesBothFactoriesConditionUpdateAndActualTaskSaveReload() {
        var uses = List.of(use("cobblestone",8,ResourceUse.Kind.CONSUME),use("cobblestone",7,ResourceUse.Kind.RETAIN));
        var spec = new SubtaskSpec("炉子用途声明，不是已消耗", FURNACE, null, NodeType.MAIN, null, uses);
        UUID id = new UUID(0,73);
        var goal = RddChainFactory.fromSpec(id,"prepare furnace",List.of(spec));
        assertEquals(uses,goal.primaryGoals().get(0).subtasks().get(0).resourceUses());
        var steps = RddChainFactory.subtasksFromSpecs("p",2,List.of(spec));
        assertEquals(uses,steps.get(0).withCondition(FURNACE).resourceUses());
        var chain = new TaskChain(new Goal("g","prepare",List.of(new PrimaryGoal("p","prepare",steps))));
        String before = chain.toJson();
        var restored = TaskChain.fromJson(before);
        assertEquals(uses,restored.currentSubtask().resourceUses());
        assertEquals(SubtaskStatus.PENDING,restored.currentSubtaskStatus(),"a declaration is not completion");
        var primaries = (List<?>)restored.snapshot().get("primaries");
        var tasks = (List<?>)((Map<?,?>)primaries.get(0)).get("subtasks");
        assertEquals(uses,((Map<?,?>)tasks.get(0)).get("resourceUses"));
        assertEquals(before,chain.toJson(),"snapshot and serialization must not mutate facts");
    }

    @Test void legacyConstructorsAndMissingJsonNeverInferConsumption() {
        var spec = new SubtaskSpec("legacy",FURNACE,null,NodeType.MAIN,null);
        assertTrue(spec.resourceUses().isEmpty());
        var chain = new TaskChain(RddChainFactory.fromSpec(new UUID(0,7),"legacy",List.of(spec)));
        var json=JsonParser.parseString(chain.toJson()).getAsJsonObject();
        var task=json.getAsJsonObject("goal").getAsJsonArray("primaryGoals").get(0).getAsJsonObject()
                .getAsJsonArray("subtasks").get(0).getAsJsonObject();
        task.remove("resourceUses");
        assertTrue(TaskChain.fromJson(json.toString()).currentSubtask().resourceUses().isEmpty());
        assertTrue(Subtask.hardCoded("s","legacy",FURNACE).resourceUses().isEmpty());
        var malformed=JsonParser.parseString(chain.toJson()).getAsJsonObject();
        var badTask=malformed.getAsJsonObject("goal").getAsJsonArray("primaryGoals").get(0).getAsJsonObject()
                .getAsJsonArray("subtasks").get(0).getAsJsonObject();
        var badUses=new com.google.gson.JsonArray();
        var badUse=new com.google.gson.JsonObject();badUse.addProperty("assetKey","minecraft:potato");
        badUse.addProperty("quantity",1.5);badUse.addProperty("kind","CONSUME");badUses.add(badUse);
        badTask.add("resourceUses",badUses);
        assertThrows(IllegalArgumentException.class,()->TaskChain.fromJson(malformed.toString()),
                "explicit corrupt quantities cannot be coerced to integers or treated as legacy");
    }

    @Test void retainedQuantityIsIndependentOfConsumptionAndUnknownCounts() {
        var uses=List.of(use("cobblestone",8,ResourceUse.Kind.CONSUME),use("cobblestone",7,ResourceUse.Kind.RETAIN));
        assertFalse(ResourceUse.retainedSatisfied(uses,null));
        assertFalse(ResourceUse.retainedSatisfied(uses,Map.of("minecraft:cobblestone",6)));
        assertTrue(ResourceUse.retainedSatisfied(uses,Map.of("minecraft:cobblestone",7)));
        assertTrue(ResourceUse.retainedSatisfied(List.of(use("potato",1,ResourceUse.Kind.CONSUME)),null),
                "no RETAIN leaves the original output condition in charge; it does not prove eating");
    }

    @Test void placedBedBaseDoesNotAlsoRequireTheSameBedInBackpack() {
        Map<String, Object> base = Map.of("type", "base");
        var bed = use("yellow_bed", 1, ResourceUse.Kind.RETAIN);
        var torch = use("torch", 4, ResourceUse.Kind.RETAIN);
        var uses = List.of(bed, torch);
        assertEquals(List.of(torch), ResourceUse.completionObligations(uses, base));
        assertTrue(ResourceUse.retainedSatisfied(
                ResourceUse.completionObligations(List.of(bed), base), Map.of()));
        assertEquals(uses, ResourceUse.completionObligations(uses, FURNACE),
                "non-base steps must keep every RETAIN obligation");
    }

    @Test void boundsDuplicatesAndExactTransferAreValidatedNotSilentlyDropped() {
        assertThrows(IllegalArgumentException.class,()->use("cobblestone",0,ResourceUse.Kind.CONSUME));
        assertThrows(IllegalArgumentException.class,()->new ResourceUse("#minecraft:logs",1,ResourceUse.Kind.CONSUME));
        var consume=use("cobblestone",8,ResourceUse.Kind.CONSUME);
        assertThrows(IllegalArgumentException.class,()->ResourceUse.validate(List.of(consume,consume),FURNACE));
        assertThrows(IllegalArgumentException.class,()->ResourceUse.validate(Collections.nCopies(9,consume),FURNACE));
        var transfer=use("cooked_beef",8,ResourceUse.Kind.TRANSFER);
        var destination=Map.<String,Object>of("type","container_at","item","minecraft:cooked_beef","minimum",8,
                "dimension","minecraft:overworld","x",1,"y",64,"z",2);
        assertEquals(List.of(transfer),ResourceUse.validate(List.of(transfer),destination));
        assertThrows(IllegalArgumentException.class,()->ResourceUse.validate(List.of(transfer),Map.of("type","stocked","minimum",10)));
        var wrong=new HashMap<>(destination);wrong.put("minimum",7);
        assertThrows(IllegalArgumentException.class,()->ResourceUse.validate(List.of(transfer),wrong));
        wrong.put("minimum",8);wrong.remove("dimension");
        assertThrows(IllegalArgumentException.class,()->ResourceUse.validate(List.of(transfer),wrong));
    }
}
