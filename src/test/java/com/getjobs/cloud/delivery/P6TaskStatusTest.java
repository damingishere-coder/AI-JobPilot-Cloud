package com.getjobs.cloud.delivery;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Canonical status vocabulary (P8): the eight persistent values surface
 * unchanged through every DTO; legacy pre-V10 names resolve in filters to
 * their canonical successors; unknown values never resolve.
 */
class P6TaskStatusTest {

    @Test
    void canonicalStatusesPassThroughUnchanged() {
        for (String status : new String[]{
                "WAITING_CONFIRM", "CONFIRMED", "PULLED_BY_PLUGIN", "RUNNING",
                "SUCCESS", "FAILED", "SKIPPED", "PAUSED_NEED_USER"
        }) {
            assertThat(P6TaskStatus.fromPersistent(status)).isEqualTo(status);
            assertThat(P6TaskStatus.isP6Name(status)).isTrue();
            assertThat(P6TaskStatus.persistentFilterStatuses(status)).containsExactly(status);
        }
    }

    @Test
    void legacyNamesMapToTheirCanonicalSuccessors() {
        assertThat(P6TaskStatus.fromPersistent("PENDING_CONFIRMATION")).isEqualTo("WAITING_CONFIRM");
        assertThat(P6TaskStatus.fromPersistent("LEASED")).isEqualTo("PULLED_BY_PLUGIN");
        assertThat(P6TaskStatus.fromPersistent("EXECUTING")).isEqualTo("RUNNING");
        assertThat(P6TaskStatus.fromPersistent("SUCCEEDED")).isEqualTo("SUCCESS");
        assertThat(P6TaskStatus.fromPersistent("PAUSED")).isEqualTo("PAUSED_NEED_USER");
        assertThat(P6TaskStatus.fromPersistent("CANCELLED")).isEqualTo("SKIPPED");
        assertThat(P6TaskStatus.fromPersistent("CONFIRMED")).isEqualTo("CONFIRMED");
        assertThat(P6TaskStatus.fromPersistent(null)).isNull();
    }

    @Test
    void legacyFilterValuesResolveToTheCanonicalStatusSet() {
        assertThat(P6TaskStatus.persistentFilterStatuses("EXECUTING")).containsExactly("RUNNING");
        assertThat(P6TaskStatus.persistentFilterStatuses("executing")).containsExactly("RUNNING");
        assertThat(P6TaskStatus.persistentFilterStatuses("SUCCEEDED")).containsExactly("SUCCESS");
        assertThat(P6TaskStatus.persistentFilterStatuses("PENDING_CONFIRMATION"))
                .containsExactly("WAITING_CONFIRM");
        assertThat(P6TaskStatus.persistentFilterStatuses("PAUSED")).containsExactly("PAUSED_NEED_USER");
        assertThat(P6TaskStatus.persistentFilterStatuses("CANCELLED")).containsExactly("SKIPPED");
    }

    @Test
    void unknownValuesNeverResolveAndAreNotP6Names() {
        assertThat(P6TaskStatus.persistentFilterStatuses("EXECUTING_BOGUS")).isNull();
        assertThat(P6TaskStatus.persistentFilterStatuses(null)).isNull();
        assertThat(P6TaskStatus.persistentFilterStatuses("")).isNull();
        assertThat(P6TaskStatus.isP6Name("LEASED")).isFalse();
        assertThat(P6TaskStatus.isP6Name("WAITING_CONFIRM")).isTrue();
        assertThat(P6TaskStatus.isP6Name(null)).isFalse();
        // Unknown persistent values pass through unchanged, never null.
        assertThat(P6TaskStatus.fromPersistent("MYSTERY")).isEqualTo("MYSTERY");
    }

    @Test
    void labelsCoverEveryCanonicalStatus() {
        for (P6TaskStatus status : P6TaskStatus.values()) {
            assertThat(status.label()).isNotBlank();
        }
    }
}
