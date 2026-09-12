package com.onderogluserdar.ticketing.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import com.onderogluserdar.ticketing.common.error.BusinessException;
import com.onderogluserdar.ticketing.common.error.ErrorCode;

@Entity
@Table(name = "events")
public class Event {

    private static final int MAX_TITLE_LENGTH = 200;
    private static final int MAX_VENUE_LENGTH = 200;

    @Id
    private UUID id;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String venue;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(nullable = false)
    private int capacity;

    @Column(nullable = false)
    private boolean published;

    @Version
    @Column(nullable = false)
    private long version;

    protected Event() {}

    public static Event createDraft(
            UUID ownerId, String title, String venue, Instant startsAt, Instant endsAt, int capacity) {
        Event event = new Event();
        event.id = UUID.randomUUID();
        event.ownerId = Objects.requireNonNull(ownerId, "ownerId must not be null");
        event.applyDetails(title, venue, startsAt, endsAt, capacity);
        return event;
    }

    public void update(
            String title, String venue, Instant startsAt, Instant endsAt, int capacity, int activeReservedSeats) {
        if (capacity < activeReservedSeats) {
            throw new BusinessException(
                    ErrorCode.CAPACITY_BELOW_RESERVED,
                    "capacity %d is below the %d seats already reserved".formatted(capacity, activeReservedSeats));
        }
        applyDetails(title, venue, startsAt, endsAt, capacity);
    }

    public void publish() {
        if (published) {
            throw new BusinessException(
                    ErrorCode.EVENT_ALREADY_PUBLISHED, "event %s is already published".formatted(id));
        }
        published = true;
    }

    public boolean isOwnedBy(UUID userId) {
        return ownerId.equals(userId);
    }

    public int remainingSeats(int activeReservedSeats) {
        return capacity - activeReservedSeats;
    }

    public void ensureCanAccommodate(int requestedSeats, int activeReservedSeats) {
        if (!published) {
            throw new BusinessException(ErrorCode.EVENT_NOT_PUBLISHED, "event %s is not published".formatted(id));
        }
        if (activeReservedSeats + requestedSeats > capacity) {
            throw new BusinessException(
                    ErrorCode.INSUFFICIENT_CAPACITY,
                    "only %d of %d seats remain".formatted(remainingSeats(activeReservedSeats), capacity));
        }
    }

    private void applyDetails(String title, String venue, Instant startsAt, Instant endsAt, int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1");
        }
        Objects.requireNonNull(startsAt, "startsAt must not be null");
        Objects.requireNonNull(endsAt, "endsAt must not be null");
        if (!endsAt.isAfter(startsAt)) {
            throw new IllegalArgumentException("endsAt must be after startsAt");
        }
        this.title = requireText(title, "title", MAX_TITLE_LENGTH);
        this.venue = requireText(venue, "venue", MAX_VENUE_LENGTH);
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.capacity = capacity;
    }

    private static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException(field + " must be at most " + maxLength + " characters");
        }
        return trimmed;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public String getTitle() {
        return title;
    }

    public String getVenue() {
        return venue;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public Instant getEndsAt() {
        return endsAt;
    }

    public int getCapacity() {
        return capacity;
    }

    public boolean isPublished() {
        return published;
    }

    public long getVersion() {
        return version;
    }
}
