package com.dwinovo.numen.api;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Read one explicitly requested learned body through the existing capability port.
 * The learner owns storage and eligibility; the API owns no registry or cache.
 * Reading is neither adoption nor execution, and never follows references recursively.
 */
@FunctionalInterface
public interface LearnedSkillBodyResolver {
    enum View { EXECUTOR, PLANNER }

    /** Exact identity only: an omitted version must never silently mean latest. */
    record Reference(String skillId, int version) {
        private static final Pattern FORM = Pattern.compile("skill:([A-Za-z0-9][A-Za-z0-9_-]*)@v([1-9][0-9]*)");

        public Reference {
            if (skillId == null || !skillId.matches("[A-Za-z0-9][A-Za-z0-9_-]*") || version < 1)
                throw new IllegalArgumentException("invalid learned skill reference");
        }

        public static Reference parse(String value) {
            var match = FORM.matcher(value == null ? "" : value);
            if (!match.matches()) throw new IllegalArgumentException("expected skill:id@vN");
            try {
                return new Reference(match.group(1), Integer.parseInt(match.group(2)));
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("invalid learned skill version");
            }
        }

        public String exactRef() { return "skill:" + skillId + "@v" + version; }
    }

    /** A refusal carries no body; a read carries the exact requested identity. */
    record Resolution(String exactRef, String body, String gap) {
        public Resolution {
            exactRef = exactRef == null ? "" : exactRef;
            body = body == null ? "" : body;
            gap = gap == null ? "" : gap;
            if (!gap.isEmpty() && !body.isEmpty())
                throw new IllegalArgumentException("a refused read cannot carry a body");
        }

        public boolean available() { return gap.isEmpty() && !body.isBlank(); }

        public static Resolution refused(String ref, String reason) {
            return new Resolution(ref, "", reason);
        }
    }

    /** One explicitly selected package component; a read is not execution or adoption. */
    record PackageComponentResolution(String packageRef, String componentRef, String body, String availability, String gap) {
        public PackageComponentResolution {
            packageRef=packageRef==null?"":packageRef; componentRef=componentRef==null?"":componentRef;
            body=body==null?"":body; availability=availability==null?"":availability; gap=gap==null?"":gap;
            if (!gap.isEmpty() && !body.isEmpty()) throw new IllegalArgumentException("a refused read cannot carry a body");
        }
        public boolean available() { return gap.isEmpty() && !body.isBlank(); }
        public static PackageComponentResolution refused(String pack,String component,String reason) {
            return new PackageComponentResolution(pack,component,"","",reason);
        }
    }
    default PackageComponentResolution resolveComponent(UUID companion,String packageRef,String componentRef,View consumerView) {
        return PackageComponentResolution.refused(packageRef,componentRef,"package-component-unavailable");
    }
    /** Server-side declaration lookup, not a mount or execution credential. */
    record FocusDeclaration(String packageRef, String topic, String contractVersion, String gap) {
        public boolean available() {
            if (gap == null || !gap.isEmpty() || topic == null || topic.isBlank() || contractVersion == null || contractVersion.isBlank()) return false;
            try { Reference.parse(packageRef); return true; } catch (IllegalArgumentException ex) { return false; }
        }
    }
    default FocusDeclaration resolveFocusDeclaration(UUID companion,String packageRef,View view) {
        return new FocusDeclaration(packageRef,"","","focus-declaration-unavailable");
    }
    /** In-memory managed snapshot revision; -1 means unavailable, never a fresh disk read. */
    default long managedRevision() { return -1L; }
    /** Declaration and revision are from one immutable snapshot; unavailable defaults to UNKNOWN. */
    record ManagedFocusEligibility(long revision, FocusDeclaration declaration) { }
    default ManagedFocusEligibility managedFocusEligibility(UUID companion, String packageRef, View view) {
        return new ManagedFocusEligibility(-1L, new FocusDeclaration(packageRef, "", "", "managed-snapshot-unknown"));
    }
    Resolution resolve(UUID companion, String exactRef, View consumerView);
}
