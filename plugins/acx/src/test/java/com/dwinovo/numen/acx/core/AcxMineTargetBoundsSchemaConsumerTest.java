package com.dwinovo.numen.acx.core;

import com.dwinovo.numen.acx.api.*;
import com.dwinovo.numen.plugins.acx.AcxRegistry;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real reference catalog -> port adapter -> loader/runner. Host execution is a capture boundary;
 * nested coordinate validation belongs to the actual core mine parser, not this coarse AC catalog. */
class AcxMineTargetBoundsSchemaConsumerTest {
    private static final Map<String,Object> BOUNDS=Map.of("x1",0,"y1",0,"z1",0,"x2",2,"y2",2,"z2",2);
    private static final class Host implements AcxToolPort {
        final List<Map<String,Object>> calls=new ArrayList<>();
        public String name(){return "mine";}
        public AcxPortSchema schema(){return NumenToolCatalog.find("mine").schema();}
        public Result invoke(Map<String,Object> params){calls.add(params);return Result.completed(Map.of("gathered",1,"requested",1,"partial",false,"shortfall",0));}
    }
    private static AcxRunRecord run(Host host,String params){
        var registry=new AcxRegistry();registry.register(PortToolAdapter.builder(host).build());
        var definition=AcxLoader.parseJson("{\"name\":\"mine_bounds_schema\",\"version\":\"1\",\"steps\":[{\"id\":\"mine\",\"block\":\"mine\",\"params\":"+params+"}]}",registry);
        return AcxRunner.builder().tools(registry).build().run(definition,Map.of("bounds",BOUNDS));
    }
    @Test void optionalObjectBoundsReachTheRealPortThroughLoaderAndRunner(){
        Host host=new Host();var result=run(host,"{\"block_ids\":[\"minecraft:iron_ore\"],\"count\":1,\"target_bounds\":\"$input.bounds\"}");
        assertEquals(AcxStatus.SUCCESS,result.status(),result.errorMessage());assertEquals(1,host.calls.size());
        assertEquals(BOUNDS,host.calls.getFirst().get("target_bounds"));
        var field=host.schema().params().get("target_bounds");assertNotNull(field);assertFalse(field.required());
        assertTrue(field.nullableAllowed());assertEquals(AcxPortSchema.Type.OBJECT,field.type());
    }
    @Test void legacyAndNullRemainOptionalWhileWrongTopLevelTypesDoNotDispatch(){
        for(String extra:List.of("",",\"target_bounds\":null")){
            Host host=new Host();var result=run(host,"{\"block_ids\":[\"minecraft:iron_ore\"],\"count\":1"+extra+"}");
            assertEquals(AcxStatus.SUCCESS,result.status(),result.errorMessage());assertEquals(1,host.calls.size());
        }
        for(String bounds:List.of("3","\"not-an-object\"","[]")){
            Host host=new Host();var result=run(host,"{\"block_ids\":[\"minecraft:iron_ore\"],\"count\":1,\"target_bounds\":"+bounds+"}");
            assertEquals(AcxStatus.FAIL,result.status());assertTrue(host.calls.isEmpty());
        }
    }
}
