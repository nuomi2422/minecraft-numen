package com.dwinovo.numen.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ExecutorPriorityTest {
    private static NumenPlugins.KnowledgeContributor c(String tag) {
        return (id, obj) -> NumenPlugins.KnowledgeFragment.ofText(tag);
    }

    @AfterEach void clear() { NumenPlugins.clearExecutorForTest(); }

    @Test void higherPriorityFirstAndTieByOwnerNotRegistration() {
        var low = c("low"); var high = c("high"); var a = c("a"); var b = c("b");
        NumenPlugins.contributeExecutorKnowledge(low, 0, "low");
        NumenPlugins.contributeExecutorKnowledge(high, 10, "high");
        NumenPlugins.contributeExecutorKnowledge(b, 0, "b");
        NumenPlugins.contributeExecutorKnowledge(a, 0, "a");
        var list = NumenPlugins.executorContributors();
        assertSame(high, list.get(0));
        assertSame(a, list.get(1));
        assertSame(b, list.get(2));
        assertSame(low, list.get(3));
    }
}
