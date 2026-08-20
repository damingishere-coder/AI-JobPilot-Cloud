package com.getjobs.cloud.delivery;

import com.getjobs.cloud.delivery.DeliveryRepository.TaskSort;
import com.getjobs.cloud.web.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The client sort value must collapse into the finite {@link TaskSort} enum
 * before it can influence SQL; every rejection here is a 400 VALIDATION_ERROR
 * and nothing beyond the enum value is carried forward.
 */
class DeliveryServiceSortTest {
    @Test
    void parsesEverySupportedColumnAndDirection() {
        assertThat(DeliveryService.parseSort("createdAt,desc")).isEqualTo(TaskSort.CREATED_DESC);
        assertThat(DeliveryService.parseSort("createdAt,asc")).isEqualTo(TaskSort.CREATED_ASC);
        assertThat(DeliveryService.parseSort("updatedAt,desc")).isEqualTo(TaskSort.UPDATED_DESC);
        assertThat(DeliveryService.parseSort("updatedAt,asc")).isEqualTo(TaskSort.UPDATED_ASC);
        assertThat(DeliveryService.parseSort("confirmedAt,desc")).isEqualTo(TaskSort.CONFIRMED_DESC);
        assertThat(DeliveryService.parseSort("confirmedAt,asc")).isEqualTo(TaskSort.CONFIRMED_ASC);
    }

    @Test
    void defaultsToCreatedAtDescendingTrimsAndIgnoresDirectionCase() {
        assertThat(DeliveryService.parseSort(null)).isEqualTo(TaskSort.CREATED_DESC);
        assertThat(DeliveryService.parseSort("  ")).isEqualTo(TaskSort.CREATED_DESC);
        assertThat(DeliveryService.parseSort("  updatedAt,asc  ")).isEqualTo(TaskSort.UPDATED_ASC);
        assertThat(DeliveryService.parseSort("updatedAt,ASC")).isEqualTo(TaskSort.UPDATED_ASC);
    }

    @Test
    void rejectsInjectionAttemptsAndUnknownInputAsValidationErrors() {
        String[] malicious = {
                "updatedAt;drop table app.delivery_tasks,asc",
                "updatedAt,asc;drop table app.delivery_tasks",
                "updatedAt,asc,desc",
                "updatedAt",
                "greeting,asc",
                "updatedAt,up",
                "updatedAt,asc nulls last",
                "updatedAt ,desc",
                "updatedAt, desc",
                "UpdatedAt,desc",
                ",asc",
        };
        for (String candidate : malicious) {
            assertThatThrownBy(() -> DeliveryService.parseSort(candidate))
                    .as("sort=%s", candidate)
                    .isInstanceOfSatisfying(ApiException.class, exception -> {
                        assertThat(exception.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(exception.code()).isEqualTo("VALIDATION_ERROR");
                    });
        }
    }
}
