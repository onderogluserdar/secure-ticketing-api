package com.onderogluserdar.ticketing.reservation;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.onderogluserdar.ticketing.common.error.BusinessException;
import com.onderogluserdar.ticketing.common.error.ErrorCode;
import com.onderogluserdar.ticketing.idempotency.IdempotencyKey;
import com.onderogluserdar.ticketing.idempotency.IdempotencyKeyRepository;
import com.onderogluserdar.ticketing.reservation.dto.ReservationResponse;
import com.onderogluserdar.ticketing.security.CurrentUser;

import tools.jackson.databind.ObjectMapper;

@Service
public class IdempotentReservationService {

    static final String ENDPOINT = "POST /api/events/{eventId}/reservations";

    private static final Duration TIME_TO_LIVE = Duration.ofHours(24);

    private static final int MAX_KEY_LENGTH = 200;

    private final IdempotencyKeyRepository keys;
    private final ReservationTransaction transaction;
    private final ObjectMapper objectMapper;

    IdempotentReservationService(
            IdempotencyKeyRepository keys, ReservationTransaction transaction, ObjectMapper objectMapper) {
        this.keys = keys;
        this.transaction = transaction;
        this.objectMapper = objectMapper;
    }

    public record Outcome(int status, ReservationResponse response) {}

    public Outcome reserve(String key, UUID eventId, int seats, CurrentUser caller) {
        String idempotencyKey = requireKey(key);
        String requestHash = requestHash(eventId, seats);

        Optional<IdempotencyKey> known = reclaimIfExpired(caller.id(), idempotencyKey);
        if (known.isPresent()) {
            return replayOrConflict(known.get(), requestHash);
        }

        try {
            return transaction.claimAndReserve(
                    caller, ENDPOINT, idempotencyKey, requestHash, TIME_TO_LIVE, eventId, seats);
        } catch (DataIntegrityViolationException raceOrRealFailure) {
            IdempotencyKey winner = keys.findByUserIdAndEndpointAndIdempotencyKey(caller.id(), ENDPOINT, idempotencyKey)
                    .orElseThrow(() -> raceOrRealFailure);
            return replayOrConflict(winner, requestHash);
        }
    }

    private Optional<IdempotencyKey> reclaimIfExpired(UUID userId, String idempotencyKey) {
        Optional<IdempotencyKey> found =
                keys.findByUserIdAndEndpointAndIdempotencyKey(userId, ENDPOINT, idempotencyKey);
        if (found.isPresent() && found.get().hasExpiredBy(Instant.now())) {
            keys.deleteIfExpired(found.get().getId(), Instant.now());
            return Optional.empty();
        }
        return found;
    }

    private Outcome replayOrConflict(IdempotencyKey known, String requestHash) {
        if (!known.matches(requestHash)) {
            throw new BusinessException(
                    ErrorCode.IDEMPOTENCY_KEY_CONFLICT, "this Idempotency-Key was used with a different request");
        }
        if (!known.isReplayable()) {
            throw new BusinessException(
                    ErrorCode.IDEMPOTENCY_KEY_CONFLICT, "this Idempotency-Key is still being processed");
        }
        return new Outcome(
                known.getResponseStatus(), objectMapper.readValue(known.getResponseBody(), ReservationResponse.class));
    }

    private static String requestHash(UUID eventId, int seats) {
        return IdempotencyKey.sha256("eventId=" + eventId + "&seats=" + seats);
    }

    private static String requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "Idempotency-Key must not be blank");
        }
        if (key.length() > MAX_KEY_LENGTH) {
            throw new BusinessException(
                    ErrorCode.INVALID_REQUEST, "Idempotency-Key must be at most " + MAX_KEY_LENGTH + " characters");
        }
        return key;
    }
}
