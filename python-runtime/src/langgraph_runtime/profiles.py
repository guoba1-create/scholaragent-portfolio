"""Authoritative Python Agent profile catalog.

The project intentionally exposes only two real Agents:

* ``deep_research_react`` coordinates a bounded Plan-Execute-Evaluate-Replan
  research loop over the local literature library.
* ``external_scholar_scout_react`` is a narrow child Agent used only through
  handoff when external academic evidence is required.

Claim verification, paper comparison, related work and citation support are
deterministic workflow templates. Library troubleshooting is a tool. Keeping
those concepts out of the Agent catalog prevents overlapping responsibilities.
"""

from dataclasses import dataclass, field


CATALOG_VERSION = "execution-catalog-v3"
DEEP_RESEARCH_PROFILE = "deep_research_react"
EXTERNAL_SCOUT_PROFILE = "external_scholar_scout_react"


@dataclass(frozen=True)
class AgentProfile:
    """Runtime capability and budget policy for one Agent."""

    profile_id: str
    name: str
    description: str
    role_prompt: str
    allowed_tools: set[str]
    allowed_actions: set[str]
    max_steps: int
    max_tool_calls: int
    max_estimated_tokens: int
    max_latency_ms: int
    handoff_targets: set[str] = field(default_factory=set)
    termination_conditions: list[str] = field(default_factory=list)
    quality_gate_rules: list[str] = field(default_factory=list)

    def is_tool_allowed(self, tool_name: str) -> bool:
        return tool_name in self.allowed_tools

    def is_action_allowed(self, action_type: str) -> bool:
        return action_type.upper() in self.allowed_actions

    def is_handoff_allowed(self, target_profile: str) -> bool:
        return target_profile in self.handoff_targets


PROFILES: dict[str, AgentProfile] = {
    DEEP_RESEARCH_PROFILE: AgentProfile(
        profile_id=DEEP_RESEARCH_PROFILE,
        name="DeepResearch Coordinator",
        description=(
            "Plans and executes local literature research, evaluates coverage, "
            "replans gaps and synthesizes the final report."
        ),
        role_prompt="""You are the DeepResearch coordinator.
Create a bounded research plan, inspect the local literature library, retrieve
and judge evidence, evaluate coverage, replan uncovered dimensions, and write
the final report. Prefer local literature. Use diagnose_library for local
library failures. When recent, SOTA, benchmark, survey or otherwise missing
external evidence is required, hand off a minimal search task to
external_scholar_scout_react. Never invent evidence or URLs.""",
        allowed_tools={
            "list_papers",
            "get_library_summary",
            "diagnose_library",
            "memory_recall",
            "query_rewrite",
            "retrieve_evidence",
            "read_document",
            "judge_evidence_quality",
        },
        allowed_actions={"TOOL", "HANDOFF", "ASK_USER", "FINISH"},
        handoff_targets={EXTERNAL_SCOUT_PROFILE},
        max_steps=12,
        max_tool_calls=12,
        max_estimated_tokens=24000,
        max_latency_ms=120000,
        termination_conditions=[
            "The research plan has sufficient evidence coverage.",
            "The replan or tool budget is exhausted and limitations are explicit.",
        ],
        quality_gate_rules=[
            "local_evidence_first",
            "citations_are_traceable",
            "unsupported_claims_are_disclosed",
        ],
    ),
    EXTERNAL_SCOUT_PROFILE: AgentProfile(
        profile_id=EXTERNAL_SCOUT_PROFILE,
        name="External Scholar Scout",
        description=(
            "Finds external papers and web evidence for a bounded gap delegated "
            "by DeepResearch; it never writes the final report."
        ),
        role_prompt="""You are an external academic search specialist.
Execute only the delegated search gap. Rewrite the query when useful, search
academic and web sources, and return structured results containing title,
year, venue, URL, snippet or abstract, and relevance reason. Do not synthesize
the parent Agent's final report and do not hand off again.""",
        allowed_tools={"query_rewrite", "search_papers", "web_search"},
        allowed_actions={"TOOL", "FINISH"},
        max_steps=5,
        max_tool_calls=6,
        max_estimated_tokens=6000,
        max_latency_ms=45000,
        termination_conditions=[
            "The delegated evidence gap has usable external results.",
            "No usable external result exists and the limitation is explicit.",
        ],
        quality_gate_rules=[
            "results_are_structured",
            "urls_are_tool_outputs",
            "no_final_report",
        ],
    ),
}


def get_profile(profile_id: str) -> AgentProfile | None:
    """Return a profile only for an exact, supported profile id."""

    return PROFILES.get(profile_id)


def list_profiles() -> list[AgentProfile]:
    """Return profiles in a deterministic order for health checks and tests."""

    return [PROFILES[key] for key in sorted(PROFILES)]
