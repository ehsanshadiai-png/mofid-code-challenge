# Concurrent Balance Service

A Spring Boot service that credits, debits and transfers money between accounts correctly under heavy
concurrency, with per-`transactionId` idempotency.

**Stack:** Java 21 · Spring Boot 4.1 · Spring Data JPA (Hibernate 7) · H2 (in-memory) · Maven

```bash
./mvnw test              # build + run all tests (no external dependencies needed)
./mvnw spring-boot:run   # start the REST API on :8080
```

Requires a JDK 21 or newer. Nothing else: the database is embedded.

---

## TL;DR of the design

| Concern | Mechanism |
|---|---|
| Thread safety / lost updates | Every operation is **one database transaction** that locks the account rows it changes with `SELECT … FOR UPDATE` |
| Isolation level | `READ COMMITTED` — sufficient because every row we decide on is locked (reasoning [below](#why-read-committed-is-enough)) |
| Independent accounts | Row-level locks: operations on A and B never wait on each other |
| Transfer atomicity | Both balance updates, the transaction record and the ledger entries commit or roll back together |
| Deadlocks | Locks are always acquired in **ascending account-id order** |
| Idempotency | `transactionId` is the **primary key** of `balance_transaction`; outcome (COMPLETED / REJECTED) is stored and returned to every retry |
| Concurrent duplicates | Same request → serialized by the account lock, later copies see the committed row and return its outcome. Different request with same id → primary key lets one win |
| Negative balance | Checked in code under the lock, **and** a `CHECK (balance >= 0)` constraint in the schema |

---

## Architecture

```
HTTP (optional)          BalanceController ──► ApiExceptionHandler (RFC 9457 problem details)
                                 │
Service interface        BalanceService  (the interface required by the challenge)
                                 │
Facade (no transaction)  DefaultBalanceService
                           · validates input (amount > 0, ids, same-account transfer)
                           · runs the unit of work; on a primary-key race, returns the winner's outcome
                                 │
Unit of work             TransactionalBalanceOperations   @Transactional(READ_COMMITTED)
                           1. lock account rows (FOR UPDATE, sorted by id)
                           2. look up transactionId → return stored outcome if it exists
                           3. apply balance change, or record REJECTED
                           4. INSERT balance_transaction (PK = transactionId) + ledger_entry rows
                                 │
Persistence              AccountRepository · BalanceTransactionRepository · LedgerEntryRepository
                                 │
Database (H2)            account · balance_transaction · ledger_entry   (schema.sql)
```

```
src/main/java/com/example/mofid/balance
├── domain/       Account, BalanceTransaction, LedgerEntry, OperationRequest, enums
├── exception/    BalanceException hierarchy (business failures)
├── repository/   Spring Data JPA repositories (incl. the FOR UPDATE query)
├── service/      BalanceService, DefaultBalanceService, TransactionalBalanceOperations, AccountService
└── web/          REST controller, DTOs, exception → HTTP mapping
src/main/resources/schema.sql   the schema, including constraints (Hibernate only validates it)
```

### Data model

- **`account`** — `id`, `balance BIGINT` (minor units, never floating point), `CHECK (balance >= 0)`.
- **`balance_transaction`** — one row per `transactionId` (primary key): type, accounts, amount, status
  `COMPLETED` or `REJECTED`. This is the idempotency record.
- **`ledger_entry`** — one signed row per balance movement (a transfer writes −amount and +amount).
  Gives an audit trail and a checkable invariant: `balance = opening balance + Σ ledger entries`,
  which the concurrency tests assert for every account.

### Why the facade / unit-of-work split?

When a transaction fails on the primary key, the persistence context and the database transaction are
both dead: you cannot "catch and continue" inside them. `DefaultBalanceService` is deliberately **not**
transactional, so it sees the rollback and can open a *new* transaction to read what the winner committed.
It also avoids the Spring self-invocation pitfall (a `@Transactional` method called from the same bean
bypasses the proxy).

### Transaction propagation rules

| Where | Propagation | Why |
|---|---|---|
| `DefaultBalanceService` (class) | `NEVER` | The service owns its transaction boundaries. With the default `REQUIRED`, a call from inside a caller's transaction would **silently join it**: row locks held until the caller commits, lock ordering no longer guaranteed, and after a primary-key failure the lookup of the winner's outcome would run in the same rollback-only transaction. `NEVER` turns that misuse into an immediate `IllegalTransactionStateException`. |
| `TransactionalBalanceOperations` | `REQUIRED` (default) | Called only from the non-transactional facade, so each call starts a **new** transaction; the repositories join it. |
| `AccountRepository.findByIdForUpdate` | `MANDATORY` | A row lock is only meaningful inside a transaction. Hibernate already rejects it outside one (`No active transaction`); `MANDATORY` states the rule in the code and fails before any SQL is sent. |

Verified in `TransactionPropagationTest`. Without `NEVER`, the "call inside a caller's transaction" test
fails because the credit silently goes ahead.

The integration tests are deliberately **not** `@Transactional`: every service call would join the test's
transaction, nothing would really commit, and worker threads could not see the data. Tables are cleaned
with `DELETE` instead.

---

## Concurrency

### How thread safety is guaranteed

There is no in-JVM locking at all. All shared state lives in the database and every operation runs in
a single transaction that takes an exclusive row lock on the accounts it will modify:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select a from Account a where a.id = :id")
Optional<Account> findByIdForUpdate(String id);   // SELECT ... FOR UPDATE
```

The lock is held until commit/rollback, so *read balance → check funds → write balance* is atomic
with respect to every other writer of that row.

### What happens to concurrent operations on one account

They queue on the row lock and execute one at a time, each seeing the balance committed by the previous
one. With balance 1,000 and two concurrent `debit(A, 700)`: the first gets the lock, debits, commits
(300); the second then acquires the lock, reads 300, and is rejected with `InsufficientFundsException`.

Readers (`getBalance`) do not take locks and are not blocked (H2 and PostgreSQL are MVCC); they see the
last committed balance.

Operations on **different** accounts lock different rows and run fully in parallel — this is verified by
`ConcurrencyTest.lockOnOneAccountDoesNotBlockAnotherAccount`, which holds A's lock open and shows that an
operation on B completes immediately while one on A waits.

The lock wait is bounded (`LOCK_TIMEOUT=10000` ms). On timeout the transaction rolls back completely and
the caller gets `PessimisticLockingFailureException` (HTTP 503); retrying with the same `transactionId`
is safe because nothing was recorded.

### Why pessimistic locking (and not the alternatives)

| Option | Why not chosen |
|---|---|
| **Optimistic locking** (`@Version` + retry) | Correct, but under contention on a hot account most attempts fail and retry, wasting work and producing unpredictable latency. Balance updates are short, write-heavy and contend on exactly the same row, which is the case pessimistic locking fits. |
| **`SERIALIZABLE` isolation** | Would also be correct, but aborts transactions with serialization failures that must be retried, and adds predicate-tracking overhead. We don't need it: our decisions are based only on rows we lock. |
| **Conditional UPDATE** (`SET balance = balance - ? WHERE id = ? AND balance >= ?`) | Excellent for a single debit (atomic, no explicit lock). Less clear for transfers + ledger + idempotency record in one unit; I preferred one uniform, explicit locking model. A good optimisation candidate. |
| **In-JVM locks** (`ConcurrentHashMap<id, Lock>`) | Only correct for a single instance; state lost on restart; does not survive horizontal scaling. The DB approach works unchanged with N service instances. |

### Why READ COMMITTED is enough

The isolation level only matters for anomalies the locks don't already prevent. Walking through them:

| Anomaly | Could it hurt us? | Why it doesn't |
|---|---|---|
| **Dirty read** | No | READ COMMITTED never exposes uncommitted data. |
| **Lost update** (two tx read 1,000, both write) | Yes, without locks | `FOR UPDATE` forces the second transaction to wait and then read the *committed* value. The test suite fails with 18 errors if the lock is removed (see [Testing](#testing)). |
| **Non-repeatable read** | No | We read each account row exactly once, and it is locked from that moment, so nobody can change it under us. |
| **Phantom read** | **Yes — this is the subtle one** | The idempotency check "does `transactionId` exist?" is a *predicate* read. Two transactions can both see "no row" and both proceed to insert. We do **not** rely on that SELECT for correctness: the `transactionId` **primary key** makes the second INSERT fail, whatever the isolation level. The SELECT is only the fast path. |
| **Write skew** | No | We never make a decision based on rows we haven't locked (e.g. "sum over accounts"). A transfer locks both accounts before reading either. |

The other thing READ COMMITTED gives us *positively*: each statement takes a fresh snapshot. A duplicate
request that waited for the account lock sees the `balance_transaction` row committed by the request it
waited for, and returns its outcome instead of hitting the primary key. (Under REPEATABLE READ that row would be
invisible — the design is still correct, it just takes the slower primary-key-violation path.)

---

## Idempotency

### How a transaction is prevented from executing twice

`transactionId` is the primary key of `balance_transaction`, inserted **in the same database
transaction** as the balance change. Either both commit or neither does, and the database can never
hold two rows with the same id. So a transaction's effect is applied **at most once**, guaranteed by
the database, not by application code.

Stored outcome, returned to every retry (the operation itself is never re-executed):

| Existing record | New request with same `transactionId` | Result |
|---|---|---|
| none | — | executed normally |
| `COMPLETED`, same operation | retry | returns success, no effect |
| `REJECTED` (insufficient funds), same operation | retry | throws `InsufficientFundsException` again — **even if funds have arrived since** |
| any, **different** type/accounts/amount | misuse | `TransactionIdConflictException` (HTTP 409) |

Decisions worth defending:

- **Rejections are recorded.** An idempotency key should yield the same answer every time. If a rejected
  debit could succeed on a later retry, a client retrying a timed-out request might debit money it had
  already been told was refused. To try again, the client uses a new `transactionId`. The `REJECTED` row
  is committed via `@Transactional(noRollbackFor = InsufficientFundsException.class)`; no balance has been
  touched at that point.
- **Validation errors and unknown accounts are *not* recorded.** They fail before any row is written,
  so the `transactionId` is not consumed (e.g. a credit that arrives before its account is opened can be
  retried successfully).
- **Payload mismatch is an error, not a silent success.** Returning "OK" for a different amount would hide
  a client bug in a financial system.

### What happens when the same transaction arrives concurrently

**Case 1 — genuine duplicates (same operation, so same accounts).** All copies try to lock the same account
row(s). One wins, executes, inserts the record and commits. The others wait on the lock; when each gets
it, its lookup (fresh READ COMMITTED snapshot) finds the committed record and returns its outcome. Every
copy returns the same result; the balance changes once. Tested for credit, debit, transfer and a rejected
debit with 32 simultaneous copies (`ConcurrencyTest.concurrentDuplicate*`).

**Case 2 — same id, different operation on different accounts.** No shared row lock, so they really race.
All pass the lookup, all try to INSERT the same primary key. The database blocks the later inserts until
the first commits, then fails them with a unique violation → the whole transaction (including its balance
change) rolls back → `DefaultBalanceService` opens a new transaction, reads the winner's record, and
answers `TransactionIdConflictException`. Exactly one is applied
(`ConcurrencyTest.sameTransactionIdOnDifferentAccountsIsAppliedExactlyOnce`).

---

## Transfer

### Atomicity

A transfer is one database transaction:

1. lock both accounts (sorted by id),
2. check the source has enough funds (else record `REJECTED`, touching no balance),
3. debit source, credit destination,
4. insert the transaction record and two ledger entries (−amount, +amount),
5. commit.

Any exception before commit — lock timeout, constraint violation, overflow, JVM crash, lost
connection — rolls back all of it. The amount debited and credited is the same `long` value by
construction. `BalanceServiceTest.failureAfterDebitingSourceRollsBackTheWholeTransfer` forces a failure
*after* the source has been debited in memory (destination balance near `Long.MAX_VALUE` so crediting it
overflows) and asserts neither account nor any record changed.

One trap I avoided: the business exception is excluded from rollback, so it must never be thrown after a
balance has been changed. The entity's own guard (`Account.debit`) therefore throws an
`IllegalStateException`, which *does* roll back.

### Same-account transfer: `transfer("A", "A", 100, "TX-100")`

**Rejected** with `SameAccountTransferException` (HTTP 400) before touching the database, and the
`transactionId` is not consumed. Rationale: it has no net effect, it almost certainly signals a client
bug, and recording a "successful" no-op would put meaningless entries in the ledger. (Technically it
would also be safe to execute: locking the same row twice in one transaction is re-entrant.)

### Is deadlock possible?

**Not between balance operations.** A deadlock needs a cycle of transactions each waiting for a lock
another holds. Every transaction acquires its account locks in ascending id order, so transfers A→B and
B→A both lock A first; the second simply waits. A cycle would require some transaction to hold a
higher id while waiting for a lower one, which the ordering rules out.

The other wait in the system is the `transactionId` primary key (Case 2 above). It can't close a cycle
either: a transaction only inserts *after* it holds all its account locks, and the transaction it waits
for is past that point too, doing only inserts that need no further locks (ledger entries have generated
ids; their foreign keys point at accounts it already holds exclusively).

This was verified, not just argued: removing the `.sorted()` call makes the opposing-transfer tests fail
with H2's `Deadlock detected` errors.

Remaining edge: should an external process lock accounts in a different order, the database's deadlock
detector aborts one victim (`CannotAcquireLockException` → HTTP 503), which rolls back cleanly and is safe
to retry.

---

## Validation

| Input | Behaviour |
|---|---|
| `amount <= 0` | `InvalidAmountException` (400) |
| blank / too long `accountId` or `transactionId` | `InvalidRequestException` (400) |
| unknown account (any side of a transfer) | `AccountNotFoundException` (404), nothing recorded |
| source == destination | `SameAccountTransferException` (400) |
| insufficient funds | `InsufficientFundsException` (422), outcome recorded as `REJECTED` |
| `transactionId` reused for a different operation | `TransactionIdConflictException` (409) |
| balance overflow on credit | `ArithmeticException` (`Math.addExact`), full rollback |

---

## Testing

82 tests, all against the real Spring context and database — no mocks, because the locking behaviour *is*
the thing under test.

| Class | What it covers |
|---|---|
| `BalanceServiceTest` | Spec examples, validation, unknown accounts, same-account transfer, failure mid-transfer rollback, DB `CHECK` constraint |
| `IdempotencyTest` | Repeated credit/debit/transfer applied once; rejected outcomes are stable; id reuse conflicts; failed requests don't consume ids |
| `ConcurrencyTest` | See below |
| `TransactionPropagationTest` | `NEVER` / `MANDATORY` transaction-boundary rules |
| `BalanceControllerTest` | REST flow and HTTP error mapping |

### Concurrency tests

- **Single account:** two-of-N competing debits (exactly one succeeds, repeated 10×); 1,000 mixed credits
  (+7) and debits (−3) with an exact expected final balance; 1,000 debits of 150 against 100,000 → exactly
  666 succeed, 334 rejected, final balance 100.
- **Concurrent duplicates:** 32 simultaneous copies of the same credit / debit / transfer / rejected debit.
- **Same id on 32 different accounts:** exactly one applied, the rest conflict.
- **Multiple accounts:** 2,000 random transfers among 10 accounts in both directions → total money
  conserved, no negative balance, every balance matches its ledger, no deadlock/timeout errors.
- **Opposing transfers:** A→B and B→A hammered concurrently (the textbook deadlock shape).
- **Isolation between accounts:** a held lock on A does not delay B.

**Raising the chance of races:** all tasks wait behind a single `CountDownLatch` start gate and are released
together; 32 threads contend for a 20-connection pool; the key scenarios are `@RepeatedTest`s; a hard
timeout turns a deadlock into a failure instead of a hung build.

**Do the tests actually detect bugs?** I checked by breaking the implementation on purpose:

| Mutation | Result |
|---|---|
| Replace `findByIdForUpdate` with plain `findById` (no lock) | 18 concurrency tests fail: overdrafts, wrong final balances, ledger mismatches |
| Remove lock ordering (`.sorted()`) | Transfer tests fail with `Deadlock detected` |

Interesting side result of the first mutation: the duplicate-request tests still passed without the row lock,
because the primary key alone still prevents double application. That is the defence-in-depth working as
intended.

---

## REST API (optional layer)

| Method & path | Body | Success |
|---|---|---|
| `POST /api/accounts` | `{"accountId":"A","openingBalance":1000}` | 201 |
| `GET /api/accounts/{id}/balance` | — | 200 `{"accountId":"A","balance":1000}` |
| `POST /api/accounts/{id}/credit` | `{"amount":100,"transactionId":"TX-1"}` | 200 + balance |
| `POST /api/accounts/{id}/debit` | `{"amount":100,"transactionId":"TX-2"}` | 200 + balance |
| `POST /api/transfers` | `{"sourceAccountId":"A","destinationAccountId":"B","amount":300,"transactionId":"TX-3"}` | 204 |

Account opening is outside `BalanceService` (which only moves money) in `AccountService`.

### Error responses (RFC 9457)

Errors use the standard *Problem Details for HTTP APIs* format ([RFC 9457](https://www.rfc-editor.org/rfc/rfc9457),
successor of RFC 7807) with `Content-Type: application/problem+json`, so every failure has the same
shape and clients need one error parser. Spring's `ProblemDetail` produces it; `ApiExceptionHandler`
extends `ResponseEntityExceptionHandler`, so framework errors (malformed JSON, a missing required field)
use the same format as domain errors.

```http
HTTP/1.1 422 Unprocessable Content
Content-Type: application/problem+json

{
  "title": "Insufficient funds",
  "status": 422,
  "detail": "Account 'A' has insufficient funds: balance 700, requested 5000",
  "instance": "/api/accounts/A/debit"
}
```

| Field | Meaning |
|---|---|
| `title` | Short summary of the kind of problem |
| `status` | The HTTP status, repeated in the body |
| `detail` | Explanation of this specific occurrence |
| `instance` | The request path that failed |
| `type` | URI identifying the problem kind; not set, so it defaults to `about:blank` and is omitted |

| Status | Title | Cause | Retry with the same `transactionId`? |
|---|---|---|---|
| 400 | Invalid request | `amount <= 0`, blank or too-long id, same-account transfer | No, fix the request |
| 400 | Bad Request (Spring's own) | Missing required field, malformed JSON | No, fix the request |
| 404 | Account not found | An account in the request does not exist | Yes, once the account exists (the id was not consumed) |
| 409 | Conflict | `transactionId` already used for a different operation, or account id already exists | No, use a new id |
| 422 | Insufficient funds | Not enough balance; the rejection is stored | No, it stays rejected; use a new id |
| 503 | Temporarily unavailable | Lock wait timed out or deadlock victim; nothing was recorded | Yes, safe to retry |

#### Examples

Captured from the running application (bodies pretty-printed):

`POST /api/accounts/A/debit` with `{"amount": 0, "transactionId": "TX-9"}` → **400**

```json
{
  "title": "Invalid request",
  "status": 400,
  "detail": "Amount must be greater than zero but was 0",
  "instance": "/api/accounts/A/debit"
}
```

`POST /api/accounts/A/debit` with `{"transactionId": "TX-9"}` (no `amount`) → **400**, generated by Spring

```json
{
  "title": "Bad Request",
  "status": 400,
  "detail": "Invalid request content.",
  "instance": "/api/accounts/A/debit"
}
```

`POST /api/transfers` with `{"sourceAccountId": "A", "destinationAccountId": "A", "amount": 1, "transactionId": "TX-8"}` → **400**

```json
{
  "title": "Invalid request",
  "status": 400,
  "detail": "Source and destination account must differ, both were 'A'",
  "instance": "/api/transfers"
}
```

`POST /api/accounts/missing/debit` with `{"amount": 10, "transactionId": "TX-9"}` → **404**

```json
{
  "title": "Account not found",
  "status": 404,
  "detail": "Account 'missing' does not exist",
  "instance": "/api/accounts/missing/debit"
}
```

`POST /api/accounts/A/credit` with `{"amount": 999, "transactionId": "TX-1"}`, after `TX-1` was used to credit 100 → **409**

```json
{
  "title": "Conflict",
  "status": 409,
  "detail": "Transaction id 'TX-1' was already used for a different operation",
  "instance": "/api/accounts/A/credit"
}
```

**503** when a lock wait times out (from `ApiExceptionHandler`; hard to trigger by hand):

```json
{
  "title": "Temporarily unavailable",
  "status": 503,
  "detail": "The account is busy, retry with the same transactionId",
  "instance": "/api/accounts/A/debit"
}
```

**Known gaps** (acceptable for the challenge, fix before production):

- **`type` is not set.** Clients must tell errors apart by `status` and `title`, but `title` is meant for
  humans and 409 covers two different problems. A stable URI per problem
  (e.g. `/problems/transaction-id-conflict`) would give clients something reliable to match on.
- **`detail` exposes the account balance** ("balance 700"). Without authentication, anyone could probe
  balances with oversized debits. A production API should return a generic message and keep the numbers
  in server logs.

---

## Technology choices

### Database (H2, in-memory)

1. **Why:** balance consistency is fundamentally a transactional problem. A database gives atomic
   multi-row commits, row locks, isolation levels, constraints and crash-safe durability that would
   otherwise have to be re-implemented (badly) with JVM locks. H2 keeps `./mvnw test` dependency-free.
2. **What it solves:** atomic transfers, lost updates (row locks), idempotency under races (primary key),
   invariant enforcement (`CHECK`), and correctness across multiple service instances.
3. **Trade-offs:**
   - Throughput on a *single hot account* is serialized by its row lock; each operation costs a DB round
     trip and a commit.
   - In-memory H2 is not durable and not the production engine. Locking semantics differ slightly between
     databases, so production should run the same test suite against PostgreSQL (e.g. Testcontainers).
     The design only uses standard behaviour (`FOR UPDATE`, unique constraints, READ COMMITTED).
   - The schema is managed by `schema.sql`; production would use Flyway/Liquibase migrations.

Not used: **Redis** (a distributed lock would add a second source of truth and lease-expiry hazards
for no gain over the DB's own locks), **Kafka** (async processing changes the API semantics — callers
couldn't get a synchronous answer), **Docker** (nothing external to run).

---

## Limitations and known trade-offs

- **Hot accounts** are processed strictly sequentially. Mitigations if needed: the conditional-UPDATE
  form, batching, or modelling balance as sub-accounts/buckets.
- **Idempotency records never expire.** Real systems keep them for a retention window (e.g. 30 days)
  and archive or purge them.
- **Lock timeouts are surfaced, not retried internally.** The caller retries with the same
  `transactionId`, which is safe. A bounded server-side retry on `PessimisticLockingFailureException`
  would be a small addition.
- **The balance returned by the REST credit/debit endpoints** is read after commit, so under concurrency
  it may already include later operations.
- **A retry does not get the original response body**, only success / the same error. Storing the
  response would allow returning it exactly.
- **No authentication, currencies or opening-balance ledger entry** (the opening balance is treated as
  the ledger's starting point).

## What was implemented / what I'd do next

**Implemented:** everything in the brief — credit, debit, transfer, balance query, concurrency safety,
idempotency (including concurrent duplicates), atomic transfers, deadlock avoidance, validation, the
required tests, plus a REST API and an audit ledger.

**Next, with more time:**
1. Run the suite against PostgreSQL via Testcontainers and add a Docker Compose setup.
2. Flyway migrations instead of `schema.sql`.
3. Bounded retry with jitter for lock timeouts; metrics for lock wait time and rejection rates
   (Micrometer).
4. Retention job for idempotency records.
5. A load test (Gatling/JMH) to measure throughput per hot account vs. spread across accounts, and try
   the conditional-UPDATE variant against it.
