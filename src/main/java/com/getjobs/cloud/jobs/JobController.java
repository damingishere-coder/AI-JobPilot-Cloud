package com.getjobs.cloud.jobs;

import com.getjobs.cloud.auth.CurrentUser;
import com.getjobs.cloud.web.ApiResponse;
import com.getjobs.cloud.web.PageResult;
import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/jobs")
@Profile("api")
public class JobController {
    private final CurrentUser currentUser;
    private final JobService jobs;

    public JobController(CurrentUser currentUser, JobService jobs) {
        this.currentUser = currentUser;
        this.jobs = jobs;
    }

    @GetMapping
    public ApiResponse<PageResult<JobModels.JobSummary>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant capturedFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant capturedTo,
            @RequestParam(defaultValue = "lastSeenAt,desc") String sort,
            @RequestParam(required = false) String matchDecision,
            @RequestParam(required = false) String matchStatus,
            @RequestParam(required = false) Integer minScore
    ) {
        return ApiResponse.success(jobs.list(
                currentUser.require().userId(), page, size, platform, status, keyword,
                capturedFrom, capturedTo, sort, matchDecision, matchStatus, minScore
        ));
    }

    @GetMapping("/{id}")
    public ApiResponse<JobModels.JobDetail> detail(@PathVariable UUID id) {
        return ApiResponse.success(jobs.detail(currentUser.require().userId(), id));
    }
}
