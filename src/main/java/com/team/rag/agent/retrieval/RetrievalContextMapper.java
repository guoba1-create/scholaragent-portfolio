package com.team.rag.agent.retrieval;

import com.team.rag.agent.context.EvidenceReference;
import com.team.rag.entity.RetrievalHit;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Converts retrieval hits into immutable citation identities for prompts. */
@Component
public class RetrievalContextMapper {

    public List<EvidenceReference> toEvidenceReferences(RetrievalContext context) {
        if (context == null || context.hits == null) {
            return List.of();
        }
        List<EvidenceReference> result = new ArrayList<>();
        int index = 1;
        for (RetrievalHit hit : context.hits) {
            if (hit == null) {
                continue;
            }
            EvidenceReference ref = new EvidenceReference();
            ref.citationId = "E" + index++;
            ref.sourceType = "LOCAL_DOCUMENT";
            ref.documentId = hit.documentId;
            ref.chunkId = firstNonBlank(hit.chunkId,
                    hit.chunkIndex == null ? null : String.valueOf(hit.chunkIndex));
            ref.title = firstNonBlank(hit.title, hit.source, "Untitled document");
            ref.section = firstNonBlank(hit.sectionPath, hit.section);
            ref.pageNumber = firstNonNull(hit.pageNumber, hit.pageStart);
            ref.chunkIndex = hit.chunkIndex;
            ref.quote = hit.content;
            ref.score = hit.score;
            result.add(ref);
        }
        return result;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private <T> T firstNonNull(T first, T second) {
        return first != null ? first : second;
    }
}
