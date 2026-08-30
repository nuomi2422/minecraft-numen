package com.dwinovo.numen.plugins.selfcompile;

import java.time.Duration;
import java.util.Objects;

/** Orchestrates only the safe pre-compile stages; execution remains disabled. */
public final class MutationPipeline {
    private final MutationWorkspace workspace;
    private final MutationSourceStore sources;
    private final MutationBudget budget;

    public MutationPipeline(MutationWorkspace workspace, int maxAttempts, Duration maxDuration) {
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.sources = new MutationSourceStore(workspace);
        this.budget = new MutationBudget(maxAttempts, maxDuration);
    }

    public MutationManifest request(String requirement) throws java.io.IOException {
        if (!budget.tryAcquire()) throw new IllegalStateException("mutation budget exhausted");
        return workspace.create(requirement);
    }

    public MutationManifest recordGeneratedSource(MutationManifest manifest,
                                                   String fileName, String source) throws java.io.IOException {
        sources.write(manifest, fileName, source);
        return MutationStateMachine.transition(manifest, MutationState.GENERATED, "");
    }

    public MutationBudget budget() { return budget; }
}
