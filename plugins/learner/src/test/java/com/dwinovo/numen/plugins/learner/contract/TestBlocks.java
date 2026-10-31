package com.dwinovo.numen.plugins.learner.contract;

import com.dwinovo.numen.plugins.learner.blocks.BlockKind;
import com.dwinovo.numen.plugins.learner.blocks.EnvBlock;

/** 测试用块构造（只在本包测试里用）。 */
final class TestBlocks {

    private TestBlocks() {
    }

    static EnvBlock decisionContext(String id) {
        return new EnvBlock(BlockKind.DECISION_CONTEXT, id, 1L, "context.jsonl#runtime_state",
                EnvBlock.Coverage.COMPLETE, EnvBlock.Freshness.CURRENT, EnvBlock.Confidence.HIGH,
                "turn=1", "<runtime_state/>", false);
    }

    static EnvBlock summary(String id) {
        return new EnvBlock(BlockKind.SAMPLE_SUMMARY, id, 1L, "samples.jsonl",
                EnvBlock.Coverage.COMPLETE, EnvBlock.Freshness.CURRENT, EnvBlock.Confidence.HIGH,
                "death", "sampleId=s-1", false);
    }
}
