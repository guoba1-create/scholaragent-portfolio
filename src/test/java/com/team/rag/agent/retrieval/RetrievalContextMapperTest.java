package com.team.rag.agent.retrieval;

import com.team.rag.agent.context.EvidenceReference;
import com.team.rag.entity.RetrievalHit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalContextMapperTest {

    @Test
    void createsStableOrderedCitationReferences() {
        RetrievalHit first = new RetrievalHit();
        first.documentId = "doc-1";
        first.chunkId = "chunk-4";
        first.title = "Structured RNN";
        first.sectionPath = "3 Method > 3.2 Transition Model";
        first.pageStart = 4;
        first.chunkIndex = 7;
        first.score = 0.91;
        first.content = "The model jointly represents spatial and temporal transitions.";

        RetrievalContext context = new RetrievalContext();
        context.hits = List.of(first);

        List<EvidenceReference> refs = new RetrievalContextMapper().toEvidenceReferences(context);

        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).citationId).isEqualTo("E1");
        assertThat(refs.get(0).documentId).isEqualTo("doc-1");
        assertThat(refs.get(0).chunkId).isEqualTo("chunk-4");
        assertThat(refs.get(0).section).isEqualTo("3 Method > 3.2 Transition Model");
        assertThat(refs.get(0).pageNumber).isEqualTo(4);
        assertThat(refs.get(0).quote).contains("spatial and temporal");
    }
}
