# Architectural decisions

Why the system is built the way it is — a reference, not a narrative. **All decisions are accepted
unless explicitly stated otherwise.** Decision IDs are stable and are not renumbered when records are
regrouped.

Each record gives its context, the decision, the alternatives considered and the consequences.
*Production evolution* appears where a decision leaves a real limitation, and *Evidence* where a test
proves something the code does not make obvious. Short decisions are short.

[architecture.md](architecture.md) explains **how** it works, [security.md](security.md) covers
security mechanics and the access-control matrix, and the OpenAPI document is the generated API
reference.

| ID | Category | Decision |
|---|---|---|
| [AD-1](#ad-1-feature-oriented-layered-monolith) | Architecture | Feature-oriented layered monolith |
| [AD-2](#ad-2-jpa-entities-carry-domain-behaviour) | Architecture | JPA entities carry domain behaviour |
| [AD-3](#ad-3-rfc-9457-problem-details-with-a-stable-error-code) | API design | RFC 9457 problem details with a stable error code |
| [AD-4](#ad-4-code-first-openapi-verified-at-runtime) | API design | Code-first OpenAPI, verified at runtime |
| [AD-5](#ad-5-stateless-hs256-jwt-with-strict-accessrefresh-separation) | Security | Stateless HS256 JWT, strict access/refresh separation |
| [AD-6](#ad-6-explicit-roles-plus-service-level-ownership) | Security | Explicit roles plus service-level ownership |
| [AD-7](#ad-7-bcrypt-cost-12-and-timing-enumeration-mitigation) | Security | BCrypt cost 12 and timing-enumeration mitigation |
| [AD-8](#ad-8-in-memory-token-bucket-rate-limiting-on-login) | Security | In-memory token-bucket rate limiting on login |
| [AD-9](#ad-9-pessimistic-event-lock-with-a-derived-active-seat-sum) | Concurrency | Pessimistic event lock, derived active-seat sum |
| [AD-10](#ad-10-global-event--reservation-lock-ordering) | Concurrency | Global event → reservation lock ordering |
| [AD-21](#ad-21-postgresql-as-the-transactional-system-of-record) | Persistence | PostgreSQL as the transactional system of record |
| [AD-20](#ad-20-flyway-owns-the-schema-hibernate-only-validates) | Persistence | Flyway owns the schema, Hibernate only validates |
| [AD-11](#ad-11-the-unique-constraint-is-the-idempotency-authority) | Idempotency | The unique constraint is the idempotency authority |
| [AD-12](#ad-12-atomic-claim-reserve-complete-with-lazy-expiry) | Idempotency | Atomic claim, reserve, complete with lazy expiry |
| [AD-13](#ad-13-audit-records-are-atomic-with-the-business-change) | Transactions | Audit records are atomic with the business change |
| [AD-14](#ad-14-three-channels-logs-audit-and-metrics) | Observability | Three channels: logs, audit and metrics |
| [AD-15](#ad-15-an-error-id-instead-of-request-correlation) | Observability | An error id instead of request correlation |
| [AD-16](#ad-16-minimal-actuator-exposure) | Observability | Minimal Actuator exposure |
| [AD-17](#ad-17-h2-for-breadth-postgresql-for-truth) | Testing | H2 for breadth, PostgreSQL for truth |
| [AD-18](#ad-18-real-concurrency-and-mutation-probes) | Testing | Real concurrency and mutation probes |
| [AD-19](#ad-19-formatting-and-static-analysis-fail-the-build) | Code quality | Formatting and static analysis fail the build |

---

## Architecture

### AD-1. Feature-oriented layered monolith

**Context.** One deployable, eleven operations, one consistency-critical invariant.

**Decision.** Package by capability (`event`, `reservation`, `idempotency`, `auth`), controller →
service → repository within each, so everything about a capability sits together.

**Alternatives considered.** Hexagonal architecture with inbound and outbound ports, which would add
an entity, a mapper and a port per repository with no second implementation behind any of them.

**Consequences.** Infrastructure is not swappable behind ports and package boundaries rest on review
rather than tooling. The payoff is that the interesting engineering sits in the concurrency and
idempotency logic rather than in a framework of our own making.

### AD-2. JPA entities carry domain behaviour

**Context.** Capacity limits, publish transitions and the reservation state machine must not be
bypassable.

**Decision.** Entities are the domain model — factory methods for construction, behaviour for state
changes — so invariants live with the data they protect and an illegal state cannot be constructed.
Entities are never serialized; responses are explicit DTOs.

**Alternatives considered.** A separate domain model with mappers, which triples the file count for a
schema that maps cleanly; anemic entities, which make the rules bypassable.

**Consequences.** Persistence annotations sit inside the domain type, coupling the model to JPA.
Acceptable while the schema and the domain agree.

---

## API design

### AD-3. RFC 9457 problem details with a stable error code

**Context.** Clients must branch on failures without parsing prose.

**Decision.** Every 4xx and 5xx is a `ProblemDetail` carrying a stable `code`. Business conflicts are
409, an authenticated non-owner is 403, unexpected failures are sanitized to a generic 500. The same
shape covers controller failures, MVC rejections and security-filter failures — the last of which
`@RestControllerAdvice` never sees, so those are written explicitly.

**Alternatives considered.** Ad-hoc error JSON per endpoint, unusable for a client; 404 instead of
403 to hide existence, not chosen because confirming existence to an authenticated caller is
acceptable here.

**Consequences.** One more enum to maintain, and a 403 confirms the resource exists.

### AD-4. Code-first OpenAPI, verified at runtime

**Context.** A hand-written specification drifts from the handlers it describes.

**Decision.** Generate the document with springdoc and assert it in a test that actually fetches
`/v3/api-docs`, because springdoc reflects over live MVC mappings and an incompatible version fails at
request time rather than at dependency resolution. The required `Idempotency-Key` flag is *inferred*
from `@RequestHeader`, so the document cannot outlive the enforcement.

**Alternatives considered.** A hand-maintained specification, which can contradict the code it
claims to describe.

**Consequences.** Annotations live in controllers, and the document is only as good as the code that
produces it.

**Production evolution.** The documentation endpoints are public so a reviewer needs no token; an
internal API would restrict them by network or authentication policy.

---

## Security

### AD-5. Stateless HS256 JWT with strict access/refresh separation

**Context.** Authentication without shared server state.

**Decision.** Short access and long refresh tokens, each carrying a `typ` claim, validated by **two
separate decoders** that reject the wrong type — a refresh token must never authorize an API call, and
that boundary belongs in the validation path rather than a hand-written check. CSRF is disabled:
bearer-only, no cookie or session, so no ambient credential exists to abuse.

**Alternatives considered.** Opaque tokens with introspection, which would make every request depend
on an online authorization-server call; one decoder checking the claim in code, which makes the
boundary depend on remembering to check.

**Consequences.** A token cannot be revoked before it expires, and HS256 means every verifier holds
the signing secret. Short access-token lifetimes are what bound that exposure.

**Production evolution.** Asymmetric signing with key rotation so verifiers cannot mint tokens, and
refresh rotation with reuse detection. Immediate access-token invalidation would need server-side
state, trading away the statelessness this design exists for.

**Evidence.** `TokenTypeBoundaryTest` proves the boundary at the decoder level and
`SecurityFilterChainTest` proves the real filter chain rejects a refresh token used as a bearer
credential.

### AD-6. Explicit roles plus service-level ownership

**Context.** Two different questions: may this *kind* of user call this operation, and may *this* user
touch *this* resource.

**Decision.** Authorization is deliberately two layers: coarse role policy centralized in
`SecurityConfig` request matchers, and resource ownership checked in the service layer where the
entity is loaded. Roles are a `Set<Role>` with **no hierarchy** and ADMIN is granted explicitly in
every rule, so effective permissions are something you read rather than infer. The complete matrix is
in [security.md](security.md).

**Alternatives considered.** A role hierarchy, which hides the effective permission set behind
inheritance. Declarative ownership expressions, which cannot work as request matchers because
ownership needs the loaded entity. Method authorization, which would describe the same coarse policy
in both request matchers and annotations, and two descriptions of one rule drift apart.

**Consequences.** ADMIN appears in every rule and ownership logic is imperative. The two layers answer
different questions, so a request can clear the role check and still be refused by ownership.

### AD-7. BCrypt cost 12 and timing-enumeration mitigation

**Context.** Password storage, and a login endpoint that must not reveal which addresses are
registered.

**Decision.** BCrypt at cost 12, benchmarked rather than guessed. Login hashes a password **even when
no such user exists**, because a fast rejection for unknown addresses is an enumeration oracle and
identical error responses do not close it if the timing differs.

**Alternatives considered.** Returning early when no user matches — exactly the oracle being closed.

**Consequences.** CPU is spent on every attempt including failures, which is part of why login is rate
limited ([AD-8](#ad-8-in-memory-token-bucket-rate-limiting-on-login)). The mitigation removes the
obvious timing signal without claiming the channel is fully closed.

**Production evolution.** A memory-hard algorithm with parameters benchmarked on the deployment
hardware, and MFA as an independent control against credential compromise.

**Evidence.** `AuthServiceTest` verifies a hash is computed for a non-existent address, so the
mitigation cannot be silently removed.

### AD-8. In-memory token-bucket rate limiting on login

**Context.** Login is the credential-stuffing target.

**Decision.** A Bucket4j token bucket per client address, on `POST /api/auth/login` only, so normal
bursts pass while sustained attempts are capped. Rejections return 429 with a computed `Retry-After`
and produce no application error log, since logging each one would make the limiter a
log-amplification vector.

**Alternatives considered.** A fixed window, which either punishes normal bursts or allows double the
rate across a boundary.

**Consequences.** State is per instance, so replicas multiply the effective limit, and keying on
client address is imprecise under NAT or shared egress. This is one layer of friction, not identity
assurance.

**Production evolution.** Enforcement with shared state across instances, keyed on more than an
address.

---

## Concurrency

### AD-9. Pessimistic event lock with a derived active-seat sum

**Context.** The central requirement: concurrent reservations must never oversell an event.

**Decision.** Lock the event row `PESSIMISTIC_WRITE`, **then** sum non-cancelled reservation seats,
then decide. The lock makes read-decide-write serial per event. There is no counter column — a derived
sum cannot drift from the rows it summarizes — and `PENDING` reservations consume inventory, so a seat
being checked out is not sold twice.

**Alternatives considered.** Optimistic `@Version` with retry, which can amplify contention through
retries on exactly the hot events that matter. A denormalized counter or a trigger, which introduces a
second source of truth that can drift. Serializable isolation, a heavier global change to solve one
row's problem.

**Consequences.** Writers serialize per event and an abandoned hold occupies a seat until cancelled.
Contention is confined to one row, which is the trade accepted for correctness.

**Production evolution.** Stale holds would need to expire on a timeout, and for genuinely hot events
the control belongs in admission — throttling how many buyers reach the inventory at once, so the
database row lock does not become the primary queue.

**Evidence.** `NoOversellPostgresTest` — twenty concurrent threads against capacity ten produce
exactly ten reservations, and removing the lock makes it fail.

### AD-10. Global event → reservation lock ordering

**Context.** Cancelling changes what the active-seat sum returns, so it needs the inventory lock and
the reservation's own row lock.

**Decision.** Any operation needing both takes the **event first, then the reservation**. Confirm
takes only the reservation lock, because it changes no inventory. Deadlock between transactions taking
locks in opposite orders is avoided by permitting exactly one order.

**Alternatives considered.** Locking in whatever order the code happens to read, the classic deadlock.
Locking the event everywhere for uniformity, defensive over-locking that serializes confirmations for
no reason.

**Consequences.** The rule is a convention review must uphold, since the compiler cannot check it. It
needs revisiting only if confirm ever gains inventory effects, at which point it must take the event
lock too.

**Evidence.** `ReservationService.cancel` acquires the Event lock before the Reservation lock.

---

## Persistence

### AD-21. PostgreSQL as the transactional system of record

**Context.** Reservation correctness depends on related data, atomic state changes across more than
one row, and an aggregate computed over reservation rows.

**Decision.** PostgreSQL is the transactional system of record. The workload needs foreign keys,
unique and check constraints, atomic multi-row transitions, aggregate seat accounting and row-level
locking — all native to a relational engine, which makes the relational model a natural fit.
PostgreSQL is the concrete engine selected; this is not a claim that it is uniquely capable.

**Alternatives considered.** MySQL would support the same model; multi-database portability is
explicitly not a requirement. Document or key-value stores can achieve correctness through conditional
writes or transactions, but would move relationship and consistency handling into application code
without simplifying this workload.

**Consequences.** The implementation deliberately relies on PostgreSQL transactional and locking
behaviour rather than avoiding engine specifics, so the critical behaviour is verified against the
real engine instead of a substitute.

**Evidence.** The foreign keys, unique and check constraints in `V1__baseline_schema.sql`;
`NoOversellPostgresTest` for row locking under contention; `PublicDiscoveryPostgresTest` for query
behaviour on the real engine.

### AD-20. Flyway owns the schema, Hibernate only validates

**Context.** Schema drift between environments is a silent, expensive failure.

**Decision.** Flyway migrations are the single authority, with `ddl-auto: validate`, so Hibernate
never mutates the schema and a mismatch fails at startup rather than at the first request. Structural
invariants are also database constraints, including uniqueness of the canonicalized email — the
application lowercases and trims the address and a plain unique constraint enforces it.

**Alternatives considered.** `ddl-auto: update`, which makes the schema an emergent property of entity
code; application-only validation, which cannot hold if application logic is bypassed.

**Consequences.** Migrations are hand-written, and structural invariants exist in both the entity and
the schema. That duplication is deliberate: the entity produces a good error message, the constraint
guarantees the outcome.

**Evidence.** `AuthServiceTest` proves a concurrent duplicate registration returns 409, not 500 — the
constraint decides the race and the application translates it.

---

## Idempotency

### AD-11. The unique constraint is the idempotency authority

**Context.** A retried reservation request must not create a second reservation.

**Decision.** `Idempotency-Key` is required, scoped by a unique constraint on
`(user_id, endpoint, idempotency_key)` where `endpoint` is the **logical template**. The concrete
`eventId` and every field affecting the outcome go into a canonical request hash, not the scope. The
database already arbitrates races correctly, and storing the template rather than the concrete URI
makes one key reused against two events a conflict rather than two silently independent scopes.

**Alternatives considered.** A distributed lock or external cache, a second mechanism to get right when
the database already provides the guarantee. Hashing the raw request body, which would make
insignificant formatting differences look like different requests.

**Consequences.** Hashing semantics rather than bytes means the hash must be updated whenever a request
field that affects the outcome is added.

### AD-12. Atomic claim, reserve, complete with lazy expiry

**Context.** A claim committed before the reservation leaves a crash window where the key exists but
the reservation does not.

**Decision.** One transaction claims the key, creates the reservation, stores the replayable response
(status **and** body) and completes the key. The uncommitted unique-index entry is what makes a
concurrent duplicate wait and then lose, so the claim needs no separate commit. A loser rolls back
fully, then reads the winner's record in a fresh transaction. A business rejection rolls the claim back
too, because caching a capacity rejection would block a legitimate retry within the idempotency
window. Expired keys are deleted before re-claiming, since an expired row still occupies the unique
scope.

**Alternatives considered.** Committing an in-progress claim first, which creates exactly the orphan
window. Storing only a response hash, which cannot be replayed.

**Consequences.** A longer transaction, and expiry cleanup happens on the request path.

**Evidence.** `IdempotencyConcurrencyPostgresTest` — twenty simultaneous duplicates create exactly one
reservation and every caller receives the same result; bypassing the guard makes it fail.

---

## Transactions

### AD-13. Audit records are atomic with the business change

**Context.** An audit trail that can silently miss events is not an accountability control.

**Decision.** Audit writes join the caller's transaction, so a mutation and its record commit together
and an audit failure fails the business operation. No window exists where a reservation has nobody
recorded as creating it, and no record claims something that was rolled back. Failed-login auditing
uses `REQUIRES_NEW` to survive the rollback it describes.

**Alternatives considered.** Always a separate transaction, which can record successes that never
committed. Asynchronous best-effort auditing, which reintroduces the silent gap this design exists to
remove.

**Consequences.** Compliance is chosen over availability: the audit store sits on the critical path, so
the system fails closed rather than proceeding un-audited. For an accountability control that is the
defensible default.

**Production evolution.** Keeping atomicity while taking the audit sink off the critical path would
need an outbox — write the event in the same transaction, relay it asynchronously.

**Evidence.** `AuditTrailTest` proves a rejected operation leaves no row and that no credentials are
recorded.

---

## Observability

### AD-14. Three channels: logs, audit and metrics

**Context.** "Who did what", "how often" and "what broke" are different questions.

**Decision.** Audit is per-event and attributable. Metrics are four aggregate counters carrying **no
tags** — untagged counters cannot answer "who", which is audit's job, and tagging by user or event
would risk a cardinality explosion. Creation is counted **after** commit, because counters cannot be
rolled back. Business and security outcomes are not duplicated into application logs; unexpected
technical failures are ERROR-logged with a stack trace.

**Alternatives considered.** Annotation- or aspect-driven instrumentation, which hides where a metric
is emitted.

**Consequences.** Instrumentation is explicit in application code rather than woven in, which keeps it
greppable while leaving domain entities unaware observability exists.

**Evidence.** `ReservationMetricsTest` proves a replay is not counted as a creation, nor is a
reservation whose transaction rolled back.

### AD-15. An error id instead of request correlation

**Context.** An unexpected failure returns a deliberately sanitized body, leaving the caller nothing
to quote.

**Decision.** The catch-all generates a UUID, returns it as an `errorId` extension, and logs the same
value with the stack trace. Nothing is propagated further and expected 4xx responses do not carry one;
calling it `correlationId` would promise propagation the system does not do. Console logging stays
human-readable, because there is no aggregation backend here to consume structured output.

**Alternatives considered.** A request-wide correlation filter with MDC — a platform concern built
into one application for a need this narrow.

**Consequences.** No cross-request or cross-service correlation; the id links exactly one response to
exactly one logged exception.

### AD-16. Minimal Actuator exposure

**Context.** Operational endpoints are a reconnaissance surface.

**Decision.** Only `health`, `info` and `metrics` are exposed; everything else is never mapped, so it
cannot later be misconfigured into exposure. Health is anonymous and reports the overall status and
liveness/readiness group names with no component details — a probe must work without credentials, but
an anonymous caller must not learn that a database exists or whether it is reachable. Info and metrics
require ADMIN, enforced in this application's own filter chain rather than by framework defaults.

**Alternatives considered.** Exposing everything and protecting it by role, rejected because unmapped
is stronger than protected.

**Consequences.** Less diagnostic surface, and the business ADMIN role doubles as operational
authority.

**Production evolution.** Domain administration and service operation are not the same role. This
deployment deliberately does not introduce a separate management interface or its own identity, so
they share one role here.

---

## Testing

### AD-17. H2 for breadth, PostgreSQL for truth

**Context.** Fast feedback across the suite, against real database semantics where it matters.

**Decision.** Most integration tests run on H2 in PostgreSQL compatibility mode; a small set runs on
real PostgreSQL via Testcontainers for concurrency, locking and query portability — deliberately
**not** a duplicate suite. Compatibility mode is not equivalence: a discovery query with nullable
filters passed on H2 and failed on PostgreSQL with `could not determine data type of parameter`.

**Alternatives considered.** Everything on Testcontainers, too slow for the whole suite; everything on
H2, disproved by the bug above.

**Consequences.** Two engines to keep green, and Docker is required for the full build.

**Evidence.** `PublicDiscoveryPostgresTest` is the regression test for that failure, and it only fails
on the real engine.

### AD-18. Real concurrency and mutation probes

**Context.** A concurrency test that never actually contends, and a test that passes with its
mechanism removed, both prove nothing.

**Decision.** For the critical correctness mechanisms — locking, idempotency, ownership and error
sanitization — a test is kept only if **removing the mechanism it guards makes it fail**. Concurrency
tests release workers from a barrier and size the connection pool above the worker count, because
thread count is not transaction concurrency: an early no-oversell test passed with the lock removed,
since a small pool serialized the workers and the pool, not the lock, was enforcing the invariant.

**Alternatives considered.** Trusting a green build, which the episode above disproves; targeting a
coverage percentage, which rewards trivial tests.

**Consequences.** Probing is manual effort that leaves no artefact in the repository, and coverage is
reported without being gated.

---

## Code quality

### AD-19. Formatting and static analysis fail the build

**Context.** Style debates and mechanically detectable bugs both waste review attention.

**Decision.** `mvn verify` runs Spotless and SpotBugs with FindSecBugs and **fails** on any violation.
Suppressions are scoped to a class — where possible a single method — each with a written
justification, because a global suppression silences the detector everywhere.

**Alternatives considered.** Advisory-only reports, rejected because warnings nobody must fix
accumulate.

**Consequences.** A formatting slip fails the build, and every false positive costs a justified
exclusion rather than a shrug.

**Evidence.** The gates caught an unsafe hash comparison in idempotency-key matching, and forced
review of a CRLF log-injection finding that was suppressed only after the interpolated value was shown
to be a locally generated UUID. `config/spotbugs/exclude.xml` documents every suppression.
