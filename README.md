# Secure Ticketing & Reservation API

A Spring Boot 4 REST API for event ticketing: organizers publish events, customers reserve seats, and
the system guarantees that an event is never oversold — even under concurrent requests.

## Core requirements

The implementation satisfies the case's three critical requirements: no overselling under concurrent
reservations, idempotent reservation creation, and JWT-based authentication with role-based
authorization and ownership checks.

How each is achieved:

- **No overselling** — a pessimistic row lock on the event is taken before the active-seat total is
  computed, so two concurrent buyers cannot both read a stale total. Verified against real PostgreSQL
  under genuine contention. See [architecture.md](docs/architecture.md).
- **Idempotent reservations** — `Idempotency-Key` is required on reservation creation. A retry with the
  same payload replays the original result; the same key with a different payload is a conflict. A
  database unique constraint, not application logic, arbitrates concurrent duplicates.
- **Authentication and authorization** — stateless HS256 JWTs with separate access and refresh tokens,
  coarse role rules in one filter chain, and resource ownership checked in the service layer. See
  [security.md](docs/security.md).

## Quick start

**Prerequisites:** Java 21, Docker (for PostgreSQL and the Testcontainers-based tests). Maven is not
required — the project ships the Maven wrapper.

**1. Create your environment file.** Do this first: Docker Compose reads `.env` and refuses to start
without `DB_PASSWORD`.

```bash
cp .env.example .env
```

Then edit `.env` and set both empty values:

```properties
DB_PASSWORD=<choose any local password>
JWT_SECRET=<at least 32 bytes; generate one below>
```

```bash
openssl rand -base64 48   # paste the output as JWT_SECRET
```

**2. Start PostgreSQL.**

```bash
docker compose up -d postgres
```

**3. Run the application** with the `dev` profile, which seeds one user per role.

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

**4. Confirm it is up.** Health is public.

```bash
curl http://localhost:8080/actuator/health
```

### Run the full stack with Docker

Instead of steps 2 and 3, the application and PostgreSQL can run together. The same `.env` supplies
the credentials, so create it first as in step 1.

```bash
docker compose up --build
```

Compose waits for PostgreSQL to report healthy, then starts the application on
<http://localhost:8080> with the `dev` profile. `docker compose down` stops both.

## Seed users

Created **only** under the `dev` profile, for local exploration.

| Email | Role |
|---|---|
| `admin@example.com` | ADMIN |
| `organizer@example.com` | ORGANIZER |
| `customer@example.com` | CUSTOMER |

All three use the password `development-only-password`, defined in `application-dev.yml`.

## Authentication flow

Registration creates a `CUSTOMER`; elevated roles are assigned out of band. Login returns an access
token and a refresh token. The access token is sent as a Bearer credential to protected endpoints;
when the access token expires, a valid refresh token can be exchanged for a new token pair.

The walkthrough below uses the seeded organizer to call a protected event-listing endpoint.

```bash
# 1. Register a customer
curl -X POST http://localhost:8080/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"someone@example.com","password":"a-sufficiently-long-password"}'

# 2. Log in once, then derive both tokens from the same response
AUTH_RESPONSE=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"organizer@example.com","password":"development-only-password"}')

TOKEN=$(printf '%s' "$AUTH_RESPONSE" | python3 -c 'import sys,json; print(json.load(sys.stdin)["accessToken"])')
REFRESH_TOKEN=$(printf '%s' "$AUTH_RESPONSE" | python3 -c 'import sys,json; print(json.load(sys.stdin)["refreshToken"])')

# 3. Call a protected endpoint — listing your own events requires ORGANIZER or ADMIN
curl http://localhost:8080/api/events \
  -H "Authorization: Bearer $TOKEN"

# 4. Exchange the refresh token for a new pair
curl -X POST http://localhost:8080/api/auth/refresh \
  -H 'Content-Type: application/json' \
  -d "{\"refreshToken\":\"$REFRESH_TOKEN\"}"
```

Login returns `accessToken`, `refreshToken`, `tokenType` (`Bearer`) and `expiresIn`. Access tokens live
15 minutes, refresh tokens 7 days. Python 3 appears above only to pull fields out of the JSON response
in a copy-pasteable way; the application itself does not need it.

### Sample JWT

**Sample only — this is not a valid token and will not authenticate.** An access token issued by this
service decodes to:

Header:

```json
{ "alg": "HS256" }
```

Payload:

```json
{
  "sub": "3f1c2b9e-7a54-4d2b-9c81-0e5f6a7b8c90",
  "roles": ["CUSTOMER"],
  "typ": "access",
  "iat": 1789000000,
  "exp": 1789000900
}
```

`sub` is the user id and `roles` carries the granted roles, which is what keeps authorization
stateless. The `typ` claim is a security boundary rather than a label: access and refresh tokens are
validated by two separate decoders, so a refresh token presented as a bearer credential is rejected
before any controller runs.

## OpenAPI / Swagger

The **generated API reference** carries the request and response schemas and required headers, so
they are not duplicated in this README.

| | URL |
|---|---|
| Swagger UI | <http://localhost:8080/swagger-ui.html> |
| OpenAPI JSON | <http://localhost:8080/v3/api-docs> |

Both are public so the contract can be read without a token. The API has **11 operations: 3
authentication, 5 event, 3 reservation.**

## Observability

| Endpoint | Access |
|---|---|
| [Health](http://localhost:8080/actuator/health) | Public |
| [Info](http://localhost:8080/actuator/info) | ADMIN |
| [Metrics](http://localhost:8080/actuator/metrics) | ADMIN |

Only `health`, `info` and `metrics` are exposed. See
[security.md](docs/security.md) for the complete access policy.

## Postman collection

A reviewer-oriented Postman collection is available at
[`postman/secure-ticketing-api.postman_collection.json`](postman/secure-ticketing-api.postman_collection.json).

Start the application with the `dev` profile, import the collection, and run the folders from top to
bottom. It uses `http://localhost:8080` by default, logs in with the seeded development users,
captures tokens and created resource IDs automatically, and includes the idempotent reservation
replay and representative authorization checks.

## Architecture decisions at a glance

- **Feature-oriented layered monolith**, not hexagonal — no ports, adapters or mappers without a
  second implementation to justify them.
- **PostgreSQL as the transactional system of record**, because the workload needs declarative
  constraints, atomic multi-row changes and row-level locking.
- **Pessimistic locking with a derived seat total**, chosen over optimistic retries or a counter column
  that could drift.
- **The database unique constraint is the idempotency authority**, not a distributed lock or cache.
- **Two authorization layers** — coarse roles centrally, resource ownership in the service layer.
- **Four untagged metrics and an error id**, rather than a tracing stack with nothing to consume it.

The rationale and trade-offs behind each are recorded in [decisions.md](docs/decisions.md).

## Testing and quality

```bash
./mvnw verify      # tests, formatting, static analysis, coverage report
```

Docker must be running: the targeted PostgreSQL tests use Testcontainers.

The verification worth looking at is not the size of the suite but its shape. The no-oversell and
idempotency guarantees are proven against **real PostgreSQL under genuine contention** — workers
released simultaneously from a barrier, with a connection pool sized so it cannot serialize them into
passing. For the critical correctness mechanisms, each test was kept only after confirming that
**removing the mechanism it guards makes the test fail**; one early concurrency test passed with the
lock removed, which is exactly the failure mode that discipline exists to catch.

`verify` also runs Spotless and SpotBugs with FindSecBugs, and **fails the build** on any violation.

## Further reading

| Document | Contents |
|---|---|
| [docs/architecture.md](docs/architecture.md) | How the system works: components, domain model, the reservation and idempotency flow, concurrency, transactions, testing strategy |
| [docs/security.md](docs/security.md) | Authentication, the complete access-control matrix, threat controls, audit trail and known limitations |
| [docs/decisions.md](docs/decisions.md) | Why: 21 ADRs with alternatives and trade-offs |
