package com.team.rag.agent.context;

import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class EvidenceIntegrityValidator {

    public ValidationResult validate(List<EvidenceReference> before, List<EvidenceReference> after) {
        Set<String> expected = identities(before);
        Set<String> actual = identities(after);
        Set<String> missing = new HashSet<>(expected);
        missing.removeAll(actual);
        Set<String> unexpected = new HashSet<>(actual);
        unexpected.removeAll(expected);
        return new ValidationResult(missing.isEmpty() && unexpected.isEmpty(), missing, unexpected);
    }

    private Set<String> identities(List<EvidenceReference> refs) {
        Set<String> result = new HashSet<>();
        if (refs == null) {
            return result;
        }
        for (EvidenceReference ref : refs) {
            if (ref != null) {
                result.add(ref.stableIdentity());
            }
        }
        return result;
    }

    public record ValidationResult(boolean valid, Set<String> missing, Set<String> unexpected) {
    }
}
