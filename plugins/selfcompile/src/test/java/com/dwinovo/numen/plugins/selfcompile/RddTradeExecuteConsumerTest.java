package com.dwinovo.numen.plugins.selfcompile;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Compiles the complete production trade tool and TaskResult against isolated boundary stubs.
 * This tests neither real Minecraft behavior nor model adoption. */
class RddTradeExecuteConsumerTest {
 @TempDir static Path dir;
 static URLClassLoader loader;
 static Class<?> driver;
 @BeforeAll static void compile() throws Exception {
  Path root=Path.of("").toAbsolutePath();
  while(root!=null && !Files.exists(root.resolve("plugins/selfcompile/src/main/java/com/dwinovo/numen/plugins/selfcompile/generated/RddTradeExecuteTool.java"))) root=root.getParent();
  assertNotNull(root,"repository root not found");
  Path src=dir.resolve("src"), out=dir.resolve("out"); Files.createDirectories(out);
  Map<String,String> s=new LinkedHashMap<>();
  s.put("com.dwinovo.numen.plugins.selfcompile.generated.RddTradeExecuteTool", Files.readString(root.resolve("plugins/selfcompile/src/main/java/com/dwinovo/numen/plugins/selfcompile/generated/RddTradeExecuteTool.java")));
  s.put("com.dwinovo.numen.task.TaskResult", Files.readString(root.resolve("api/common/src/main/java/com/dwinovo/numen/task/TaskResult.java")));
  s.put("com.dwinovo.numen.agent.tool.NumenTool", "package com.dwinovo.numen.agent.tool; import java.util.*; import java.util.function.*; import com.google.gson.JsonObject; import com.dwinovo.numen.entity.NumenPlayer; public interface NumenTool { String name(); String description(); Map<String,Object> parameterSchema(); void onServerCall(String id,JsonObject args,NumenPlayer p,Consumer<String> reply); }");
  s.put("net.minecraft.world.entity.Entity", "package net.minecraft.world.entity; public class Entity {}");
  s.put("net.minecraft.server.level.ServerLevel", "package net.minecraft.server.level; import net.minecraft.world.entity.Entity; public class ServerLevel { public Entity entity; public Entity getEntity(int id){return id==42?entity:null;} }");
  s.put("net.minecraft.world.item.ItemStack", "package net.minecraft.world.item; public class ItemStack { public static final ItemStack EMPTY=new ItemStack(\"empty\",0); public String item; public int count; public ItemStack(String i,int n){item=i;count=n;} public ItemStack copy(){return new ItemStack(item,count);} public boolean isEmpty(){return count<=0;} public int getCount(){return count;} public String getItem(){return item;} public int getMaxStackSize(){return 64;} public void shrink(int n){count-=n;} public static boolean isSameItemSameComponents(ItemStack a,ItemStack b){return a.item.equals(b.item);} }");
  s.put("net.minecraft.core.registries.BuiltInRegistries", "package net.minecraft.core.registries; public class BuiltInRegistries { public static final Registry ITEM=new Registry(); public static class Registry { public String getKey(String item){return item;} } }");
  s.put("net.minecraft.world.item.trading.MerchantOffer", "package net.minecraft.world.item.trading; import net.minecraft.world.item.ItemStack; public class MerchantOffer { public ItemStack a,b,result; public int uses,max; public MerchantOffer(int u){a=new ItemStack(\"minecraft:wheat\",3);b=ItemStack.EMPTY;result=new ItemStack(\"minecraft:emerald\",1);uses=u;max=2;} public ItemStack getCostA(){return a;} public ItemStack getCostB(){return b;} public ItemStack getResult(){return result;} public int getUses(){return uses;} public int getMaxUses(){return max;} public boolean isOutOfStock(){return uses>=max;} }");
  s.put("net.minecraft.world.item.trading.MerchantOffers", "package net.minecraft.world.item.trading; public class MerchantOffers extends java.util.ArrayList<MerchantOffer> {}");
  s.put("net.minecraft.world.entity.npc.Villager", "package net.minecraft.world.entity.npc; import net.minecraft.world.entity.Entity; import net.minecraft.world.item.trading.*; public class Villager extends Entity { public MerchantOffers offers=new MerchantOffers(); public int notifications; public MerchantOffers getOffers(){return offers;} public void notifyTrade(MerchantOffer o){notifications++;o.uses++;} }");
  s.put("net.minecraft.world.entity.player.Inventory", "package net.minecraft.world.entity.player; import net.minecraft.world.item.ItemStack; public class Inventory { public ItemStack[] slots={new ItemStack(\"minecraft:wheat\",5),ItemStack.EMPTY}; public int adds; public int getContainerSize(){return slots.length;} public ItemStack getItem(int i){return slots[i];} public void setItem(int i,ItemStack s){slots[i]=s;} public boolean add(ItemStack s){adds++; for(int i=0;i<slots.length;i++){if(slots[i].isEmpty()){slots[i]=s.copy();s.shrink(s.getCount());return true;}}return false;} }");
  s.put("com.dwinovo.numen.entity.NumenPlayer", "package com.dwinovo.numen.entity; import net.minecraft.server.level.ServerLevel; import net.minecraft.world.entity.player.Inventory; import net.minecraft.world.item.ItemStack; public class NumenPlayer { public ServerLevel world; public Inventory inv=new Inventory(); public int drops; public Object level(){return world;} public Inventory getInventory(){return inv;} public void drop(ItemStack s,boolean b){drops++;} }");
  s.put("harness.Driver", """
   package harness;
   import java.util.*;
   import com.google.gson.*;
   import com.dwinovo.numen.entity.NumenPlayer;
   import com.dwinovo.numen.plugins.selfcompile.generated.RddTradeExecuteTool;
   import net.minecraft.server.level.ServerLevel;
   import net.minecraft.world.entity.npc.Villager;
   import net.minecraft.world.item.trading.MerchantOffer;
   public class Driver {
    public static Map<String,Object> run(boolean sold) {
     var p=new NumenPlayer(); p.world=new ServerLevel(); var v=new Villager(); p.world.entity=v;
     var o=new MerchantOffer(sold?2:0); v.offers.add(o);
     var args=new JsonObject(); args.addProperty("entity_id",42); args.addProperty("offer_index",0);
     List<String> replies=new ArrayList<>(); new RddTradeExecuteTool().onServerCall("call-1",args,p,replies::add);
     return Map.of("wheat",p.inv.slots[0].getCount(),"output",p.inv.slots[1].isEmpty()?0:p.inv.slots[1].getCount(),"outputItem",p.inv.slots[1].getItem(),"adds",p.inv.adds,"drops",p.drops,"notify",v.notifications,"uses",o.uses,"replies",replies);
    }
   }
   """);
  URL gson=com.google.gson.JsonParser.class.getProtectionDomain().getCodeSource().getLocation();
  List<String> args=new ArrayList<>(List.of("-encoding","UTF-8","-proc:none","-classpath",Path.of(gson.toURI()).toString(),"-d",out.toString()));
  for(var e:s.entrySet()){Path f=src.resolve(e.getKey().replace('.','/')+".java");Files.createDirectories(f.getParent());Files.writeString(f,e.getValue());args.add(f.toString());}
  var javac=ToolProvider.getSystemJavaCompiler();assertNotNull(javac,"JDK required");
  assertEquals(0,javac.run(null,null,null,args.toArray(String[]::new)),"complete production classes must compile");
  loader=new URLClassLoader(new URL[]{out.toUri().toURL(),gson},ClassLoader.getPlatformClassLoader());driver=loader.loadClass("harness.Driver");
 }
 @AfterAll static void close() throws Exception {if(loader!=null)loader.close();}
 @SuppressWarnings("unchecked") static Map<String,Object> run(boolean sold) throws Exception {return (Map<String,Object>)driver.getMethod("run",boolean.class).invoke(null,sold);}
 static com.google.gson.JsonObject reply(Map<String,Object> r){ List<?> a=(List<?>)r.get("replies"); assertEquals(1,a.size());return com.google.gson.JsonParser.parseString((String)a.get(0)).getAsJsonObject(); }
 @Test void soldOutDoesNotConsumeProduceOrNotify() throws Exception {
  var r=run(true); var json=reply(r); assertAll(
   ()->assertFalse(json.get("success").getAsBoolean()),
   ()->assertEquals(5,r.get("wheat")),()->assertEquals(0,r.get("output")),
   ()->assertEquals(0,r.get("adds")),()->assertEquals(0,r.get("drops")),
   ()->assertEquals(0,r.get("notify")),()->assertEquals(2,r.get("uses")));
 }
 @Test void availableOfferCompletes() throws Exception {
  var r=run(false);var json=reply(r); assertTrue(json.get("success").getAsBoolean());var d=json.getAsJsonObject("data");
  assertAll(()->assertEquals(2,r.get("wheat")),()->assertEquals(1,r.get("output")),
   ()->assertEquals("minecraft:emerald",r.get("outputItem")),()->assertEquals(1,r.get("adds")),
   ()->assertEquals(0,r.get("drops")),()->assertEquals(1,r.get("notify")),()->assertEquals(1,r.get("uses")),
   ()->assertEquals("minecraft:wheat x3",d.get("paid").getAsString()),
   ()->assertEquals("minecraft:emerald x1",d.get("got").getAsString()),
   ()->assertFalse(d.get("dropped_if_full").getAsBoolean()),
   ()->assertEquals(1,d.get("uses").getAsInt()),()->assertEquals(2,d.get("max_uses").getAsInt()));
 }
}
