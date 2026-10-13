# Working on Nkap Agent

Conventions for anyone writing code in this repository. They are those of Nkap, adapted
to a repository that has no modules yet.

## Language

**Everything committed to this repository is in English**: code, comments, Javadoc,
commit messages, documentation, issues and pull requests. Conversations may happen in
another language; what is committed does not.

## Commits

- **Nothing in this repository mentions an assistant or a tool that helped write it.** Not
  in a commit message, a trailer, a file, a pull request title or description, an issue or
  a comment. A pull request description is as public and as permanent as a commit, and it
  is the place this rule is most often missed.
- Sign off every commit: `git commit -s`. The sign-off is checked on every pull request.
- Subject in the imperative, under 72 characters, then a blank line, then a body that
  explains **why** — the diff already says what.
- One concern per commit.

## Branches and pull requests

**One issue, one branch, one pull request.** Nothing is committed straight to the default
branch, not even a one-line fix.

- Branch name: `<type>/<issue>-<slug>`, where type is `feat`, `fix`, `docs` or `chore` —
  for example `chore/1-scaffold`. When the work has no issue, use `<type>/<slug>` and say
  why in the pull request.
- The pull request description says what changed and why, and closes the issue with
  `Closes #N`.
- The build must be green before merging. A red pull request is unfinished work.
- Merge with a rebase or a merge commit, not a squash. Commit messages are written to be
  read a year later, and squashing throws them away.
- Delete the branch after merging.

## Secrets

No secret, key or token is ever committed, and no placeholder that looks like one.
Local configuration lives in `.env`, which is ignored.

## Build

```bash
mvn -B clean verify
```

## Tests

Test names are sentences stating the rule being defended, in `@DisplayName`.

## Local notes

`PLAN.md` and `STATE.md` are working notes. They are ignored and never committed.
