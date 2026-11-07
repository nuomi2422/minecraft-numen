package com.dwinovo.numen.core.task.light;

import com.dwinovo.numen.core.tools.work.BuildTool;
import com.dwinovo.numen.core.task.build.BuildTaskRecord;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AutoLightRequestTest {
    String op="{\"op\":\"auto_light\",\"x1\":0,\"y1\":64,\"z1\":0,\"x2\":3,\"y2\":64,\"z2\":3}";
    AutoLightRequest parse(String s){return BuildTool.parseAutoLight(JsonParser.parseString("{\"ops\":["+s+"]}").getAsJsonObject());}
    @Test void legacyBlueprintRecipeReplaysUnchanged(){
        String raw="{\"file\":\"house.nbt\",\"x\":1,\"y\":64,\"z\":2,\"rotation\":90}";
        var d=com.dwinovo.numen.task.TaskPersistence.decideRestore("blueprint",raw,t->true);
        var replay=assertInstanceOf(com.dwinovo.numen.task.TaskPersistence.RestoreDecision.Replay.class,d);
        assertEquals(JsonParser.parseString(raw),replay.args());
    }
    @Test void legacyBuildRecipeReplaysUnchanged(){
        String raw="{\"ops\":[{\"op\":\"set\",\"block_id\":\"torch\",\"x\":0,\"y\":64,\"z\":0}],\"replace_existing\":false}";
        var d=com.dwinovo.numen.task.TaskPersistence.decideRestore("build",raw,t->true);
        var replay=assertInstanceOf(com.dwinovo.numen.task.TaskPersistence.RestoreDecision.Replay.class,d);
        assertEquals(JsonParser.parseString(raw),replay.args());
        assertNull(BuildTool.parseAutoLight(replay.args()));
    }
    @Test void lightingRecipeReplaysThroughSameParser(){
        String raw="{\"ops\":["+op+"]}";
        var d=com.dwinovo.numen.task.TaskPersistence.decideRestore("build",raw,t->true);
        var replay=assertInstanceOf(com.dwinovo.numen.task.TaskPersistence.RestoreDecision.Replay.class,d);
        assertEquals(parse(op),BuildTool.parseAutoLight(replay.args()));
    }
    @Test void extremeCoordinateSpanRejectedBeforeMultiplication(){
        assertThrows(IllegalArgumentException.class,()->new AutoLightRequest(
                new net.minecraft.core.BlockPos(Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE),
                new net.minecraft.core.BlockPos(Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE),1,1,1));
    }
    @Test void defaultsAndRegion(){var r=parse(op);assertEquals(16,r.volume());assertEquals(64,r.maxLights());assertEquals(2400,r.maxTicks());}
    @Test void reversedBoundsNormalized(){assertEquals(16,parse(op.replace("\"x1\":0","\"x1\":3").replace("\"x2\":3","\"x2\":0")).volume());}
    @Test void fractionalRejected(){assertThrows(IllegalArgumentException.class,()->parse(op.replace("\"x1\":0","\"x1\":0.5")));}
    @Test void numericStringRejected(){assertThrows(IllegalArgumentException.class,()->parse(op.replace("\"x1\":0","\"x1\":\"0\"")));}
    @Test void nullRejected(){assertThrows(IllegalArgumentException.class,()->parse(op.replace("\"x1\":0","\"x1\":null")));}
    @Test void overflowRejected(){assertThrows(IllegalArgumentException.class,()->parse(op.replace("\"x1\":0","\"x1\":2147483648")));}
    @Test void volumeRejected(){assertThrows(IllegalArgumentException.class,()->parse(op.replace("\"x2\":3","\"x2\":30000")));}
    @Test void wrongItemRejected(){assertThrows(IllegalArgumentException.class,()->parse(op.replace("}",",\"block_id\":\"minecraft:wall_torch\"}")));}
    @Test void unknownOptionRejected(){assertThrows(IllegalArgumentException.class,()->parse(op.replace("}",",\"threshold\":15}")));}
    @Test void zeroBudgetRejected(){assertThrows(IllegalArgumentException.class,()->parse(op.replace("}",",\"max_lights\":0}")));}
    @Test void excessTimeRejected(){assertThrows(IllegalArgumentException.class,()->parse(op.replace("}",",\"max_ticks\":12001}")));}
    @Test void mixedOpsRejected(){assertThrows(IllegalArgumentException.class,()->BuildTool.parseAutoLight(JsonParser.parseString("{\"ops\":["+op+","+op+"]}").getAsJsonObject()));}
    @Test void replacementRejected(){assertThrows(IllegalArgumentException.class,()->BuildTool.parseAutoLight(JsonParser.parseString("{\"replace_existing\":true,\"ops\":["+op+"]}").getAsJsonObject()));}
    @Test void legacySetIsNotIntercepted(){assertNull(BuildTool.parseAutoLight(JsonParser.parseString("{\"ops\":[{\"op\":\"set\",\"block_id\":\"torch\",\"x\":0,\"y\":64,\"z\":0}]}").getAsJsonObject()));}
    @Test void legacyRecordConstructorRetainsDefaults(){var r=new BuildTaskRecord("old",100,List.of(),true);assertNull(r.autoLight);assertTrue(r.replaceExisting);assertFalse(r.allowPartial);}
    @Test void lightingRecordNeverContainsDenseTargets(){var r=BuildTaskRecord.autoLight("new",100,parse(op),true);assertTrue(r.targets.isEmpty());assertFalse(r.replaceExisting);assertNotNull(r.autoLight);}
}
