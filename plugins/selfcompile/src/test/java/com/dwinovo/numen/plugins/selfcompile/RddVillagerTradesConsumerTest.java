package com.dwinovo.numen.plugins.selfcompile;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Offline boundary test: complete production tool, result and interfaces, with Minecraft and
 * host transport stubs. It does not establish real game behavior, API compatibility or model use. */
class RddVillagerTradesConsumerTest {
 @TempDir static Path dir;
 static URLClassLoader loader;
 static Class<?> driver;
 @BeforeAll static void compile() throws Exception {
  Path root=Path.of("").toAbsolutePath();
  String tool="plugins/selfcompile/src/main/java/com/dwinovo/numen/plugins/selfcompile/generated/RddVillagerTradesTool.java";
  while(root!=null && !Files.exists(root.resolve(tool))) root=root.getParent();
  assertNotNull(root,"repository root not found");
  Path src=dir.resolve("src"), out=dir.resolve("out"); Files.createDirectories(out);
  Map<String,String> s=new LinkedHashMap<>();
  s.put("com.dwinovo.numen.plugins.selfcompile.generated.RddVillagerTradesTool",Files.readString(root.resolve(tool)));
  s.put("com.dwinovo.numen.task.TaskResult",Files.readString(root.resolve("api/common/src/main/java/com/dwinovo/numen/task/TaskResult.java")));
  s.put("com.dwinovo.numen.agent.tool.NumenTool",Files.readString(root.resolve("api/common/src/main/java/com/dwinovo/numen/agent/tool/NumenTool.java")));
  s.put("com.dwinovo.numen.agent.provider.IToolSpec",Files.readString(root.resolve("ai/src/main/java/com/dwinovo/numen/agent/provider/IToolSpec.java")));
  s.put("com.dwinovo.numen.agent.tool.ToolCall","package com.dwinovo.numen.agent.tool; public class ToolCall {} ");
  s.put("com.dwinovo.numen.agent.tool.ServerToolTransport","package com.dwinovo.numen.agent.tool; public class ServerToolTransport { public static void ship(ToolCall c) {} }");
  s.put("net.minecraft.world.entity.Entity","package net.minecraft.world.entity; public class Entity { public String getType(){return \"minecraft:pig\";} }");
  s.put("net.minecraft.server.level.ServerLevel","package net.minecraft.server.level; import net.minecraft.world.entity.Entity; public class ServerLevel { public Entity entity; public int lookups; public Entity getEntity(int id){lookups++;return id==42?entity:null;} }");
  s.put("com.dwinovo.numen.entity.NumenPlayer","package com.dwinovo.numen.entity; import net.minecraft.server.level.ServerLevel; public class NumenPlayer { public ServerLevel world; public Object level(){return world;} }");
  s.put("net.minecraft.core.registries.BuiltInRegistries","package net.minecraft.core.registries; public class BuiltInRegistries { public static final Registry ITEM=new Registry(), ENTITY_TYPE=new Registry(); public static class Registry { public String getKey(String item){return item;} } }");
  s.put("net.minecraft.world.item.ItemStack","package net.minecraft.world.item; public class ItemStack { public String item; public int count; public ItemStack(String i,int n){item=i;count=n;} public boolean isEmpty(){return count==0;} public String getItem(){return item;} public int getCount(){return count;} }");
  s.put("net.minecraft.world.item.trading.MerchantOffer","package net.minecraft.world.item.trading; import net.minecraft.world.item.ItemStack; public class MerchantOffer { public ItemStack a=new ItemStack(\"minecraft:wheat\",3), b=new ItemStack(\"minecraft:book\",2), result=new ItemStack(\"minecraft:emerald\",1); public int uses=1; public ItemStack getCostA(){return a;} public ItemStack getCostB(){return b;} public ItemStack getResult(){return result;} public int getUses(){return uses;} public int getMaxUses(){return 12;} public float getPriceMultiplier(){return 0.05f;} }");
  s.put("net.minecraft.world.item.trading.MerchantOffers","package net.minecraft.world.item.trading; public class MerchantOffers extends java.util.ArrayList<MerchantOffer> {} ");
  s.put("net.minecraft.world.entity.npc.VillagerProfession","package net.minecraft.world.entity.npc; public enum VillagerProfession { FARMER,NITWIT }");
  s.put("net.minecraft.world.entity.npc.VillagerData","package net.minecraft.world.entity.npc; public class VillagerData { public VillagerProfession getProfession(){return VillagerProfession.FARMER;} public String getType(){return \"plains\";} public int getLevel(){return 2;} }");
  s.put("net.minecraft.world.entity.npc.AbstractVillager","package net.minecraft.world.entity.npc; import net.minecraft.world.entity.Entity; import net.minecraft.world.item.trading.MerchantOffers; public abstract class AbstractVillager extends Entity { public abstract MerchantOffers getOffers(); }");
  s.put("net.minecraft.world.entity.npc.Villager","package net.minecraft.world.entity.npc; import net.minecraft.world.entity.Entity; import net.minecraft.world.item.trading.*; public class Villager extends AbstractVillager { public MerchantOffers offers=new MerchantOffers(); public int reads,notifications; public MerchantOffers getOffers(){reads++;return offers;} public void notifyTrade(MerchantOffer o){notifications++;o.uses++;} public VillagerData getVillagerData(){return new VillagerData();} public boolean isBaby(){return false;} }");
  // In Minecraft a wandering trader is not a Villager. Keep it distinct to expose the contract gap.
  s.put("net.minecraft.world.entity.npc.WanderingTrader","package net.minecraft.world.entity.npc; import net.minecraft.world.entity.Entity; import net.minecraft.world.item.trading.*; public class WanderingTrader extends AbstractVillager { public MerchantOffers offers=new MerchantOffers(); public int reads,notifications; public MerchantOffers getOffers(){reads++;return offers;} public void notifyTrade(MerchantOffer o){notifications++;o.uses++;} public String getType(){return \"minecraft:wandering_trader\";} }");
  s.put("harness.Driver", """
   package harness;
   import java.util.*;
   import com.google.gson.*;
   import com.dwinovo.numen.entity.NumenPlayer;
   import com.dwinovo.numen.plugins.selfcompile.generated.RddVillagerTradesTool;
   import net.minecraft.server.level.ServerLevel;
   import net.minecraft.world.entity.Entity;
   import net.minecraft.world.entity.npc.*;
   import net.minecraft.world.item.trading.MerchantOffer;
   public class Driver {
    public static Map<String,Object> run(String scenario) {
     var p=new NumenPlayer(); p.world=new ServerLevel(); var offer=new MerchantOffer();
     var v=new Villager(); v.offers.add(offer);
     var w=new WanderingTrader(); w.offers.add(offer);
     p.world.entity=switch(scenario) {case "villager" -> v; case "wandering" -> w; case "nonmerchant" -> new Entity(); default -> null;};
     var args=new JsonObject(); if(!scenario.equals("missing")) args.addProperty("entity_id",scenario.equals("unknown")?99:42);
     List<String> replies=new ArrayList<>(); new RddVillagerTradesTool().onServerCall("call-1",args,p,replies::add);
     return Map.of("replies",replies,"lookups",p.world.lookups,"villagerReads",v.reads,"wanderingReads",w.reads,
       "notifications",v.notifications+w.notifications,"uses",offer.uses,"cost1",offer.a.count,"cost2",offer.b.count,"result",offer.result.count);
    }
   }
   """);
  URL gson=com.google.gson.JsonParser.class.getProtectionDomain().getCodeSource().getLocation();
  List<String> args=new ArrayList<>(List.of("-encoding","UTF-8","-proc:none","-classpath",Path.of(gson.toURI()).toString(),"-d",out.toString()));
  for(var e:s.entrySet()){Path f=src.resolve(e.getKey().replace('.','/')+".java");Files.createDirectories(f.getParent());Files.writeString(f,e.getValue());args.add(f.toString());}
  var javac=ToolProvider.getSystemJavaCompiler(); assertNotNull(javac,"JDK required");
  assertEquals(0,javac.run(null,null,null,args.toArray(String[]::new)),"complete production classes and interfaces must compile");
  loader=new URLClassLoader(new URL[]{out.toUri().toURL(),gson},ClassLoader.getPlatformClassLoader()); driver=loader.loadClass("harness.Driver");
 }
 @AfterAll static void close() throws Exception {if(loader!=null)loader.close();}
 @SuppressWarnings("unchecked") static Map<String,Object> run(String scenario) throws Exception {return (Map<String,Object>)driver.getMethod("run",String.class).invoke(null,scenario);}
 static com.google.gson.JsonObject reply(Map<String,Object> r){List<?> a=(List<?>)r.get("replies");assertEquals(1,a.size());return com.google.gson.JsonParser.parseString((String)a.get(0)).getAsJsonObject();}
 static void unchanged(Map<String,Object> r){assertAll(()->assertEquals(0,r.get("notifications")),()->assertEquals(1,r.get("uses")),()->assertEquals(3,r.get("cost1")),()->assertEquals(2,r.get("cost2")),()->assertEquals(1,r.get("result")));}
 @Test void villagerReplyContainsActualOffer() throws Exception {
  var r=run("villager");var j=reply(r);assertTrue(j.get("success").getAsBoolean());var d=j.getAsJsonObject("data");
  assertEquals(42,d.get("entity_id").getAsInt());assertEquals(1,d.get("count").getAsInt());
  assertEquals("villager",d.get("merchant_type").getAsString()); assertEquals("FARMER",d.get("profession").getAsString()); assertTrue(d.get("can_work").getAsBoolean());
  var offers=d.getAsJsonArray("offers");assertEquals(1,offers.size());var o=offers.get(0).getAsJsonObject();
  assertAll(()->assertEquals("minecraft:wheat x3",o.get("cost1").getAsString()),()->assertEquals("minecraft:book x2",o.get("cost2").getAsString()),()->assertEquals("minecraft:emerald x1",o.get("result").getAsString()),()->assertEquals(1,o.get("uses").getAsInt()),()->assertEquals(12,o.get("max_uses").getAsInt()),()->assertEquals(0.05,o.get("price_multiplier").getAsDouble(),0.0001),()->assertEquals(1,r.get("lookups")),()->assertEquals(1,r.get("villagerReads")));
  unchanged(r);
 }
 @Test void missingUnknownAndNonMerchantAreRejected() throws Exception {
  for(String scenario:List.of("missing","unknown","nonmerchant")){var r=run(scenario);var j=reply(r);assertFalse(j.get("success").getAsBoolean(),scenario);assertFalse(j.get("message").getAsString().isBlank(),scenario);assertEquals(scenario.equals("missing")?0:1,r.get("lookups"),scenario);assertEquals(0,r.get("villagerReads"));assertEquals(0,r.get("wanderingReads"));unchanged(r);}
 }
 @Test void wanderingTraderShouldSucceedAsProductionDescriptionPromises() throws Exception {
  var r=run("wandering");var j=reply(r);unchanged(r);
  assertTrue(j.get("success").getAsBoolean(),"description promises wandering trader support: "+j);
  var data=j.getAsJsonObject("data"); assertEquals("wandering_trader",data.get("merchant_type").getAsString());
  for(String key:List.of("profession","type","level","nitwit","baby","can_work")) assertFalse(data.has(key),key);
  var offers=data.getAsJsonArray("offers");assertEquals(1,offers.size());
  assertEquals("minecraft:emerald x1",offers.get(0).getAsJsonObject().get("result").getAsString());
 }
}
