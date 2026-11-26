package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.*;
import com.dwinovo.numen.rdd.core.*;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RddResourceUseProtocolTest {
    private static final Gson GSON=new Gson();
    private static Map<String,Object> use(String item,int q,String kind){return Map.of("asset_key",item,"quantity",q,"kind",kind);}
    private static String plan(String description,Map<String,Object> condition,Object uses){
        var step=new LinkedHashMap<String,Object>();step.put("description",description);step.put("condition",condition);
        if(uses!=null)step.put("resource_uses",uses);
        return GSON.toJson(Map.of("subtasks",List.of(step)));
    }
    private static final Map<String,Object> FURNACE=Map.of("type","block_nearby","block","minecraft:furnace","radius",8);

    @Test void genericConsumedInputsAndIndependentRetainedInputsReachGuardAndReplan() {
        String text="任意语言的施工说明：使用材料，保留撤退垫脚；不靠炉子中文识别";
        var parsed=RddStageBSpecParser.parseDetailed(plan(text,FURNACE,List.of(
                use("minecraft:cobblestone",8,"CONSUME"),use("minecraft:cobblestone",7,"RETAIN"))));
        assertTrue(parsed.skippedRequired().isEmpty());assertEquals(1,parsed.specs().size());
        var spec=parsed.specs().get(0);
        var guarded=RddPlanGuard.filterSubtasks(RddPlanGuard.normalizeConditions(parsed.specs()),"基地施工",Map.of());
        assertEquals(List.of(spec),RddDecomposer.stageBPublished(parsed,guarded,"基地施工"));
        var chain=new TaskChain(RddChainFactory.fromSpec(new UUID(0,91),"基地施工",List.of(spec)));
        String history=RddReplanPlanRenderer.renderPreviousPlanForReplan(chain);
        assertTrue(history.contains("CONSUME"));assertTrue(history.contains("RETAIN"));
        assertTrue(history.contains("declaration="));
        assertEquals(spec.resourceUses(),TaskChain.fromJson(chain.toJson()).currentSubtask().resourceUses());
        assertEquals(SubtaskStatus.PENDING,chain.currentSubtaskStatus(),"declared uses must not manufacture completion");
        assertFalse(ResourceUse.retainedSatisfied(spec.resourceUses(),Map.of("minecraft:cobblestone",6)));
        var heldOutput=new SubtaskSpec("已有炉子仍须保留垫脚",Map.of("asset_key","minecraft:furnace","minimum",1),null,
                NodeType.MAIN,null,spec.resourceUses());
        var tail=new SubtaskSpec("拿木棍",Map.of("asset_key","minecraft:stick","minimum",1),null);
        var notReused=RddPlanGuard.filterSubtasks(List.of(heldOutput,tail),"基地施工",
                Map.of("minecraft:furnace",1,"minecraft:cobblestone",6));
        assertTrue(notReused.allowed().contains(heldOutput),"held output cannot silently drop an unmet independent RETAIN");
    }

    @Test void transferRequiresExactDestinationWhileEatingOnlyDeclaresItsInput() {
        var destination=Map.<String,Object>of("type","container_at","item","minecraft:cooked_beef","minimum",8,
                "dimension","minecraft:overworld","x",1,"y",64,"z",2);
        var transfer=RddStageBSpecParser.parseDetailed(plan("存入基地箱子",destination,List.of(use("minecraft:cooked_beef",8,"TRANSFER"))));
        assertEquals(1,transfer.specs().size());
        assertEquals(ResourceUse.Kind.TRANSFER,transfer.specs().get(0).resourceUses().get(0).kind());
        var unknownChest=RddStageBSpecParser.parseDetailed(plan("存入基地箱子",Map.of("type","stocked","minimum",10),List.of(use("minecraft:cooked_beef",8,"TRANSFER"))));
        assertTrue(unknownChest.specs().isEmpty());assertEquals(1,unknownChest.skippedRequired().size());
        var eat=RddStageBSpecParser.parseDetailed(plan("吃一个土豆，不能凭此断言健康已恢复",Map.of("type","item_used","item","minecraft:potato","minimum",1),List.of(use("minecraft:potato",1,"CONSUME"))));
        assertEquals(1,eat.specs().size());
        assertEquals("item_used",eat.specs().get(0).condition().get("type"));
        assertEquals(ResourceUse.Kind.CONSUME,eat.specs().get(0).resourceUses().get(0).kind());
    }

    @Test void invalidExplicitPurposesRejectRequiredStepRatherThanPublishItsPrefix() {
        List<Object> malformed=List.of("wrong",List.of(Map.of("asset_key","minecraft:cobblestone","quantity",1.5,"kind","CONSUME")),
                List.of(use("minecraft:cobblestone",0,"CONSUME")),List.of(use("minecraft:cobblestone",1,"UNKNOWN")),
                List.of(use("minecraft:cobblestone",8,"CONSUME"),use("minecraft:cobblestone",8,"CONSUME")));
        for(Object raw:malformed){
            var tree=JsonParser.parseString(plan("required furnace",FURNACE,raw)).getAsJsonObject();
            tree.getAsJsonArray("subtasks").add(GSON.toJsonTree(Map.of("description","挖木头","condition",Map.of("group","wood","minimum",1))));
            var parsed=RddStageBSpecParser.parseDetailed(tree.toString());
            var guarded=RddPlanGuard.filterSubtasks(parsed.specs(),"基地施工",Map.of());
            assertEquals(1,parsed.skippedRequired().size(),raw.toString());
            assertTrue(RddDecomposer.stageBPublished(parsed,guarded,"基地施工").isEmpty(),"required declarations cannot be silently lost");
        }
    }

    @Test void legacyStillUsesOriginalPredicateGapsAndNormalizationKeepsDeclarations() {
        var legacy=RddStageBSpecParser.parseDetailed(plan("清点背包圆石至少7个圆石",FURNACE,null)).specs().get(0);
        assertTrue(legacy.resourceUses().isEmpty());assertFalse(RddPlanGuard.predicateGaps(legacy).isEmpty());
        var typed=new SubtaskSpec("弄到一张床（任意颜色）",Map.of("asset_key","minecraft:white_bed","minimum",1),null,NodeType.MAIN,null,
                List.of(new ResourceUse("minecraft:potato",1,ResourceUse.Kind.RETAIN)));
        assertEquals(typed.resourceUses(),RddPlanGuard.normalizeCondition(typed).resourceUses());
        Map<?,?> props=(Map<?,?>)RddDecomposerPrompt.DECOMPOSE_TOOL.parameterSchema().get("properties");
        Map<?,?> items=(Map<?,?>)((Map<?,?>)props.get("subtasks")).get("items");
        assertTrue(((Map<?,?>)items.get("properties")).containsKey("resource_uses"));
        assertTrue(RddDecomposerPrompt.decompositionPrompt("基地",Map.of(),List.of(),"").contains("resource_uses"));
    }
}
