package com.getjobs.cloud.jobs;

import com.getjobs.cloud.web.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The client sort value must collapse into the finite {@link JobModels.JobSort}
 * enum before it can influence SQL; every rejection here is a 400
 * VALIDATION_ERROR and nothing beyond the enum value is carried forward.
 */
class JobServiceSortTest {
    @Test
    void parsesEverySupportedColumnAndDirection() {
        assertThat(JobService.parseSort("lastSeenAt,desc")).isEqualTo(JobModels.JobSort.LAST_SEEN_DESC);
        assertThat(JobService.parseSort("lastSeenAt,asc")).isEqualTo(JobModels.JobSort.LAST_SEEN_ASC);
        assertThat(JobService.parseSort("createdAt,desc")).isEqualTo(JobModels.JobSort.CREATED_DESC);
        assertThat(JobService.parseSort("createdAt,asc")).isEqualTo(JobModels.JobSort.CREATED_ASC);
        assertThat(JobService.parseSort("salaryMinK,desc")).isEqualTo(JobModels.JobSort.SALARY_MIN_DESC);
        assertThat(JobService.parseSort("salaryMinK,asc")).isEqualTo(JobModels.JobSort.SALARY_MIN_ASC);
        assertThat(JobService.parseSort("title,desc")).isEqualTo(JobModels.JobSort.TITLE_DESC);
        assertThat(JobService.parseSort("title,asc")).isEqualTo(JobModels.JobSort.TITLE_ASC);
    }

    @Test
    void defaultsToLastSeenDescendingTrimsAndIgnoresDirectionCase() {
        assertThat(JobService.parseSort(null)).isEqualTo(JobModels.JobSort.LAST_SEEN_DESC);
        assertThat(JobService.parseSort("  ")).isEqualTo(JobModels.JobSort.LAST_SEEN_DESC);
        assertThat(JobService.parseSort("  title,desc  ")).isEqualTo(JobModels.JobSort.TITLE_DESC);
        assertThat(JobService.parseSort("title,DESC")).isEqualTo(JobModels.JobSort.TITLE_DESC);
    }

    @Test
    void rejectsInjectionAttemptsAndUnknownInputAsValidationErrors() {
        String[] malicious = {
                "title;drop table app.users,asc",
                "title,asc;drop table app.users",
                "title,asc,desc",
                "title",
                "unknown,asc",
                "title,up",
                "title,asc nulls last",
                "title ,desc",
                "title, desc",
                "Title,desc",
                ",asc",
        };
        for (String candidate : malicious) {
            assertThatThrownBy(() -> JobService.parseSort(candidate))
                    .as("sort=%s", candidate)
                    .isInstanceOfSatisfying(ApiException.class, exception -> {
                        assertThat(exception.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(exception.code()).isEqualTo("VALIDATION_ERROR");
                    });
        }
    }
}
