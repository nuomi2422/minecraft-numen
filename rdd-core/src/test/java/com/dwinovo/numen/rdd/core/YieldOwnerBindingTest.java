package com.dwinovo.numen.rdd.core;

import org.junit.jupiter.api.Test;
import java.lang.reflect.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Contract red: a server-held ticket must bind the full task owner and expire. */
class YieldOwnerBindingTest {
 @Test void rejectsStaleIdentityExpiryOtherCompanionAndRestart() throws Exception {
  Class<?> identity=Class.forName("com.dwinovo.numen.rdd.core.YieldOwnerBinding$Identity");
  Class<?> store=Class.forName("com.dwinovo.numen.rdd.core.YieldOwnerBinding");
  var ctor=identity.getConstructor(UUID.class,String.class,String.class,long.class,String.class,String.class,String.class,long.class);
  UUID a=UUID.randomUUID(),b=UUID.randomUUID();
  Object original=ctor.newInstance(a,"server-a","chain-a",3L,"s1","MAINLINE","",0L);
  Object changed=ctor.newInstance(a,"server-a","chain-b",3L,"s1","MAINLINE","",0L);
  Object other=ctor.newInstance(b,"server-a","chain-a",3L,"s1","MAINLINE","",0L);
  Object side=ctor.newInstance(a,"server-a","chain-a",3L,"s1","SIDE","side-1",7L);
  Object sideNew=ctor.newInstance(a,"server-a","chain-a",3L,"s1","SIDE","side-1",8L);
  Object ledger=store.getConstructor(int.class,long.class).newInstance(2,100L);
  var issue=store.getMethod("issue",identity,long.class);var valid=store.getMethod("valid",String.class,identity,long.class);
  String t=(String)issue.invoke(ledger,original,10L);
  assertEquals(true,valid.invoke(ledger,t,original,11L));
  assertEquals(false,valid.invoke(ledger,t,original,11L),"one-shot");
  assertEquals(false,valid.invoke(ledger,issue.invoke(ledger,original,10L),changed,11L));
  assertEquals(false,valid.invoke(ledger,issue.invoke(ledger,original,10L),other,11L));
  assertEquals(false,valid.invoke(ledger,issue.invoke(ledger,original,10L),original,110L));
  Object restarted=store.getConstructor(int.class,long.class).newInstance(2,100L);
  assertEquals(false,valid.invoke(restarted,t,original,11L));
  String s=(String)issue.invoke(ledger,side,20L);
  assertEquals(false,valid.invoke(ledger,s,sideNew,21L));
  Object rev=ctor.newInstance(a,"server-a","chain-a",4L,"s1","MAINLINE","",0L);
  Object next=ctor.newInstance(a,"server-a","chain-a",3L,"s2","MAINLINE","",0L);
  assertEquals(false,valid.invoke(ledger,issue.invoke(ledger,original,30L),next,31L));
  Object server=ctor.newInstance(a,"server-b","chain-a",3L,"s1","MAINLINE","",0L);
  assertEquals(false,valid.invoke(ledger,issue.invoke(ledger,original,30L),rev,31L));
  assertEquals(false,valid.invoke(ledger,issue.invoke(ledger,original,30L),server,31L));
  String evicted=(String)issue.invoke(ledger,original,40L); issue.invoke(ledger,original,41L); issue.invoke(ledger,original,42L);
  assertEquals(false,valid.invoke(ledger,evicted,original,43L));
  assertThrows(InvocationTargetException.class,()->ctor.newInstance(a,"", "chain-a",3L,"s1","MAINLINE","",0L));
  assertThrows(InvocationTargetException.class,()->issue.invoke(ledger,null,44L));
 }
}
