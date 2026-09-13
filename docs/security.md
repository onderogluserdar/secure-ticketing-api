# Security

Authentication, authorization and the controls in place, plus the limitations that come with an
assignment-sized deployment. The access-control matrix below is the canonical authorization
reference.

For **how** the system is structured see [architecture.md](architecture.md); for **why** each security
choice was made, including alternatives and production evolution, see [decisions.md](decisions.md).

**Scope.** This covers application-level controls only. TLS termination, network policy, WAF, secret
management and host hardening are deployment concerns and are deliberately out of scope here.

## Authentication

### Registration and password storage

Self-registration is public and always creates a `CUSTOMER`. The role is never read from the request
body, so a caller cannot register themselves as an organizer or administrator; elevated roles are
assigned out of band.

Passwords are hashed with **BCrypt at cost 12**, benchmarked rather than copied from a default. The
plaintext is never stored, logged or returned, and no published API schema contains a password hash —
asserted by a test against the generated OpenAPI document.

Email addresses are canonicalized on construction, trimmed and lowercased, with a unique constraint
enforcing one account per address, so `Alice@Example.com` and `alice@example.com` collide as intended.
A concurrent duplicate registration loses the constraint race and becomes a 409, not a 500.

### Login and user enumeration

Login verifies the password, records `lastLoginAt`, and returns an access/refresh token pair.

An unknown email address and a wrong password produce an identical 401, with the same
`INVALID_CREDENTIALS` code and message. That alone is not enough, because a fast rejection for unknown
addresses is still an enumeration oracle, so login performs **a BCrypt verification even when no such
user exists** against a fixed comparison hash, making both paths cost comparable work. This narrows
the timing channel rather than closing it completely.

### Token design

Two token types, both HS256-signed JWTs, each carrying a `typ` claim that names its own kind:

| | Access token | Refresh token |
|---|---|---|
| `typ` | `access` | `refresh` |
| Lifetime | 15 minutes | 7 days |
| `sub` | user id | user id |
| `roles` | the caller's roles | *absent* |
| Accepted by | the resource server | the refresh endpoint only |

The separation is enforced by **two distinct `JwtDecoder` beans**, each with a validator rejecting a
token whose `typ` does not match. The resource server is wired explicitly to the access-token decoder,
so bean selection is never implicit. A refresh token presented as a bearer credential is rejected by
the filter chain before any controller runs.

Roles travel in the access token, which is what makes authorization stateless — no database read per
request — at the cost of a role change not taking effect until the token expires.

### Refresh

Refresh takes only a valid refresh token. The user is **reloaded from the database** and roles
re-read, so a changed role takes effect on the next refresh rather than being carried forward. Refresh
tokens are stateless and not rotated; see [limitations](#known-limitations).

## Authorization

Authorization is deliberately **two layers**, because there are two different questions:

1. **Coarse role policy** — may this *kind* of caller reach this operation at all? Centralized in
   `SecurityConfig` request matchers, so the whole policy is readable in one file.
2. **Resource ownership** — may *this* caller act on *this* resource? Checked in the service layer,
   because it needs the loaded entity.

A request can clear the role check and still be refused by ownership. That is the IDOR control
([AD-6](decisions.md#ad-6-explicit-roles-plus-service-level-ownership)).

### Access-control matrix

| # | Operation | Anon | CUSTOMER | ORGANIZER | ADMIN | Scope / ownership |
|---|---|:--:|:--:|:--:|:--:|---|
| 1 | `POST /api/auth/register` | ● | ● | ● | ● | Always creates a CUSTOMER; role is never client-supplied |
| 2 | `POST /api/auth/login` | ● | ● | ● | ● | Rate limited per client address |
| 3 | `POST /api/auth/refresh` | ●† | ●† | ●† | ●† | Requires a valid `typ=refresh` token; roles reloaded from the database |
| 4 | `GET /api/events/public` | ● | ● | ● | ● | Published events only |
| 5 | `POST /api/events` | ✗401 | ✗403 | ● | ● | `ownerId` comes from the principal, never from the request body |
| 6 | `GET /api/events` | ✗401 | ✗403 | ○ | ●‡ | ORGANIZER: own events only; a foreign `ownerId` filter is refused. ADMIN: all events, optionally filtered by any `ownerId` |
| 7 | `PUT /api/events/{id}` | ✗401 | ✗403 | ○ | ● | Non-owning organizer → `EVENT_ACCESS_DENIED` |
| 8 | `POST /api/events/{id}/publish` | ✗401 | ✗403 | ○ | ● | Non-owning organizer → `EVENT_ACCESS_DENIED` |
| 9 | `POST /api/events/{eventId}/reservations` | ✗401 | ● | ✗403 | ● | `userId` comes from the principal; `Idempotency-Key` required |
| 10 | `POST /api/reservations/{id}/confirm` | ✗401 | ○ | ✗403 | ● | Non-owning customer → `RESERVATION_ACCESS_DENIED` |
| 11 | `POST /api/reservations/{id}/cancel` | ✗401 | ○ | ✗403 | ● | Non-owning customer → `RESERVATION_ACCESS_DENIED` |

| Path | Anon | CUSTOMER | ORGANIZER | ADMIN | Notes |
|---|:--:|:--:|:--:|:--:|---|
| `GET /actuator/health` | ● | ● | ● | ● | Overall status and liveness/readiness group names; no health components or details |
| `GET /actuator/info` | ✗401 | ✗403 | ✗403 | ● | Build metadata; `build.time` excluded |
| `GET /actuator/metrics`, `/actuator/metrics/**` | ✗401 | ✗403 | ✗403 | ● | Diagnostic endpoint |
| Any other Actuator endpoint | ✗401 | **404** | **404** | **404** | Never mapped, so any authenticated caller gets 404 |
| `/v3/api-docs/**`, `/swagger-ui/**`, `/swagger-ui.html` | ● | ● | ● | ● | Public so the contract is readable without a token |
| Any unmatched path | ✗401 | 404 | 404 | 404 | `anyRequest().authenticated()`; security rejects before routing |

**Legend.** ● allowed by the authorization layer · ○ allowed **for own resources only**, enforced in
the service layer · ✗401 unauthenticated · ✗403 authenticated but role not permitted · † the
authorization layer is open, but the request must still carry a valid refresh token · ‡ ADMIN sees
every owner's events, including unpublished drafts.

**ADMIN is explicitly authorized as a superuser; this is not role inheritance.** Resource creation
always assigns ownership to the authenticated principal, so ADMIN may manage other users' existing
resources but cannot create an event or reservation on behalf of another user.

**Roles are additive and there is no hierarchy.** A user holds a `Set<Role>`, and ORGANIZER does not
inherit CUSTOMER: an organizer cannot reserve seats, and a customer cannot create events. A user who
needs both capabilities must hold both roles.

**Ownership is a service-level check** and can still return 403 after the coarse role check succeeds.

**409 responses are not authorization failures.** Insufficient capacity, an unpublished event, an
already-published event, an illegal state transition and an idempotency-key conflict are business
outcomes that occur *after* authorization has succeeded.

## Threat controls

**Brute force and credential stuffing.** A token-bucket limiter on `POST /api/auth/login`, keyed by
client address, allowing 5 attempts per minute. Rejections return 429 with a computed `Retry-After`
and are not error-logged, so the limiter cannot become a log-amplification vector. BCrypt cost 12
makes each guess expensive independently of the limiter.

**Broken object-level authorization (IDOR).** Event update and publish, and reservation confirm and
cancel, load the target resource and enforce owner-or-admin semantics before acting, so guessing
another customer's reservation id yields 403 rather than the reservation. Owner listing has no target
entity to load, so it instead restricts the requested `ownerId` filter to the caller's own id unless
the caller is ADMIN. Covered by dedicated tests.

**Privilege escalation.** For protected API requests, roles are read only from the validated access
JWT. Refresh accepts no client-supplied roles and reloads them from the database. No endpoint accepts
roles from a request field or an arbitrary header, self-registration cannot select a role, there is no
hierarchy to exploit, and no endpoint accepts an `ownerId` or `userId` for resource creation.

**Token misuse.** Type confusion between access and refresh tokens is blocked by separate decoders
with `typ` validators; tampered, unsigned and expired tokens fail standard validation. The long-lived
refresh token is accepted at exactly one endpoint.

**Duplicate submission and replay.** Reservation creation requires an `Idempotency-Key` scoped per
principal: a retry with the same payload replays the stored result, and the same key with a different
payload is a 409. A database unique constraint, not application logic, arbitrates concurrent
duplicates.

**Overselling.** The event row is locked before the active-seat total is computed, so concurrent
buyers cannot both read a stale total and both be admitted. Proven under real contention — see
[architecture.md](architecture.md).

**User enumeration and timing.** Identical 401 responses, plus a BCrypt verification on the
unknown-user path so both cost comparable work.

**Sensitive information in errors.** Unexpected failures return a generic problem document with no
exception type, message, SQL or stack frame, plus an `errorId` matching the server-side ERROR entry. A
regression test asserts a deliberately triggered database failure leaks neither the SQL nor the
constraint name.

**Sensitive data in logs and audit.** Passwords, hashes, tokens, `Authorization` headers and raw
request bodies are never logged or audited. Expected outcomes — validation failures, 401s, 403s, 409s,
rate-limit rejections — produce no application ERROR entries.

**Secret handling.** The signing secret and database credentials come from the environment. Only the
JWT secret is validated at startup — it must be present and at least 32 bytes, and the token lifetimes
must be positive; database credentials have no application-level validation and fail at connection
time if wrong. `.env` is gitignored; `.env.example` holds only placeholders.

## Audit trail

Eight actions are recorded: login success and failure, event created, updated and published, and
reservation created, confirmed and cancelled. Each row stores actor id, action, resource type and id,
client address, user agent and timestamp — never credentials, tokens or request payloads. The user
agent is metadata only; it is attacker-controlled and never drives a decision.

Audit writes join the business transaction, so a rolled-back operation leaves no row claiming success
and a committed one always has its record. Failed-login auditing uses `REQUIRES_NEW` to survive the
rollback it describes. The consequence is that the audit store sits on the critical path: an audit
failure fails the business operation rather than letting it proceed un-audited
([AD-13](decisions.md#ad-13-audit-records-are-atomic-with-the-business-change)).

## Known limitations

Stated plainly, because an assignment-sized deployment makes trade-offs a production system would not.

- **No token revocation.** Access tokens are valid until they expire; there is no denylist. A role
  change or logout does not invalidate an already-issued access token. The 15-minute lifetime is what
  bounds the exposure.
- **Symmetric signing.** HS256 means every verifier holds the key that mints tokens. Acceptable for
  one service; not for multiple verifiers.
- **Refresh tokens are not rotated** and have no reuse detection, so a stolen refresh token is usable
  until it expires.
- **Rate limiting is per instance and keyed by address.** Replicas multiply the effective allowance,
  and NAT or shared egress makes address keying imprecise in both directions.
- **No MFA or adaptive account protection.** The limiter provides friction, not identity assurance.
- **Business ADMIN doubles as operational authority** for `/actuator/info` and `/actuator/metrics`.
  Domain administration and service operation are not the same role in a real system.
- **API documentation is public**, which is a deliberate reviewer convenience rather than a
  production posture.
- **No TLS in the application.** Tokens and credentials must be protected by TLS terminated upstream.
