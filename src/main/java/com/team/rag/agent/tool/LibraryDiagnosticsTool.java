package com.team.rag.agent.tool;

import com.team.rag.service.DocumentLibraryCatalogService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Diagnoses local knowledge-space availability without creating another Agent. */
@Component
public class LibraryDiagnosticsTool implements ScholarAgentTool<LibraryDiagnosticsTool.Input, LibraryDiagnosticsTool.Output> {

    private final DocumentLibraryCatalogService catalogService;

    public LibraryDiagnosticsTool(DocumentLibraryCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @Override
    public String name() {
        return "diagnose_library";
    }

    @Override
    public String description() {
        return "Diagnose whether the current knowledge space is bound, contains indexed documents, and can be searched.";
    }

    @Override
    public ToolSchema inputSchema() {
        ToolSchema schema = new ToolSchema("Library diagnostics input");
        schema.addProperty("researchGroupId", ToolSchema.PropertySchema.string(
                "Knowledge-space id. Defaults to the server-provided workflow context."));
        return schema;
    }

    @Override
    public ToolSchema outputSchema() {
        ToolSchema schema = new ToolSchema("Library diagnostics output");
        schema.addProperty("status", ToolSchema.PropertySchema.string("READY, EMPTY, UNBOUND or DEGRADED."));
        schema.addProperty("paperCount", ToolSchema.PropertySchema.integer("Number of document manifests."));
        schema.addProperty("likelyCause", ToolSchema.PropertySchema.string("Most likely cause of the current state."));
        schema.addProperty("suggestions", ToolSchema.PropertySchema.array("Safe remediation suggestions."));
        schema.addProperty("warnings", ToolSchema.PropertySchema.array("Catalog warnings."));
        return schema;
    }

    @Override
    public ToolResult<Output> execute(Input input, ToolContext context) {
        long startedAt = System.currentTimeMillis();
        String requested = input == null ? null : input.researchGroupId;
        String contextGroup = context == null ? null : context.getResearchGroupId();
        String researchGroupId = firstNonBlank(requested, contextGroup);

        Output output = new Output();
        output.researchGroupId = researchGroupId;
        if (researchGroupId == null) {
            output.status = "UNBOUND";
            output.paperCount = 0;
            output.likelyCause = "No knowledge space is bound to the current request.";
            output.suggestions.add("Bind the authenticated user to a knowledge space before retrieval.");
            return ToolResult.success(output, System.currentTimeMillis() - startedAt);
        }

        try {
            DocumentLibraryCatalogService.CatalogResult catalog = catalogService.list(researchGroupId, 10, false);
            output.paperCount = catalog.total;
            output.summary = catalog.summary;
            output.warnings.addAll(catalog.warnings);
            if (catalog.total == 0) {
                output.status = "EMPTY";
                output.likelyCause = "The knowledge space has no completed document manifests.";
                output.suggestions.add("Upload and finish indexing at least one paper in this knowledge space.");
                output.suggestions.add("Verify that the external user is mapped to the intended knowledge space.");
            } else {
                output.status = "READY";
                output.likelyCause = "The document catalog is available; inspect retrieval diagnostics if search is still empty.";
                output.suggestions.add("Run retrieval diagnostics with the same query and knowledge space.");
            }
            return ToolResult.success(output, System.currentTimeMillis() - startedAt);
        } catch (RuntimeException exception) {
            output.status = "DEGRADED";
            output.likelyCause = "The document catalog could not be read: " + exception.getMessage();
            output.suggestions.add("Check manifest storage and document-indexing health.");
            return ToolResult.success(output, System.currentTimeMillis() - startedAt);
        }
    }

    @Override
    public Class<Input> inputType() {
        return Input.class;
    }

    @Override
    public ToolType type() {
        return ToolType.ANALYSIS;
    }

    @Override
    public int priority() {
        return 7;
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second == null || second.isBlank() ? null : second.trim();
    }

    public static class Input {
        public String researchGroupId;
    }

    public static class Output {
        public String researchGroupId;
        public String status;
        public int paperCount;
        public String summary;
        public String likelyCause;
        public List<String> suggestions = new ArrayList<>();
        public List<String> warnings = new ArrayList<>();
    }
}
