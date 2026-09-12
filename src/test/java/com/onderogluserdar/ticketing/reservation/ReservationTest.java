package com.onderogluserdar.ticketing.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.onderogluserdar.ticketing.common.error.BusinessException;
import com.onderogluserdar.ticketing.common.error.ErrorCode;

class ReservationTest {

    private static final UUID EVENT = UUID.randomUUID();
    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-12T10:15:30Z");

    private static Reservation in(ReservationStatus status) {
        Reservation reservation = Reservation.createPending(EVENT, CUSTOMER, 2, NOW);
        switch (status) {
            case PENDING -> {}
            case CONFIRMED -> reservation.confirm();
            case CANCELLED -> reservation.cancel();
        }
        return reservation;
    }

    @Test
    void isCreatedPendingAndOwnedByTheRequester() {
        Reservation reservation = Reservation.createPending(EVENT, CUSTOMER, 2, NOW);

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.PENDING);
        assertThat(reservation.isOwnedBy(CUSTOMER)).isTrue();
        assertThat(reservation.isOwnedBy(UUID.randomUUID())).isFalse();
        assertThat(reservation.getSeats()).isEqualTo(2);
        assertThat(reservation.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void confirmsAPendingReservation() {
        Reservation reservation = in(ReservationStatus.PENDING);

        reservation.confirm();

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @ParameterizedTest(name = "cancel from {0}")
    @MethodSource("activeStates")
    void cancelsFromAnyActiveState(ReservationStatus from) {
        Reservation reservation = in(from);

        reservation.cancel();

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
    }

    private static Stream<ReservationStatus> activeStates() {
        return Stream.of(ReservationStatus.PENDING, ReservationStatus.CONFIRMED);
    }

    private static Stream<Arguments> illegalTransitions() {
        Consumer<Reservation> confirm = Reservation::confirm;
        Consumer<Reservation> cancel = Reservation::cancel;
        return Stream.of(
                Arguments.of("confirm an already confirmed reservation", ReservationStatus.CONFIRMED, confirm),
                Arguments.of("confirm a cancelled reservation", ReservationStatus.CANCELLED, confirm),
                Arguments.of("cancel an already cancelled reservation", ReservationStatus.CANCELLED, cancel));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("illegalTransitions")
    void rejectsAnIllegalTransitionAndLeavesTheStatusUntouched(
            String description, ReservationStatus from, Consumer<Reservation> transition) {
        Reservation reservation = in(from);

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> transition.accept(reservation))
                .satisfies(
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.INVALID_RESERVATION_STATE));
        assertThat(reservation.getStatus()).isEqualTo(from);
    }

    @Test
    void rejectsANonPositiveSeatCount() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> Reservation.createPending(EVENT, CUSTOMER, 0, NOW));
    }
}
