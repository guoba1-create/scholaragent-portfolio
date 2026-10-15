import unittest

from langgraph_runtime.llm_client import (
    _compile_prompt,
    _estimate_tokens,
    begin_llm_budget,
    end_llm_budget,
)


class LlmContextBudgetTests(unittest.TestCase):
    def test_hard_budget_and_citation_identity_are_preserved(self):
        audits = []
        token = begin_llm_budget(
            "wf-budget", {"maxPromptTokens": 512}, audits, default_max_tokens=2048
        )
        try:
            system, user, audit = _compile_prompt(
                "Return structured JSON and preserve citation ids.",
                ("long context " * 1600) + "\n[E1] evidence one\n[E2] evidence two",
            )
        finally:
            end_llm_budget(token)

        self.assertLessEqual(_estimate_tokens(system) + _estimate_tokens(user), 520)
        self.assertIn("E1", user)
        self.assertIn("E2", user)
        self.assertTrue(audit["compressed"])
        self.assertEqual(audit["maxPromptTokens"], 512)

    def test_legacy_character_budget_is_converted_to_tokens(self):
        audits = []
        token = begin_llm_budget(
            "wf-legacy", {"maxPromptChars": 4096}, audits, default_max_tokens=4096
        )
        try:
            _, _, audit = _compile_prompt("policy", "query")
        finally:
            end_llm_budget(token)
        self.assertEqual(audit["maxPromptTokens"], 1024)


if __name__ == "__main__":
    unittest.main()
