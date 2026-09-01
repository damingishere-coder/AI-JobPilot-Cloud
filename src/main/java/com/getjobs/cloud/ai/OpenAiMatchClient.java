package com.getjobs.cloud.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

@Service
@Profile("worker")
public class OpenAiMatchClient implements AiMatchClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiMatchClient.class);
    private static final int MAX_SUMMARY_CODE_POINTS = 2000;
    private static final int MAX_ITEM_COUNT = 5;
    private static final int MAX_ITEM_CODE_POINTS = 200;
    private static final int MAX_GREETING_CODE_POINTS = 60;

    private final AiMatchProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OpenAiMatchClient(AiMatchProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getTimeout().getConnect()))
                .build();
    }

    @Override
    public MatchResponse analyze(MatchRequest request) {
        if (!properties.isEnabled()) {
            throw new AiMatchException("AI_NOT_CONFIGURED",
                    "AI 岗位分析服务未启用，请检查 app.ai-match.enabled 配置");
        }
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new AiMatchException("AI_NOT_CONFIGURED",
                    "AI Key 未配置，请在 Worker Secret 中设置 ai_api_key");
        }

        String systemPrompt = buildSystemPrompt();
        String userPrompt = buildUserPrompt(request);

        long startNanos = System.nanoTime();
        String responseBody = null;
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "model", properties.getModel(),
                    "messages", List.of(
                            Map.of("role", "system", "content", systemPrompt),
                            Map.of("role", "user", "content", userPrompt)
                    ),
                    "temperature", 0.1,
                    "max_tokens", 4096,
                    "response_format", Map.of("type", "json_object")
            ));

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getBaseUrl() + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .timeout(Duration.ofMillis(properties.getTimeout().getRequest()))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            responseBody = response.body();

            if (response.statusCode() >= 400) {
                log.warn("AI API 返回错误状态，状态码={}", response.statusCode());
                if (response.statusCode() == 429 || response.statusCode() >= 500) {
                    throw new AiMatchException("AI_SERVICE_UNAVAILABLE",
                            "AI 服务暂时不可用（" + response.statusCode() + "），将自动重试",
                            true);
                }
                throw new AiMatchException("AI_REQUEST_FAILED",
                        "AI 服务返回错误（" + response.statusCode() + "）");
            }

            JsonNode root;
            try {
                root = objectMapper.readTree(responseBody);
            } catch (JsonProcessingException exception) {
                // Never log response fragments, even sanitized — only the exception type.
                log.warn("AI 响应不是合法 JSON，异常类型={}", exception.getClass().getSimpleName());
                throw new AiMatchException("AI_RESPONSE_INVALID",
                        "AI 响应不是合法的 JSON 对象");
            }
            if (root == null || !root.isObject()) {
                throw new AiMatchException("AI_RESPONSE_INVALID",
                        "AI 响应不是 JSON 对象");
            }

            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                throw new AiMatchException("AI_RESPONSE_INVALID",
                        "AI 服务返回格式不正确：缺少 choices");
            }

            JsonNode choice0 = choices.get(0);
            if (!choice0.isObject()) {
                throw new AiMatchException("AI_RESPONSE_INVALID",
                        "AI 服务返回格式不正确：choices[0] 不是对象");
            }
            JsonNode message = choice0.get("message");
            if (message == null || !message.isObject()) {
                throw new AiMatchException("AI_RESPONSE_INVALID",
                        "AI 服务返回格式不正确：缺少 message 对象");
            }

            JsonNode contentNode = message.get("content");
            if (contentNode == null || !contentNode.isTextual()) {
                throw new AiMatchException("AI_RESPONSE_INVALID",
                        "AI 服务返回格式不正确：message.content 不是文本");
            }
            String content = contentNode.asText();
            if (content.isBlank()) {
                throw new AiMatchException("AI_RESPONSE_INVALID",
                        "AI 服务返回的 content 为空");
            }

            // usage is optional overall (tokens recorded as null when absent).
            // When present it must be an object with both token fields valid.
            Integer inputTokens = null;
            Integer outputTokens = null;
            JsonNode usage = root.get("usage");
            if (usage != null && !usage.isNull()) {
                if (!usage.isObject()) {
                    throw new AiMatchException("AI_RESPONSE_INVALID",
                            "AI 响应的 usage 字段不是对象");
                }
                inputTokens = getUsageInt(usage, "prompt_tokens");
                outputTokens = getUsageInt(usage, "completion_tokens");
                if (inputTokens == null || outputTokens == null) {
                    throw new AiMatchException("AI_RESPONSE_INVALID",
                            "AI 响应的 usage 字段无效（token 计数必须是非负整数）");
                }
            }
            int durationMs = (int) Duration.ofNanos(System.nanoTime() - startNanos).toMillis();

            // match content must be a JSON object; violations are non-retryable
            // because retrying the same invalid model output cannot succeed.
            JsonNode matchJson;
            try {
                matchJson = objectMapper.readTree(content);
            } catch (JsonProcessingException exception) {
                throw new AiMatchException("AI_RESPONSE_INVALID",
                        "AI 返回内容无法解析为 JSON 对象");
            }
            if (matchJson == null || !matchJson.isObject()) {
                throw new AiMatchException("AI_RESPONSE_INVALID",
                        "AI 返回内容不是 JSON 对象");
            }
            return validateAndBuild(matchJson, inputTokens, outputTokens, durationMs);

        } catch (AiMatchException exception) {
            throw exception;
        } catch (Exception exception) {
            if (exception instanceof java.io.IOException
                    || exception instanceof java.net.http.HttpTimeoutException
                    || exception instanceof java.net.http.HttpConnectTimeoutException) {
                throw new AiMatchException("AI_NETWORK_ERROR",
                        "AI 服务连接失败，将自动重试", true);
            }
            if (exception.getCause() instanceof java.io.IOException) {
                throw new AiMatchException("AI_NETWORK_ERROR",
                        "AI 服务连接失败，将自动重试", true);
            }
            log.error("AI 匹配出现未预期错误，类型={}", exception.getClass().getSimpleName());
            throw new AiMatchException("AI_UNEXPECTED_ERROR",
                    "AI 服务出现未预期错误，将自动重试", true);
        }
    }

    private Integer getUsageInt(JsonNode usage, String field) {
        JsonNode node = usage.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        int value;
        if (node.isIntegralNumber() && node.canConvertToInt()) {
            value = node.intValue();
        } else if (node.isTextual()) {
            // Numeric strings are accepted; anything else is rejected by the caller.
            try {
                value = Integer.parseInt(node.asText().trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        } else {
            // Floats, booleans, objects and arrays are all rejected.
            return null;
        }
        return value < 0 ? null : value;
    }

    private MatchResponse validateAndBuild(
            JsonNode json, Integer inputTokens, Integer outputTokens, int durationMs
    ) {
        // Unknown fields are model-controlled content → stable error message, non-retryable.
        Iterator<String> fieldNames = json.fieldNames();
        while (fieldNames.hasNext()) {
            String field = fieldNames.next();
            if (!field.equals("score") && !field.equals("summary")
                    && !field.equals("strengths") && !field.equals("risks")
                    && !field.equals("greeting")) {
                throw new AiMatchException("AI_RESPONSE_INVALID",
                        "AI 响应包含未预期字段", false);
            }
        }

        // Score: required, 0-100 integer. Violations are non-retryable.
        if (!json.has("score") || !json.get("score").isInt()) {
            throw new AiMatchException("AI_RESPONSE_INVALID",
                    "AI 响应缺少有效的 score 字段", false);
        }
        int score = json.get("score").asInt();
        if (score < 0 || score > 100) {
            throw new AiMatchException("AI_RESPONSE_INVALID",
                    "AI 响应 score 不在 0-100 之间", false);
        }

        // Summary: required string, at most 2000 Unicode code points. Over-limit rejects.
        if (!json.has("summary") || !json.get("summary").isTextual()) {
            throw new AiMatchException("AI_RESPONSE_INVALID",
                    "AI 响应缺少 summary 字段", false);
        }
        String summary = json.get("summary").asText().trim();
        if (summary.isEmpty()) {
            throw new AiMatchException("AI_RESPONSE_INVALID",
                    "AI 响应 summary 不能为空", false);
        }
        if (summary.codePointCount(0, summary.length()) > MAX_SUMMARY_CODE_POINTS) {
            throw new AiMatchException("AI_RESPONSE_INVALID",
                    "AI 响应 summary 超过 " + MAX_SUMMARY_CODE_POINTS + " 字符", false);
        }

        // Strengths: optional array of strings, at most 5 items of at most 200 code points.
        List<String> strengths = validateStringArray(json, "strengths");

        // Risks: same rules as strengths.
        List<String> risks = validateStringArray(json, "risks");

        // Greeting: optional string, at most 60 Unicode code points. Over-limit rejects.
        String greeting = null;
        if (json.has("greeting") && !json.get("greeting").isNull()) {
            JsonNode greetingNode = json.get("greeting");
            if (!greetingNode.isTextual()) {
                throw new AiMatchException("AI_RESPONSE_INVALID",
                        "AI 响应 greeting 字段不是字符串", false);
            }
            String rawGreeting = greetingNode.asText().trim();
            if (!rawGreeting.isEmpty()) {
                if (rawGreeting.codePointCount(0, rawGreeting.length()) > MAX_GREETING_CODE_POINTS) {
                    throw new AiMatchException("AI_RESPONSE_INVALID",
                            "AI 响应 greeting 超过 " + MAX_GREETING_CODE_POINTS + " 字符", false);
                }
                greeting = rawGreeting;
            }
        }

        return new MatchResponse(
                score, summary, strengths, risks, greeting,
                properties.getProvider(), properties.getModel(), properties.getPromptVersion(),
                inputTokens, outputTokens, durationMs
        );
    }

    private List<String> validateStringArray(JsonNode json, String field) {
        if (!json.has(field) || json.get(field).isNull()) {
            return List.of();
        }
        JsonNode array = json.get(field);
        if (!array.isArray()) {
            throw new AiMatchException("AI_RESPONSE_INVALID",
                    "AI 响应 " + field + " 字段不是数组", false);
        }
        if (array.size() > MAX_ITEM_COUNT) {
            throw new AiMatchException("AI_RESPONSE_INVALID",
                    "AI 响应 " + field + " 超过 " + MAX_ITEM_COUNT + " 项", false);
        }
        List<String> items = new ArrayList<>();
        for (JsonNode item : array) {
            if (!item.isTextual()) {
                throw new AiMatchException("AI_RESPONSE_INVALID",
                        "AI 响应 " + field + " 包含非字符串项", false);
            }
            String value = item.asText().trim();
            if (value.isEmpty()) {
                continue;
            }
            if (value.codePointCount(0, value.length()) > MAX_ITEM_CODE_POINTS) {
                throw new AiMatchException("AI_RESPONSE_INVALID",
                        "AI 响应 " + field + " 单项超过 " + MAX_ITEM_CODE_POINTS + " 字符", false);
            }
            items.add(value);
        }
        return List.copyOf(items);
    }

    private String buildSystemPrompt() {
        return """
                你是一位资深的招聘 AI 分析专家。你的任务是根据岗位描述和求职者简历，给出岗位匹配分析。

                请严格按照以下 JSON 格式返回结果，不要包含任何 Markdown 格式标记：
                {
                  "score": <0到100的整数，表示岗位与简历的综合匹配度>,
                  "summary": "<2000字以内的中文匹配分析总结>",
                  "strengths": ["<优势1>", "<优势2>", ...],  (最多5条，每条200字以内)
                  "risks": ["<风险1>", "<风险2>", ...],  (最多5条，每条200字以内)
                  "greeting": "<60个Unicode字符以内的中文问候语>"
                }

                分析要点：
                1. 技术栈匹配度（核心技术、框架、工具链）
                2. 工作经验匹配度（年限、行业、项目经验）
                3. 学历和语言能力匹配度
                4. 地点匹配度
                5. 薪资范围匹配度
                6. 综合竞争力评估

                注意：不要输出求职者的姓名、电话号码、邮箱、身份证号等个人隐私信息。
                """;
    }

    private String buildUserPrompt(MatchRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 岗位信息\n");
        sb.append("**岗位名称**：").append(sanitizeForPrompt(request.jobTitle())).append("\n");
        sb.append("**公司名称**：").append(sanitizeForPrompt(request.companyName())).append("\n");
        if (request.jobDescription() != null && !request.jobDescription().isBlank()) {
            String desc = request.jobDescription();
            if (desc.length() > 8000) {
                desc = desc.substring(0, 8000);
            }
            sb.append("**岗位描述**：\n").append(sanitizeForPrompt(desc)).append("\n");
        }
        if (request.targetTitles() != null && !request.targetTitles().isEmpty()) {
            sb.append("**目标岗位**：").append(sanitizeItems(request.targetTitles())).append("\n");
        }
        if (request.preferredCompanies() != null && !request.preferredCompanies().isEmpty()) {
            sb.append("**优先公司**：").append(sanitizeItems(request.preferredCompanies())).append("\n");
        }
        if (request.excludedCompanies() != null && !request.excludedCompanies().isEmpty()) {
            sb.append("**排除公司**：").append(sanitizeItems(request.excludedCompanies())).append("\n");
        }
        if (request.excludedKeywords() != null && !request.excludedKeywords().isEmpty()) {
            sb.append("**排除关键词**：").append(sanitizeItems(request.excludedKeywords())).append("\n");
        }
        sb.append("\n## 求职者简历\n");
        if (request.resumeText() != null && !request.resumeText().isBlank()) {
            // PII sanitization already applied by MatchWorker before calling this method,
            // but we re-sanitize here as a defense-in-depth measure.
            String text = sanitizeForPrompt(request.resumeText());
            if (text.length() > 16000) {
                text = text.substring(0, 16000);
            }
            sb.append(text);
        } else {
            sb.append("（未提供简历文本）\n");
        }

        return sb.toString();
    }

    /**
     * Remove PII from text before sending to the AI model.
     * Handles phone numbers, ID numbers, email addresses, and labelled detailed addresses.
     */
    static String sanitizeForPrompt(String text) {
        if (text == null) {
            return "";
        }
        // Chinese mobile phone numbers
        String result = text.replaceAll("1[3-9]\\d{9}", "[手机号已隐藏]");
        // International phone numbers
        result = result.replaceAll("\\+\\d{1,3}[\\s-]?\\d{3,14}", "[国际号码已隐藏]");
        // 18-digit Chinese ID numbers
        result = result.replaceAll("\\d{6}(19|20)\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx]",
                "[身份证号已隐藏]");
        // Email addresses
        result = result.replaceAll("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}",
                "[邮箱已隐藏]");
        // Labelled residential/contact addresses. Stop at the line boundary so
        // unrelated resume sections remain useful for matching.
        result = result.replaceAll(
                "(?m)(?i)(现居住地|现住址|家庭住址|联系地址|详细地址|住址|address)\\s*[:：]\\s*[^\\r\\n]{4,120}",
                "$1：[详细住址已隐藏]"
        );
        return result;
    }

    private static String sanitizeItems(List<String> items) {
        return items.stream().map(OpenAiMatchClient::sanitizeForPrompt).collect(java.util.stream.Collectors.joining("、"));
    }
}
