# Commit Conventions

This project follows [Conventional Commits](https://www.conventionalcommits.org/). The history should
read as a short story of how the service was built, one reviewable decision at a time.

## Message format

```
<type>(<scope>): <subject>

<body: what changed and WHY, wrapped at 72 characters>

<footer: breaking changes, issue references, co-authors>
```

### Type

| Type | Use for |
|---|---|
| `feat` | New behaviour visible to a caller (an operation, an endpoint, a rule) |
| `fix` | Correcting wrong behaviour |
| `refactor` | Restructuring code with **no** behaviour change |
| `test` | Adding or changing tests only |
| `docs` | README, Javadoc, this file |
| `build` | Maven, dependencies, wrapper |
| `chore` | Repository housekeeping (ignore rules, tooling config) |
| `perf` | A change whose purpose is performance |

### Scope

Optional, one word naming the area: `domain`, `persistence`, `service`, `web`, `concurrency`,
`idempotency`, `schema`.

### Subject

- Imperative mood: "add row locking", not "added" or "adds".
  Test: *"If applied, this commit will ___"*.
- At most 72 characters, lowercase start, no trailing period.
- Say **what** changed, not which files: `lock accounts in id order` beats `update service class`.

### Body

The diff already shows *what*; the body explains **why**, and which alternative was rejected.
This matters most for concurrency and transaction changes, where the reason is not visible in the code:

```
fix(concurrency): lock transfer accounts in ascending id order

Locking source-then-destination let A→B and B→A transfers each hold one
row and wait for the other. Sorting the ids gives every transaction the
same lock order, so a wait cycle cannot form.

Verified by ConcurrencyTest.opposingTransfersBetweenTwoAccountsDoNotDeadlock,
which fails with "Deadlock detected" without the sort.
```

A body may be omitted when the subject says everything (`docs: fix typo in README`).

### Footer

- `BREAKING CHANGE: <description>` when an API or schema change breaks callers
  (or add `!` after the type: `feat(web)!: ...`).
- `Refs: #12` / `Closes: #12` for issues.
- `Co-Authored-By: Name <email>` for pair programming or AI-assisted work.

## What makes a good commit

1. **One logical change per commit.** A reviewer should be able to explain a commit in one sentence.
   If the subject needs "and", consider splitting it.
2. **Every commit builds and passes `./mvnw test`.** This keeps `git bisect` usable and means any
   commit can be checked out safely.
3. **Tests travel with the behaviour they verify.** A feature and its tests belong in the same commit;
   a `test:` commit adds coverage for behaviour that already exists.
4. **Keep refactors separate from behaviour changes.** A reviewer should never have to hunt for a
   logic change hidden inside a rename or a reformat.
5. **Review the staged diff before committing:** `git diff --staged`. Stage deliberately
   (`git add <paths>` or `git add -p`), never blindly with `git add .`.
6. **Never commit** build output (`target/`), IDE files (`.idea/`), secrets or credentials, or
   confidential material (the challenge brief in `.doc/` is ignored for this reason).
7. **Don't rewrite shared history.** Amend or rebase only commits that have not been pushed.

## Examples from this repository

```
build: set up Maven project with Spring Boot, JPA and H2
feat(persistence): add schema, entities and repositories
feat(service): implement balance operations with row locks and idempotency
test(concurrency): verify correctness under concurrent load
feat(web): expose balance operations as a REST API
docs: document design decisions and commit conventions
```
