package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.*;
import com.dwinovo.numen.rdd.core.AssetRegistry;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-11 B2: real tool schema/dispatch/TaskResult JSON, memory fixtures only, not a game run. */
class RddAssetsToolTest {
    private static void asset(AssetRegistry r,String id,long at,Map<String,Object> value) {
        r.apply(new Observation("obs-"+id,"world_location","fixture","overworld",at,value),id,AssetScope.GLOBAL,null);
    }
    private static JsonObject call(AssetRegistry r,String args) {
        String result=RddAssetsTool.dispatch(JsonParser.parseString(args).getAsJsonObject(),r);
        assertTrue(result.length()<=8000);return JsonParser.parseString(result).getAsJsonObject();
    }
    @Test void actualSchemaKeepsNoArgCompatibilityAndExposesActions() {
        var schema=new RddAssetsTool().parameterSchema();String json=new Gson().toJson(schema);
        assertTrue(json.contains("catalog"));assertTrue(json.contains("detail"));assertTrue(json.contains("asset_id"));
        assertTrue(call(new AssetRegistry(),"{}").get("success").getAsBoolean());
        assertEquals(0,call(new AssetRegistry(),"{\"action\":\"catalog\"}").getAsJsonObject("data").get("count").getAsInt());
    }
    @Test void catalogPagesReachOldAssetsBeyondForty() {
        AssetRegistry r=new AssetRegistry();for(int i=0;i<85;i++)asset(r,"id-"+i,i+1,Map.of("label","entry"+i));
        Set<String> ids=new HashSet<>();int offset=0;
        do {var d=call(r,"{\"action\":\"catalog\",\"offset\":"+offset+",\"limit\":20}").getAsJsonObject("data");
            assertEquals(85,d.get("count").getAsInt());for(var row:d.getAsJsonArray("assets"))ids.add(row.getAsJsonObject().get("asset_id").getAsString());
            int next=d.get("next_offset").getAsInt();assertTrue(next==-1||next>offset);offset=next;
        }while(offset!=-1);
        assertEquals(85,ids.size());assertTrue(ids.contains("id-0"));
        assertEquals("id-0",call(r,"{\"action\":\"detail\",\"asset_id\":\"id-0\"}").getAsJsonObject("data").getAsJsonObject("asset").get("assetId").getAsString());
    }
    @Test void unknownInvalidAndMissingMetadataStayVisibleNotUsable() {
        AssetRegistry r=new AssetRegistry();asset(r,"unknown",0,Map.of());asset(r,"invalid",2,Map.of());r.markUnknown("unknown");r.invalidate("invalid");
        String result=call(r,"{\"action\":\"catalog\"}").toString();
        assertTrue(result.contains("INVALID"));assertTrue(result.contains("UNKNOWN"));assertFalse(result.contains("OBSERVED"));
        assertEquals(2,call(r,"{\"action\":\"catalog\"}").getAsJsonObject("data").get("shown").getAsInt());
    }
    @Test void exactIdAndCompanionRegistryIsolationNoInventoryFallback() {
        AssetRegistry a=new AssetRegistry(),b=new AssetRegistry();asset(a,"A",1,Map.of());asset(b,"B",1,Map.of());
        b.apply(new Observation("inv","inventory_scan","test","overworld",1,Map.of("count",1)),"item",AssetScope.GLOBAL,null);
        for(String id:List.of("A","item","missing","B-prefix"))assertFalse(call(b,"{\"action\":\"detail\",\"asset_id\":\""+id+"\"}").get("success").getAsBoolean());
        assertTrue(call(a,"{\"action\":\"detail\",\"asset_id\":\"A\"}").get("success").getAsBoolean());
    }
    @Test void badActionsAndPaginationBoundsFailWithoutGuessing() {
        for(String args:List.of("{\"action\":\"other\"}","{\"action\":\"detail\"}","{\"action\":\"catalog\",\"offset\":-1}",
                "{\"action\":\"catalog\",\"limit\":0}","{\"action\":\"catalog\",\"limit\":41}","{\"action\":\"catalog\",\"offset\":1.5}"))
            assertFalse(call(new AssetRegistry(),args).get("success").getAsBoolean());
        assertTrue(call(new AssetRegistry(),"{\"action\":\"catalog\",\"offset\":2147483647}").get("success").getAsBoolean());
    }
    @Test void oversizedDetailAndEscapedFinalEnvelopeNeverSliceJson() {
        AssetRegistry r=new AssetRegistry();asset(r,"large",1,Map.of("label","large","nested",Map.of("body","\"<".repeat(10000))));
        var failure=call(r,"{\"action\":\"detail\",\"asset_id\":\"large\"}");
        assertFalse(failure.get("success").getAsBoolean());assertEquals("too_large",failure.getAsJsonObject("data").get("code").getAsString());
        assertEquals("large",failure.getAsJsonObject("data").get("asset_id").getAsString());
        assertTrue(call(r,"{\"action\":\"catalog\"}").get("success").getAsBoolean());
        call(r,"{}"); // legacy too-large also returns a whole bounded failure, never half JSON
    }
}
