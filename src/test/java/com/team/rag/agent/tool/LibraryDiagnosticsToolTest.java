package com.team.rag.agent.tool;

import com.team.rag.service.DocumentLibraryCatalogService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LibraryDiagnosticsToolTest {

    @Test
    void reportsUnboundWithoutCallingCatalog() {
        LibraryDiagnosticsTool tool = new LibraryDiagnosticsTool(mock(DocumentLibraryCatalogService.class));

        ToolResult<LibraryDiagnosticsTool.Output> result = tool.execute(new LibraryDiagnosticsTool.Input(), null);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData().status).isEqualTo("UNBOUND");
        assertThat(result.getData().suggestions).isNotEmpty();
    }

    @Test
    void reportsEmptyKnowledgeSpaceWithActionableSuggestions() {
        DocumentLibraryCatalogService catalogService = mock(DocumentLibraryCatalogService.class);
        DocumentLibraryCatalogService.CatalogResult catalog = new DocumentLibraryCatalogService.CatalogResult();
        catalog.researchGroupId = "space-a";
        catalog.total = 0;
        catalog.summary = "No documents.";
        when(catalogService.list("space-a", 10, false)).thenReturn(catalog);
        LibraryDiagnosticsTool tool = new LibraryDiagnosticsTool(catalogService);
        LibraryDiagnosticsTool.Input input = new LibraryDiagnosticsTool.Input();
        input.researchGroupId = "space-a";

        LibraryDiagnosticsTool.Output output = tool.execute(input, null).getData();

        assertThat(output.status).isEqualTo("EMPTY");
        assertThat(output.paperCount).isZero();
        assertThat(output.suggestions).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void reportsReadyWhenCatalogContainsDocuments() {
        DocumentLibraryCatalogService catalogService = mock(DocumentLibraryCatalogService.class);
        DocumentLibraryCatalogService.CatalogResult catalog = new DocumentLibraryCatalogService.CatalogResult();
        catalog.researchGroupId = "space-a";
        catalog.total = 3;
        catalog.summary = "Found 3 documents.";
        when(catalogService.list("space-a", 10, false)).thenReturn(catalog);
        LibraryDiagnosticsTool tool = new LibraryDiagnosticsTool(catalogService);
        LibraryDiagnosticsTool.Input input = new LibraryDiagnosticsTool.Input();
        input.researchGroupId = "space-a";

        LibraryDiagnosticsTool.Output output = tool.execute(input, null).getData();

        assertThat(output.status).isEqualTo("READY");
        assertThat(output.paperCount).isEqualTo(3);
        assertThat(output.suggestions).contains("Run retrieval diagnostics with the same query and knowledge space.");
    }
}
