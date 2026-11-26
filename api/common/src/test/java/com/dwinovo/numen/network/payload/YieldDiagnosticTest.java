package com.dwinovo.numen.network.payload;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class YieldDiagnosticTest {
 @Test void redactsOnlyYieldAndFailsClosedForMalformedJson() {
  String secret="sensitive-ticket-123";
  var p=new ExecuteToolPayload(UUID.randomUUID(),"wire","rdd_report_yield", "{\"asset_key\":\"minecraft:hay_block\",\"_yield_ticket\":\""+secret+"\"}");
  String safe=ExecuteToolPayload.diagnosticArgs(p);
  assertFalse(safe.contains(secret)); assertTrue(safe.contains("minecraft:hay_block"));
  var malformed=new ExecuteToolPayload(p.entityUuid(),"wire","rdd_report_yield", "{\"_yield_ticket\":\""+secret);
  assertEquals("[invalid yield arguments]",ExecuteToolPayload.diagnosticArgs(malformed));
  var ordinary=new ExecuteToolPayload(p.entityUuid(),"wire","other",p.argumentsJson());
  assertFalse(ExecuteToolPayload.diagnosticArgs(ordinary).contains(secret));
  assertTrue(ExecuteToolPayload.diagnosticArgs(ordinary).contains("minecraft:hay_block"));
  var ordinarySafe=new ExecuteToolPayload(p.entityUuid(),"wire","other","{\"item\":\"stone\"}");
  assertEquals(ordinarySafe.argumentsJson(),ExecuteToolPayload.diagnosticArgs(ordinarySafe));
 }
}
