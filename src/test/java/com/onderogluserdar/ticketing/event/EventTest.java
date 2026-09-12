package com.onderogluserdar.ticketing.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.onderogluserdar.ticketing.common.error.BusinessException;
import com.onderogluserdar.ticketing.common.error.ErrorCode;

class EventTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final Instant STARTS = Instant.parse("2026-10-01T18:00:00Z");
    private static final Instant ENDS = Instant.parse("2026-10-01T21:00:00Z");

    private static Event draft(int capacity) {
        return Event.createDraft(OWNER, "Concert", "Ziggo Dome", STARTS, ENDS, capacity);
    }

    @Test
    void publishesADraftOnceAndRefusesToPublishItAgain() {
        Event event = draft(100);
        assertThat(event.isPublished()).isFalse();

        event.publish();
        assertThat(event.isPublished()).isTrue();

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(event::publish)
                .satisfies(failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.EVENT_ALREADY_PUBLISHED));
    }

    @Test
    void refusesToShrinkCapacityBelowTheSeatsAlreadyReserved() {
        Event event = draft(100);

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> event.update("Concert", "Ziggo Dome", STARTS, ENDS, 29, 30))
                .satisfies(failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.CAPACITY_BELOW_RESERVED));
        assertThat(event.getCapacity()).isEqualTo(100);

        assertThatCode(() -> event.update("Concert", "Ziggo Dome", STARTS, ENDS, 30, 30))
                .doesNotThrowAnyException();
        assertThat(event.getCapacity()).isEqualTo(30);
    }

    @Test
    void keepsOwnershipOutOfReachOfOtherUsers() {
        Event event = draft(10);

        assertThat(event.isOwnedBy(OWNER)).isTrue();
        assertThat(event.isOwnedBy(UUID.randomUUID())).isFalse();
    }

    private static Stream<Arguments> malformedDetails() {
        return Stream.of(
                Arguments.of("blank title", (ThrowingCallable)
                        () -> Event.createDraft(OWNER, "  ", "Ziggo Dome", STARTS, ENDS, 10)),
                Arguments.of("blank venue", (ThrowingCallable)
                        () -> Event.createDraft(OWNER, "Concert", "", STARTS, ENDS, 10)),
                Arguments.of("zero capacity", (ThrowingCallable)
                        () -> Event.createDraft(OWNER, "Concert", "Ziggo Dome", STARTS, ENDS, 0)),
                Arguments.of("ends before it starts", (ThrowingCallable)
                        () -> Event.createDraft(OWNER, "Concert", "Ziggo Dome", ENDS, STARTS, 10)),
                Arguments.of("zero length", (ThrowingCallable)
                        () -> Event.createDraft(OWNER, "Concert", "Ziggo Dome", STARTS, STARTS, 10)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedDetails")
    void rejectsAnEventThatBreaksItsOwnInvariants(String description, ThrowingCallable creation) {
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(creation);
    }
}
