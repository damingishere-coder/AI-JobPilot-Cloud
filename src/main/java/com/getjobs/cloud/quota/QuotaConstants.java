package com.getjobs.cloud.quota;

/**
 * 额度领域稳定常量：套餐代码、资源代码与流水动作。
 *
 * <p>套餐代码面向后续订阅批次预留在代码层；本批次普通用户没有任何切换接口。</p>
 */
public final class QuotaConstants {

    /** 免费计划。 */
    public static final String PLAN_FREE = "FREE";
    /** 月度套餐（预留）。 */
    public static final String PLAN_MONTHLY = "MONTHLY";
    /** 高级月度套餐（预留）。 */
    public static final String PLAN_PREMIUM_MONTHLY = "PREMIUM_MONTHLY";
    /** 求职季套餐（预留）。 */
    public static final String PLAN_JOB_SEASON = "JOB_SEASON";
    /** 求职辅导套餐（预留）。 */
    public static final String PLAN_COACHING = "COACHING";

    /** AI 岗位分析额度。 */
    public static final String RESOURCE_AI_ANALYSIS = "AI_ANALYSIS";
    /** 投递确认额度。 */
    public static final String RESOURCE_DELIVERY_CONFIRM = "DELIVERY_CONFIRM";

    /** 预占：reserved_amount 增加，业务未完成前占用。 */
    public static final String ACTION_RESERVE = "RESERVE";
    /** 确认结算：reserved-1 且 used+1（或直接消耗）。 */
    public static final String ACTION_COMMIT = "COMMIT";
    /** 释放：reserved-1，回补可用额度。 */
    public static final String ACTION_RELEASE = "RELEASE";
    /** 人工调整（后续管理员批次使用）。 */
    public static final String ACTION_ADJUST = "ADJUST";

    /** 额度重置周期：按 UTC 自然月。 */
    public static final String RESET_CYCLE_MONTHLY = "MONTHLY";

    /** AI 分析额度流水引用类型：岗位匹配（job_matches 行）。 */
    public static final String REFERENCE_JOB_MATCH = "JOB_MATCH";

    /** 投递确认额度流水引用类型：投递任务（delivery_tasks 行）。 */
    public static final String REFERENCE_DELIVERY_TASK = "DELIVERY_TASK";

    /** AI 分析请求预占额度的固定流水原因（不含任何用户输入/PII）。 */
    public static final String REASON_AI_ANALYSIS_RESERVE = "AI 分析请求预占额度";
    /** AI 分析成功结算的固定流水原因。 */
    public static final String REASON_AI_ANALYSIS_COMMIT = "AI 分析成功结算";
    /** AI 分析最终失败返还的固定流水原因。 */
    public static final String REASON_AI_ANALYSIS_RELEASE = "AI 分析最终失败返还";

    /** 投递确认直接消耗的固定流水原因（不含任何用户输入/PII；执行失败不返还）。 */
    public static final String REASON_DELIVERY_CONFIRM = "投递任务确认消耗投递额度";

    private QuotaConstants() {
    }
}
