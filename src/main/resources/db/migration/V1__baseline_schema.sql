create table users (
    id            uuid         not null,
    email         varchar(254) not null,
    password_hash varchar(255) not null,
    created_at    timestamp with time zone not null,
    last_login_at timestamp with time zone,
    constraint users_pk primary key (id),
    constraint users_email_unique unique (email)
);

create table user_roles (
    user_id uuid        not null,
    role    varchar(20) not null,
    constraint user_roles_pk primary key (user_id, role),
    constraint user_roles_user_fk foreign key (user_id) references users (id),
    constraint user_roles_known check (role in ('ADMIN', 'ORGANIZER', 'CUSTOMER'))
);

create table events (
    id        uuid         not null,
    owner_id  uuid         not null,
    title     varchar(200) not null,
    venue     varchar(200) not null,
    starts_at timestamp with time zone not null,
    ends_at   timestamp with time zone not null,
    capacity  integer      not null,
    published boolean      not null default false,
    version   bigint       not null default 0,
    constraint events_pk primary key (id),
    constraint events_owner_fk foreign key (owner_id) references users (id),
    constraint events_capacity_positive check (capacity > 0),
    constraint events_period_ordered check (ends_at > starts_at)
);

create index events_owner_idx on events (owner_id);
create index events_discovery_idx on events (published, starts_at);

create table reservations (
    id         uuid        not null,
    event_id   uuid        not null,
    user_id    uuid        not null,
    status     varchar(20) not null,
    seats      integer     not null,
    created_at timestamp with time zone not null,
    constraint reservations_pk primary key (id),
    constraint reservations_event_fk foreign key (event_id) references events (id),
    constraint reservations_user_fk foreign key (user_id) references users (id),
    constraint reservations_status_known check (status in ('PENDING', 'CONFIRMED', 'CANCELLED')),
    constraint reservations_seats_positive check (seats > 0)
);

-- Every capacity decision sums this index; without it the sum, not the row lock, is the bottleneck.
create index reservations_active_seats_idx on reservations (event_id, status);
create index reservations_user_idx on reservations (user_id);

create table idempotency_keys (
    id              uuid         not null,
    user_id         uuid         not null,
    endpoint        varchar(200) not null,
    idempotency_key varchar(200) not null,
    request_hash    varchar(64)  not null,
    response_hash   varchar(64),
    response_status integer,
    response_body   varchar(4096),
    status          varchar(20)  not null,
    created_at      timestamp with time zone not null,
    expires_at      timestamp with time zone not null,
    constraint idempotency_keys_pk primary key (id),
    constraint idempotency_keys_scope_unique unique (user_id, endpoint, idempotency_key),
    constraint idempotency_keys_status_known check (status in ('IN_PROGRESS', 'COMPLETED', 'FAILED'))
);

create index idempotency_keys_expiry_idx on idempotency_keys (expires_at);

create table audit_logs (
    id            uuid        not null,
    actor_id      uuid,
    action        varchar(50) not null,
    resource_type varchar(50),
    resource_id   varchar(100),
    ip            varchar(45),
    user_agent    varchar(255),
    created_at    timestamp with time zone not null,
    constraint audit_logs_pk primary key (id)
);

create index audit_logs_actor_idx on audit_logs (actor_id);
create index audit_logs_created_idx on audit_logs (created_at);
