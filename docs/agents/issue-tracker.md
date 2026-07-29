# Issue tracker: Local Markdown

**Source of truth** for work items is local Markdown under `.scratch/`, not
GitHub Issues. The GitHub remote is for code backup, Releases, and CI; do not
create agent tickets as GitHub issues unless a human explicitly chooses that
channel for a one-off.

## Conventions

- One feature per directory: `.scratch/<feature-slug>/`
- The spec is `.scratch/<feature-slug>/spec.md`
- Implementation issues are one file per ticket at `.scratch/<feature-slug>/issues/<NN>-<slug>.md`, numbered from `01`
- Triage state is recorded as a `Status:` line near the top of each issue/spec file
- Comments append under a `## Comments` heading

## When a skill says "publish to the issue tracker"

Create or update files under `.scratch/<feature-slug>/`.

## Labels

See [`triage-labels.md`](./triage-labels.md). Those strings are for the `Status:` line in
Markdown tickets. They are **not** automatically mirrored as GitHub labels.

## Domain docs

Read glossary and ADRs before writing tickets: [`domain.md`](./domain.md).

## Pull requests

Code review and merge to `master` happen on **GitHub Pull Requests** (not by
default as a silent push only to the LAN `origin`). See
[`pull-requests.md`](./pull-requests.md) and root [`CONTRIBUTING.md`](../../CONTRIBUTING.md).

Use `.github/pull_request_template.md` and link the relevant
`.scratch/.../issues/NN-....md` path in the PR summary.
