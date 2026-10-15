import unittest

from langgraph_runtime.profiles import (
    CATALOG_VERSION,
    DEEP_RESEARCH_PROFILE,
    EXTERNAL_SCOUT_PROFILE,
    get_profile,
    list_profiles,
)


class ProfileCatalogTest(unittest.TestCase):

    def test_catalog_has_only_two_non_overlapping_agents(self):
        profiles = list_profiles()
        self.assertEqual("execution-catalog-v3", CATALOG_VERSION)
        self.assertEqual(
            {DEEP_RESEARCH_PROFILE, EXTERNAL_SCOUT_PROFILE},
            {profile.profile_id for profile in profiles},
        )

    def test_only_external_scout_can_search_the_internet(self):
        deep = get_profile(DEEP_RESEARCH_PROFILE)
        scout = get_profile(EXTERNAL_SCOUT_PROFILE)
        self.assertIsNotNone(deep)
        self.assertIsNotNone(scout)
        self.assertFalse({"search_papers", "web_search"} & deep.allowed_tools)
        self.assertEqual({"search_papers", "web_search", "query_rewrite"}, scout.allowed_tools)
        self.assertEqual({EXTERNAL_SCOUT_PROFILE}, deep.handoff_targets)
        self.assertFalse(scout.handoff_targets)

    def test_deprecated_task_profiles_are_not_agents(self):
        for legacy in (
            "paper_qa_react",
            "claim_verification_react",
            "related_work_react",
            "library_curator_react",
            "monitor_critic_react",
        ):
            self.assertIsNone(get_profile(legacy))


if __name__ == "__main__":
    unittest.main()
