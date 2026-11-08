# OpenSpec workflow

Use `openspec/config.yaml` and the `spec-driven` schema for the shared project.
Keep current capability requirements under `openspec/specs/`; propose changes under
`openspec/changes/` with proposal, design, spec deltas and tasks.

## Local setup

Use Node 24 LTS, or a compatible Node version meeting OpenSpec and its dependencies'
engine requirements. The checked CLI version is 1.16.1.

```sh
npm install -g @fission-ai/openspec@1.16.1
openspec init --tools codex
openspec list
openspec validate --all --strict --no-interactive
```

Open a new terminal if the npm global command directory was just added to PATH.
The Codex integration lives in `.agents/skills/`; initialization preserves the
existing shared configuration. Regenerate these vendor files with OpenSpec rather
than hand-editing them. See the [official setup documentation](https://github.com/Fission-AI/OpenSpec/blob/main/docs-lab/start/setup.md).

## Working on a change

Start with `openspec-propose` to prepare artifacts, or `openspec-update-change` to
revise an existing proposal. Use `openspec-apply-change` only after reviewing the
artifacts and satisfying the project gates in [tdd.md](tdd.md).

OpenSpec does not replace the named specialist roles in `.codex/agents/`: test
designer, fresh baseline verifier, feature-owner approval, implementer, fresh
candidate verifier, and a separate security reviewer for authorization/AI changes.
Never mark a task complete merely because its artifact files exist.

For the Maintainer frontend, run `npm run verify` from `src/frontend/maintainer`.
The current [report-browser baseline](../openspec/specs/maintainer-report-browser/spec.md)
documents the existing isolated frontend, its DTOs and QR-01 through QR-08.
The [complete-maintainer-role change](../openspec/changes/complete-maintainer-role/proposal.md)
contains the remaining role requirements, design, unchecked tasks and MT-01 through
MT-26 in verification.md. The old Maintainer workflow specifications are removed;
historical logs/commits remain evidence, not active requirement dependencies.
Coordinate shared checklist decisions with their owner.

Record actual commands, revisions, agent handoffs, findings and unavailable
measurements under `logs/`. Keep application code and tests under `src/`.
