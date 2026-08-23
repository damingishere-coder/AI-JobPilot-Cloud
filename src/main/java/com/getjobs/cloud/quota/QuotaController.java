package com.getjobs.cloud.quota;

import com.getjobs.cloud.auth.CurrentUser;
import com.getjobs.cloud.quota.QuotaModels.QuotaMeView;
import com.getjobs.cloud.web.ApiResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前用户额度查询接口。只返回本人 plan/reset 信息与两类资源额度，
 * 不返回流水幂等键、其他用户或敏感字段。
 */
@RestController
@RequestMapping("/api/quota")
@Profile("api")
public class QuotaController {

    private final CurrentUser currentUser;
    private final QuotaService quotas;

    public QuotaController(CurrentUser currentUser, QuotaService quotas) {
        this.currentUser = currentUser;
        this.quotas = quotas;
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<QuotaMeView>> me() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(quotas.currentView(currentUser.require().userId())));
    }
}
