# Agent Collaboration Rules

## Delegation preference

The user explicitly authorizes the main agent to use subagents while working in this repository.

- Prefer delegating simple, low-priority, read-only, and independently verifiable side tasks to `v4_flash_worker`.
- Good examples include repository scans, reference searches, configuration summaries, test-gap inventories, log triage, and independent risk reviews.
- Keep urgent blockers, architecture decisions, integration work, code edits, and final verification with the main agent or an appropriate `worker`.
- Do not delegate work when the main agent's immediate next step depends on its result; handle critical-path work locally.
- Do not duplicate the same work between the main agent and a subagent.
- Give every delegated task a narrow scope and a concrete expected output.
- Continue meaningful, non-overlapping work while a subagent runs instead of waiting unnecessarily.

## Transparency

- Before starting a subagent, briefly state which agent is being used and what it will do.
- In the final response, summarize which subagents were used, their assigned tasks, and how their results affected the outcome.
- If no subagent was useful for a task, it is acceptable not to start one.
