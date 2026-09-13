package com.onderogluserdar.ticketing.observability;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

@Component
public class TicketingMetrics {

    private final Counter reservationsCreated;
    private final Counter reservationsRejectedForCapacity;
    private final Counter idempotencyReplays;
    private final Counter rateLimitRejections;

    TicketingMetrics(MeterRegistry registry) {
        this.reservationsCreated = Counter.builder("ticketing.reservation.created")
                .description("Reservations created and committed, excluding idempotent replays")
                .register(registry);
        this.reservationsRejectedForCapacity = Counter.builder("ticketing.reservation.rejected.capacity")
                .description("Reservation attempts refused because the event had insufficient capacity")
                .register(registry);
        this.idempotencyReplays = Counter.builder("ticketing.idempotency.replay")
                .description("Requests resolved as an idempotent replay of a stored reservation result")
                .register(registry);
        this.rateLimitRejections = Counter.builder("ticketing.rate.limit.rejected")
                .description("Login attempts refused by the rate limiter, counted at the limiter decision")
                .register(registry);
    }

    public void reservationCreated() {
        reservationsCreated.increment();
    }

    public void reservationRejectedForCapacity() {
        reservationsRejectedForCapacity.increment();
    }

    public void idempotencyReplayed() {
        idempotencyReplays.increment();
    }

    public void rateLimitRejected() {
        rateLimitRejections.increment();
    }
}
