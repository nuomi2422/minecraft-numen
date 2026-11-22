package com.dwinovo.numen.plugins.learner.skills;

import com.dwinovo.numen.api.LearnedSkillBodyResolver;
import com.dwinovo.numen.plugins.learner.core.LearnedLocationBinding;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

/**
 * Explicit body reads use the learner's original registry and delivery checks.
 * No second store, history fallback, adoption receipt or recursive package loading.
 * Each call re-reads current eligibility, so revoked or revised entries cannot leak via cache.
 */
public final class LearnedSkillBodyResolverImpl implements LearnedSkillBodyResolver {
    private final Path configDir;

    public LearnedSkillBodyResolverImpl(Path configDir) {
        this.configDir = Objects.requireNonNull(configDir);
    }

    @Override
    public PackageComponentResolution resolveComponent(UUID companion, String packageRef, String componentRef, View view) {
        // Cache only for this request: duplicates (including the selected component) are read once.
        var reads = new java.util.HashMap<String, Resolution>();
        java.util.function.Function<String, Resolution> read = ref ->
                reads.computeIfAbsent(ref, key -> resolve(companion, key, view));
        Resolution pack = read.apply(packageRef);
        if (!pack.available()) return PackageComponentResolution.refused(packageRef, componentRef, pack.gap());
        try {
            Reference requested = Reference.parse(packageRef);
            Reference.parse(componentRef);
            var entry = SkillRegistry.open(configDir.resolve("skills.jsonl")).get(requested.skillId()).orElse(null);
            // Recheck identity because the registry may have changed since the body read.
            if (entry == null || entry.version() != requested.version() || !entry.status().retrievable()
                    || !new SkillsKnowledgeContributor(configDir, view.name(), 0).audienceMatches(entry.audience()))
                return PackageComponentResolution.refused(packageRef, componentRef, "package-changed");
            if (!"package".equals(SceneSkillCatalog.kind(entry)))
                return PackageComponentResolution.refused(packageRef, componentRef, "not-a-package");
            boolean selected = false;
            boolean degraded = false;
            boolean missingRequired = false;
            for (String condition : entry.preconditions()) {
                if (condition == null) continue;
                boolean required = condition.startsWith("requires:");
                boolean optional = condition.startsWith("optional:");
                if (!required && !optional) continue;
                String ref = condition.substring(required ? "requires:".length() : "optional:".length());
                if (ref.equals(componentRef)) selected = true;
                // A package cannot establish its own required dependency by reading itself.
                if (required && ref.equals(packageRef)) { missingRequired = true; continue; }
                Resolution dependency = read.apply(ref);
                if (!dependency.available()) {
                    if (required) missingRequired = true;
                    else degraded = true;
                }
            }
            if (!selected) return PackageComponentResolution.refused(packageRef, componentRef, "component-not-referenced");
            if (packageRef.equals(componentRef)) return PackageComponentResolution.refused(packageRef, componentRef, "not-a-scene-component");
            Reference componentIdentity=Reference.parse(componentRef);
            var componentEntry=SkillRegistry.open(configDir.resolve("skills.jsonl")).get(componentIdentity.skillId()).orElse(null);
            if(componentEntry==null || componentEntry.version()!=componentIdentity.version()
                    || !"component".equals(SceneSkillCatalog.kind(componentEntry)))
                return PackageComponentResolution.refused(packageRef, componentRef, "not-a-scene-component");
            if (missingRequired) return PackageComponentResolution.refused(packageRef, componentRef, "missing-required-component");
            Resolution component = read.apply(componentRef);
            if (!component.available()) return PackageComponentResolution.refused(packageRef, componentRef, component.gap());
            return new PackageComponentResolution(packageRef, componentRef, component.body(), degraded ? "degraded" : "ready", "");
        } catch (IllegalArgumentException ex) {
            return PackageComponentResolution.refused(packageRef, componentRef, "invalid-reference");
        } catch (IOException | RuntimeException ex) {
            return PackageComponentResolution.refused(packageRef, componentRef, "registry-read-failed");
        }
    }

    /** Only a declared, current, eligible package can name the initial supported topic. */
    @Override
    public FocusDeclaration resolveFocusDeclaration(UUID companion,String packageRef,View view) {
        Resolution packageBody=resolve(companion,packageRef,view);
        if (!packageBody.available()) return new FocusDeclaration(packageRef,"","",packageBody.gap());
        try {
            Reference requested=Reference.parse(packageRef);
            var entry=SkillRegistry.open(configDir.resolve("skills.jsonl")).get(requested.skillId()).orElse(null);
            if(entry==null || entry.version()!=requested.version() || !entry.status().retrievable()
                    || !new SkillsKnowledgeContributor(configDir,view.name(),0).audienceMatches(entry.audience())
                    || !"package".equals(SceneSkillCatalog.kind(entry)))
                return new FocusDeclaration(packageRef,"","","package-changed-or-invalid");
            if (SceneSkillCatalog.missingRequired(entry, this, companion, view))
                return new FocusDeclaration(packageRef,"","","missing-required-component");
            var declarations=entry.preconditions().stream().filter(v->v.startsWith("focus:")).toList();
            if(declarations.isEmpty()) return new FocusDeclaration(packageRef,"","","focus-not-declared");
            if(declarations.size()!=1 || !declarations.getFirst().equals("focus:environment@v1"))
                return new FocusDeclaration(packageRef,"","","focus-unsupported");
            return new FocusDeclaration(packageRef,"environment","v1","");
        } catch (IOException | RuntimeException ex) {
            return new FocusDeclaration(packageRef,"","","focus-declaration-invalid");
        }
    }

    @Override
    public long managedRevision() {
        return SkillRegistrar.peekManaged(configDir).map(r -> r.declarationSnapshot().revision()).orElse(-1L);
    }

    @Override
    public ManagedFocusEligibility managedFocusEligibility(UUID companion, String packageRef, View view) {
        var registry=SkillRegistrar.peekManaged(configDir).orElse(null);
        if (registry==null) return new ManagedFocusEligibility(-1L,new FocusDeclaration(packageRef,"","","managed-snapshot-unknown"));
        var snapshot=registry.declarationSnapshot();
        String gap="managed-snapshot-unknown";
        if (snapshot.complete() && companion!=null && view!=null) {
            try {
                var identity=Reference.parse(packageRef);
                var pack=snapshot.entries().get(identity.skillId());
                var audience=new SkillsKnowledgeContributor(configDir,view.name(),0);
                if (pack!=null && pack.version()==identity.version() && pack.status().retrievable()
                        && audience.audienceMatches(pack.audience()) && snapshot.availableBodies().contains(pack.skillId())
                        && "package".equals(SceneSkillCatalog.kind(pack))) {
                    boolean required=true;
                    for (String condition:pack.preconditions()) if (condition.startsWith("requires:")) {
                        String ref=condition.substring("requires:".length());
                        var dep=Reference.parse(ref); var entry=snapshot.entries().get(dep.skillId());
                        if (ref.equals(packageRef) || entry==null || entry.version()!=dep.version()
                                || !entry.status().retrievable() || !audience.audienceMatches(entry.audience())
                                || !snapshot.availableBodies().contains(entry.skillId())) { required=false; break; }
                    }
                    var focuses=pack.preconditions().stream().filter(v->v.startsWith("focus:")).toList();
                    if (required && focuses.equals(java.util.List.of("focus:environment@v1")))
                        return new ManagedFocusEligibility(snapshot.revision(),new FocusDeclaration(packageRef,"environment","v1",""));
                }
                gap="managed-declaration-withdrawn-or-invalid";
            } catch (RuntimeException malformed) { gap="managed-declaration-invalid"; }
        }
        return new ManagedFocusEligibility(snapshot.revision(),new FocusDeclaration(packageRef,"","",gap));
    }

    @Override
    public Resolution resolve(UUID companion, String exactRef, View consumerView) {
        if (companion == null) return Resolution.refused(exactRef, "companion-missing");
        if (consumerView == null) return Resolution.refused(exactRef, "consumer-view-missing");
        final Reference requested;
        try {
            requested = Reference.parse(exactRef);
        } catch (IllegalArgumentException ex) {
            return Resolution.refused(exactRef, "invalid-reference");
        }
        try {
            var registry = SkillRegistry.open(configDir.resolve("skills.jsonl"));
            var entry = registry.get(requested.skillId()).orElse(null);
            if (entry == null) return Resolution.refused(exactRef, "skill-missing");
            if (entry.version() != requested.version()) return Resolution.refused(exactRef, "version-mismatch");
            if (!entry.status().retrievable()) return Resolution.refused(exactRef, "not-retrievable");
            // Reuse the original audience rules, including rejection of unknown tokens.
            if (!new SkillsKnowledgeContributor(configDir, consumerView.name(), 0).audienceMatches(entry.audience()))
                return Resolution.refused(exactRef, "audience-mismatch");
            String body = SkillBodyStore.read(configDir, entry.bodyRef());
            if (body == null || body.isBlank()) return Resolution.refused(exactRef, "body-missing-or-invalid");
            if (!LearnedLocationBinding.problem(body).isEmpty())
                return Resolution.refused(exactRef, "location-binding-required");
            return new Resolution(exactRef, body, "");
        } catch (IOException | RuntimeException ex) {
            return Resolution.refused(exactRef, "registry-read-failed");
        }
    }
}
