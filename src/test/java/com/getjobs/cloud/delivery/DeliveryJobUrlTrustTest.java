package com.getjobs.cloud.delivery;

import com.getjobs.cloud.web.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * URL trust rules shared by the stored job URL and the client pageUrl: strict
 * HTTPS with host label boundaries, only real Boss/Zhilian job-detail shapes,
 * and no path-normalization smuggling (dot segments, encoded slashes,
 * backslashes). The stored-job judgment strips tracking queries, while the
 * client pageUrl deliberately keeps the stricter query rejection. No network
 * access.
 */
class DeliveryJobUrlTrustTest {

    // ---- BOSS: accepted ----

    @Test
    void bossAcceptsCanonicalAndGeekDetailPaths() {
        assertThat(DeliveryService.normalizeTrustedJobUrl(
                "https://www.zhipin.com/job_detail/abc.html", "BOSS"))
                .contains("https://www.zhipin.com/job_detail/abc.html");
        assertThat(DeliveryService.normalizeTrustedJobUrl(
                "https://www.zhipin.com/web/geek/job_detail/abc.html", "BOSS"))
                .contains("https://www.zhipin.com/web/geek/job_detail/abc.html");
        // Subdomains of the real platform stay inside the trusted label boundary.
        assertThat(DeliveryService.normalizeTrustedJobUrl(
                "https://sub.zhipin.com/job_detail/abc", "BOSS")).isPresent();
    }

    @Test
    void bossStripsTrackingQueryAndFragmentFromStoredUrls() {
        assertThat(DeliveryService.normalizeTrustedJobUrl(
                "https://www.zhipin.com/job_detail/abc.html?ka=tracking&sid=7#top", "BOSS"))
                .contains("https://www.zhipin.com/job_detail/abc.html");
    }

    // ---- BOSS: rejected ----

    @Test
    void bossRejectsSearchHomeAndNonDetailPages() {
        for (String url : new String[]{
                "https://www.zhipin.com/web/geek/job?query=Java",
                "https://www.zhipin.com/web/geek/job",
                "https://www.zhipin.com/",
                "https://www.zhipin.com/zhaopin/job_detail/abc.html",
                "https://www.zhipin.com/job_detail/",
                "https://www.zhipin.com/job_detail",
                "https://www.zhipin.com/job_detail//abc",
                "https://www.zhipin.com/gongsi/job_detail/abc.html",
                "https://www.zhipin.com/job_detail/a/b.html",
                "https://www.zhipin.com/job_detail/search",
        }) {
            assertThat(DeliveryService.normalizeTrustedJobUrl(url, "BOSS")).as(url).isEmpty();
        }
    }

    @Test
    void rejectsNonHttpsPortsUserinfoWrongHostsAndLabelSpoofing() {
        for (String url : new String[]{
                "http://www.zhipin.com/job_detail/abc.html",
                "https://www.zhipin.com:8443/job_detail/abc.html",
                "https://user@www.zhipin.com/job_detail/abc.html",
                "ftp://www.zhipin.com/job_detail/abc.html",
                "https://evilzhipin.com/job_detail/abc.html",
                "https://zhipin.com.evil.example.com/job_detail/abc.html",
                "https://www.zhipin.com.evil.com/job_detail/abc.html",
        }) {
            assertThat(DeliveryService.normalizeTrustedJobUrl(url, "BOSS")).as(url).isEmpty();
        }
    }

    @Test
    void rejectsPathNormalizationSmuggling() {
        for (String url : new String[]{
                "https://www.zhipin.com/job_detail/../web/geek/job_detail/abc.html",
                "https://www.zhipin.com/job_detail/./abc.html",
                "https://www.zhipin.com/job_detail%2Ffake.html",
                "https://www.zhipin.com/job_detail/a%2Fb",
                "https://www.zhipin.com/job_detail%5Cabc.html",
                "https://www.zhipin.com/job_detail/..%2Fabc.html",
        }) {
            assertThat(DeliveryService.normalizeTrustedJobUrl(url, "BOSS")).as(url).isEmpty();
        }
    }

    // ---- ZHILIAN: accepted ----

    @Test
    void zhilianAcceptsKnownDetailShapesAndStripsQueries() {
        for (String url : new String[]{
                "https://www.zhaopin.com/jobdetail/CC193399810J40866382707.htm",
                "https://sou.zhaopin.com/jobs/jobdetail/test",
                "https://sou.zhaopin.com/jobs/positiondetail/test.htm",
                "https://www.zhaopin.com/job_detail/xyz.htm",
                "https://www.zhaopin.com/job/CC123456789.htm",
        }) {
            assertThat(DeliveryService.normalizeTrustedJobUrl(url, "ZHILIAN")).as(url).isPresent();
        }
        assertThat(DeliveryService.normalizeTrustedJobUrl(
                "https://www.zhaopin.com/jobdetail/CC123.htm?ka=x", "ZHILIAN"))
                .contains("https://www.zhaopin.com/jobdetail/CC123.htm");
    }

    @Test
    void zhilianJobsHostAllowsOnlySingleJobFilePages() {
        assertThat(DeliveryService.normalizeTrustedJobUrl(
                "https://jobs.zhaopin.com/CC193399810J40866382707.htm", "ZHILIAN"))
                .contains("https://jobs.zhaopin.com/CC193399810J40866382707.htm");
        for (String url : new String[]{
                "https://jobs.zhaopin.com/",
                "https://jobs.zhaopin.com/index.htm",
                "https://jobs.zhaopin.com/home.html",
                "https://jobs.zhaopin.com/search.htm",
                "https://jobs.zhaopin.com/CC123",
        }) {
            assertThat(DeliveryService.normalizeTrustedJobUrl(url, "ZHILIAN")).as(url).isEmpty();
        }
    }

    // ---- ZHILIAN: rejected ----

    @Test
    void zhilianRejectsHomeSearchAndDirectoryOnlyPaths() {
        for (String url : new String[]{
                "https://sou.zhaopin.com/",
                "https://www.zhaopin.com/",
                "https://www.zhaopin.com/sou/jl765/?kw=Java",
                "https://sou.zhaopin.com/jobs/jobdetail",
                "https://sou.zhaopin.com/jobs/positiondetail/",
                "https://www.zhaopin.com/job/",
                "https://www.zhaopin.com/job/search",
                "https://www.zhaopin.com/jobdetail/search",
                "https://evilzhaopin.com/jobdetail/x.htm",
                "https://zhaopin.com.evil.example.com/jobdetail/x.htm",
                "http://www.zhaopin.com/jobdetail/x.htm",
                "https://www.zhaopin.com/jobdetail/../x.htm",
                "https://www.zhaopin.com/jobdetail%2Fx.htm",
        }) {
            assertThat(DeliveryService.normalizeTrustedJobUrl(url, "ZHILIAN")).as(url).isEmpty();
        }
    }

    // ---- client pageUrl shares the judgment but rejects queries ----

    @Test
    void clientPageUrlSharesTheHostPathJudgmentButRejectsQueries() {
        DeliveryService.validatePageUrl("https://www.zhipin.com/job_detail/abc.html", "BOSS");
        DeliveryService.validatePageUrl("https://www.zhipin.com/web/geek/job_detail/abc.html", "BOSS");
        DeliveryService.validatePageUrl("https://www.zhaopin.com/jobdetail/x.htm", "ZHILIAN");
        // The client-side judgment deliberately stays stricter than the stored
        // URL: queries and fragments are rejected outright (see CLOUD_SECURITY.md).
        assertThatThrownBy(() -> DeliveryService.validatePageUrl(
                "https://www.zhipin.com/job_detail/abc.html?ka=x", "BOSS"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> DeliveryService.validatePageUrl(
                "https://www.zhipin.com/job_detail/abc.html#top", "BOSS"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> DeliveryService.validatePageUrl(
                "https://www.zhipin.com/web/geek/job", "BOSS"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> DeliveryService.validatePageUrl(
                "https://evil.example.com/job_detail/x", "BOSS"))
                .isInstanceOf(ApiException.class);
    }
}
