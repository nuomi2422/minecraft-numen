package com.dwinovo.numen.core.task.interact;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

/** Source-level guard presence only: no claim that a missing guard executed. */
class InteractGolemSafetyConsumerTest {
 @Test void productionTaskMustGuardGolemBeforeAnyAttack() throws Exception {
  Path root=Path.of("").toAbsolutePath();
  while(root!=null&&!Files.isDirectory(root.resolve("core/common/src/main/java"))) root=root.getParent();
  assertNotNull(root);
  String source=Files.readString(root.resolve("core/common/src/main/java/com/dwinovo/numen/core/task/interact/InteractEntityCompanionTask.java"));
  int start=source.indexOf("    protected TaskState act() {");
  int click=source.indexOf("interaction.tick()",start);
  assertTrue(start>=0 && click>start);
  String before=source.substring(start,click);
  assertTrue(before.contains("IronGolem"), "production act currently has no golem-specific guard before click");
 }
}
