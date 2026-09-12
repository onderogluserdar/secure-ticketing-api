package com.onderogluserdar.ticketing.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.onderogluserdar.ticketing.event.Event;
import com.onderogluserdar.ticketing.event.EventRepository;
import com.onderogluserdar.ticketing.user.Role;
import com.onderogluserdar.ticketing.user.User;
import com.onderogluserdar.ticketing.user.UserRepository;

class PublicDiscoveryPostgresTest extends PostgresTest {

    @Autowired
    private EventRepository events;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void runsAgainstPostgresAndNotTheH2Fallback() {
        String version = jdbc.sql("select version()").query(String.class).single();

        assertThat(version).startsWith("PostgreSQL");
    }

    @Test
    void executesTheDiscoveryQueryWithNullableFromAndTo() {
        UUID ownerId = insertOwner();
        events.save(publishedEvent(ownerId));

        PageRequest page = PageRequest.of(
                0, 20, Sort.by("startsAt").ascending().and(Sort.by("id").ascending()));

        assertThat(events.findPublished(null, null, null, page)).isNotEmpty();
        assertThat(events.findPublished(Instant.parse("2026-09-01T00:00:00Z"), null, null, page))
                .isNotEmpty();
        assertThat(events.findPublished(null, Instant.parse("2026-11-01T00:00:00Z"), null, page))
                .isNotEmpty();
        assertThat(events.findPublished(null, null, "ZIGGO", page)).isNotEmpty();
        assertThat(events.findPublished(Instant.parse("2026-11-01T00:00:00Z"), null, null, page))
                .isEmpty();
    }

    private static Event publishedEvent(UUID ownerId) {
        Event event = Event.createDraft(
                ownerId,
                "Concert",
                "Ziggo Dome",
                Instant.parse("2026-10-01T18:00:00Z"),
                Instant.parse("2026-10-01T21:00:00Z"),
                10);
        event.publish();
        return event;
    }

    private UUID insertOwner() {
        return users.save(User.create(
                        UUID.randomUUID() + "@example.com", "$2a$12$hash", Set.of(Role.ORGANIZER), Instant.now()))
                .getId();
    }
}
