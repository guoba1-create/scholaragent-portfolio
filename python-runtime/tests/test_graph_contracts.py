import ast
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1] / "src" / "langgraph_runtime"


class GraphContractTest(unittest.TestCase):

    def test_evidence_workflow_has_fixed_query_prepare_topology(self):
        source = (ROOT / "workflow_graph.py").read_text(encoding="utf-8")
        ast.parse(source)

        self.assertIn('graph.add_node("query_prepare", query_prepare_node)', source)
        self.assertNotIn('graph.add_node("plan", plan_node)', source)
        self.assertIn('"claim_verification": "claim_critic"', source)
        self.assertIn('"max_replan": 1', source)
        self.assertIn('"search_query": fallback_query', source)
        self.assertNotIn("execution_mode", source)
        self.assertNotIn("awaiting_confirmation", source)
        self.assertNotIn('step_type="AGENT"', source)
        self.assertNotIn("EvidenceRetrievalAgent", source)
        self.assertNotIn("ClaimCriticAgent", source)

    def test_deep_research_has_one_specialist_and_parent_merge(self):
        source = (ROOT / "graph.py").read_text(encoding="utf-8")
        ast.parse(source)

        self.assertIn('DEEP_RESEARCH_EXTERNAL_AGENT = "external_scholar_scout_react"', source)
        self.assertIn('external_results.extend(_normalize_external_results(child_external))', source)
        self.assertIn('return bool(evaluation.get("external_required"))', source)
        self.assertNotIn("deep_handoff_library_node", source)


if __name__ == "__main__":
    unittest.main()
