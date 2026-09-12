# Secure Ticketing & Reservation API

A Spring Boot REST API for event ticketing with JWT authentication, role and
ownership based authorization, and reservations that cannot oversell.

## What this solution optimizes for

- **No overselling** under concurrent reservation requests.
- **Idempotent reservation creation** via a required `Idempotency-Key`.
- **Role and ownership authorization**, including explicit IDOR protection.

## Architecture at a glance

```
Controller -> Service -> Repository -> PostgreSQL
```

A feature-oriented layered monolith, with tactical DDD applied to the core
business rules.

## Status

Under development. This README is completed as the implementation lands.
