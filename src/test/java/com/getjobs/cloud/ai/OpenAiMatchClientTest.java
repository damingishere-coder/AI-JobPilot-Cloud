package com.getjobs.cloud.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiMatchClientTest {

    private HttpServer server;
    private AiMatchProperties properties;
    private ObjectMapper objectMapper;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.setExecutor(null);
        server.start();

        properties = new AiMatchProperties();
        properties.setEnabled(true);
        properties.setApiKey("test-key");
        properties.setModel("gpt-4.1-mini");
        properties.setProvider("openai");
        properties.setBaseUrl("http://127.0.0.1:" + port);
        properties.setPromptVersion("v1");

        objectMapper = new ObjectMapper();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    // ---- Helper ----

    private OpenAiMatchClient createClient() {
        return new OpenAiMatchClient(properties, objectMapper);
    }

    private void respond(int status, String body) {
        server.createContext("/chat/completions", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
    }

    // ---- Happy path ----

    @Test
    void parsesValidResponseAndReturnsMatchResponse() {
        respond(200, """
                {
                  "choices": [{
                    "message": {
                      "content": "{\\"score\\":85,\\"summary\\":\\"匹配度较高\\",\\"strengths\\":[\\"技术匹配\\"],\\"risks\\":[\\"薪资略低\\"],\\"greeting\\":\\"您好\\"}"
                    }
                  }],
                  "usage": {
                    "prompt_tokens": 500,
                    "completion_tokens": 200,
                    "total_tokens": 700
                  }
                }
                """);

        AiMatchClient.MatchRequest request = basicRequest();

        AiMatchClient.MatchResponse response = createClient().analyze(request);
        assertThat(response.score()).isEqualTo(85);
        assertThat(response.summary()).isEqualTo("匹配度较高");
        assertThat(response.strengths()).containsExactly("技术匹配");
        assertThat(response.risks()).containsExactly("薪资略低");
        assertThat(response.greeting()).isEqualTo("您好");
        assertThat(response.inputTokens()).isEqualTo(500);
        assertThat(response.outputTokens()).isEqualTo(200);
        assertThat(response.modelProvider()).isEqualTo("openai");
        assertThat(response.modelName()).isEqualTo("gpt-4.1-mini");
    }

    @Test
    void handlesMissingOptionalFieldsGracefully() {
        respond(200, """
                {
                  "choices": [{
                    "message": {
                      "content": "{\\"score\\":70,\\"summary\\":\\"基本匹配\\"}"
                    }
                  }],
                  "usage": {
                    "prompt_tokens": 300,
                    "completion_tokens": 100,
                    "total_tokens": 400
                  }
                }
                """);

        AiMatchClient.MatchResponse response = createClient().analyze(basicRequest());
        assertThat(response.score()).isEqualTo(70);
        assertThat(response.strengths()).isEmpty();
        assertThat(response.risks()).isEmpty();
        assertThat(response.greeting()).isNull();
    }

    // ---- Usage validation ----

    @Test
    void allowsMissingUsageFieldWithNullTokens() {
        respond(200, """
                {
                  "choices": [{
                    "message": {
                      "content": "{\\"score\\":85,\\"summary\\":\\"匹配度高\\"}"
                    }
                  }]
                }
                """);

        AiMatchClient.MatchResponse response = createClient().analyze(basicRequest());
        assertThat(response.score()).isEqualTo(85);
        assertThat(response.inputTokens()).isNull();
        assertThat(response.outputTokens()).isNull();
    }

    @Test
    void allowsNullUsageFieldWithNullTokens() {
        respond(200, """
                {
                  "choices": [{
                    "message": {
                      "content": "{\\"score\\":85,\\"summary\\":\\"匹配度高\\"}"
                    }
                  }],
                  "usage": null
                }
                """);

        AiMatchClient.MatchResponse response = createClient().analyze(basicRequest());
        assertThat(response.inputTokens()).isNull();
        assertThat(response.outputTokens()).isNull();
    }

    @Test
    void rejectsWhenUsageFieldIsNotAnObject() {
        respond(200, """
                {
                  "choices": [{
                    "message": {
                      "content": "{\\"score\\":85,\\"summary\\":\\"匹配度高\\"}"
                    }
                  }],
                  "usage": "not-an-object"
                }
                """);

        assertRejectedAsInvalid(basicRequest());
    }

    @Test
    void rejectsWhenUsageTokenFieldsAreWrongType() {
        respond(200, """
                {
                  "choices": [{
                    "message": {
                      "content": "{\\"score\\":85,\\"summary\\":\\"匹配度高\\"}"
                    }
                  }],
                  "usage": {
                    "prompt_tokens": "not-a-number",
                    "completion_tokens": 200,
                    "total_tokens": 300
                  }
                }
                """);

        assertRejectedAsInvalid(basicRequest());
    }

    @Test
    void rejectsWhenUsageTokenFieldsAreFractional() {
        respond(200, """
                {
                  "choices": [{
                    "message": {
                      "content": "{\\"score\\":85,\\"summary\\":\\"匹配度高\\"}"
                    }
                  }],
                  "usage": {
                    "prompt_tokens": 12.5,
                    "completion_tokens": 200,
                    "total_tokens": 300
                  }
                }
                """);

        assertRejectedAsInvalid(basicRequest());
    }

    @Test
    void acceptsNumericStringTokenFields() {
        respond(200, """
                {
                  "choices": [{
                    "message": {
                      "content": "{\\"score\\":85,\\"summary\\":\\"匹配度高\\"}"
                    }
                  }],
                  "usage": {
                    "prompt_tokens": "300",
                    "completion_tokens": "100",
                    "total_tokens": "400"
                  }
                }
                """);

        AiMatchClient.MatchResponse response = createClient().analyze(basicRequest());
        assertThat(response.inputTokens()).isEqualTo(300);
        assertThat(response.outputTokens()).isEqualTo(100);
    }

    @Test
    void rejectsWhenUsageIsMissingOneTokenField() {
        respond(200, """
                {
                  "choices": [{
                    "message": {
                      "content": "{\\"score\\":85,\\"summary\\":\\"匹配度高\\"}"
                    }
                  }],
                  "usage": {
                    "prompt_tokens": 300,
                    "total_tokens": 400
                  }
                }
                """);

        assertRejectedAsInvalid(basicRequest());
    }

    @Test
    void rejectsWhenUsageTokensAreNegative() {
        respond(200, """
                {
                  "choices": [{
                    "message": {
                      "content": "{\\"score\\":85,\\"summary\\":\\"匹配度高\\"}"
                    }
                  }],
                  "usage": {
                    "prompt_tokens": -1,
                    "completion_tokens": 200,
                    "total_tokens": 300
                  }
                }
                """);

        assertRejectedAsInvalid(basicRequest());
    }

    // ---- Content structure validation (all non-retryable) ----

    @Test
    void rejectsUnparseableContentAsNonRetryable() {
        respond(200, """
                {
                  "choices": [{
                    "message": {
                      "content": "this is not json at all"
                    }
                  }],
                  "usage": {
                    "prompt_tokens": 300,
                    "completion_tokens": 100,
                    "total_tokens": 400
                  }
                }
                """);

        assertThatThrownBy(() -> createClient().analyze(basicRequest()))
                .isInstanceOf(AiMatchException.class)
                .satisfies(e -> {
                    AiMatchException ex = (AiMatchException) e;
                    assertThat(ex.code()).isEqualTo("AI_RESPONSE_INVALID");
                    assertThat(ex.retryable()).isFalse();
                });
    }

    @Test
    void rejectsContentThatIsNotJsonObject() {
        respond(200, """
                {
                  "choices": [{
                    "message": {
                      "content": "[\\"score\\",85]"
                    }
                  }],
                  "usage": {
                    "prompt_tokens": 300,
                    "completion_tokens": 100,
                    "total_tokens": 400
                  }
                }
                """);

        assertThatThrownBy(() -> createClient().analyze(basicRequest()))
                .isInstanceOf(AiMatchException.class)
                .satisfies(e -> {
                    AiMatchException ex = (AiMatchException) e;
                    assertThat(ex.code()).isEqualTo("AI_RESPONSE_INVALID");
                    assertThat(ex.retryable()).isFalse();
                });
    }

    @Test
    void rejectsUnknownFieldsAsNonRetryable() {
        respond(200, """
                {
                  "choices": [{
                    "message": {
                      "content": "{\\"score\\":85,\\"summary\\":\\"匹配度高\\",\\"unknownField\\":\\"someValue\\"}"
                    }
                  }],
                  "usage": {
                    "prompt_tokens": 300,
                    "completion_tokens": 100,
                    "total_tokens": 400
                  }
                }
                """);

        assertThatThrownBy(() -> createClient().analyze(basicRequest()))
                .isInstanceOf(AiMatchException.class)
                .satisfies(e -> {
                    AiMatchException ex = (AiMatchException) e;
                    assertThat(ex.code()).isEqualTo("AI_RESPONSE_INVALID");
                    assertThat(ex.retryable()).isFalse();
                    // Model-controlled content must not be echoed into error messages
                    assertThat(ex.getMessage()).doesNotContain("unknownField", "someValue");
                });
    }

    // ---- Array validation: over-limit / non-string items reject, never truncate ----

    @Test
    void rejectsStrengthItemOver200CodePoints() {
        String longItem = "A".repeat(250);
        respond(200, """
                {
                  "choices": [{
                    "message": {
                      "content": "{\\"score\\":85,\\"summary\\":\\"匹配度高\\",\\"strengths\\":[\\"%s\\"]}"
                    }
                  }],
                  "usage": {
                    "prompt_tokens": 300,
                    "completion_tokens": 100,
                    "total_tokens": 400
                  }
                }
                """.formatted(longItem.replace("\"", "\\\"")));

        assertRejectedAsInvalid(basicRequest());
    }

    @Test
    void rejectsStrengthsOverFiveItems() {
        respond(200, """
                {
                  "choices": [{
                    "message": {
                      "content": "{\\"score\\":85,\\"summary\\":\\"匹配度高\\",\\"strengths\\":[\\"a\\",\\"b\\",\\"c\\",\\"d\\",\\"e\\",\\"f\\",\\"g\\"]}"
                    }
                  }],
                  "usage": {
                    "prompt_tokens": 300,
                    "completion_tokens": 100,
                    "total_tokens": 400
                  }
                }
                """);

        assertRejectedAsInvalid(basicRequest());
    }

    @Test
    void rejectsNonStringArrayItem() {
        respond(200, """
                {
                  "choices": [{
                    "message": {
                      "content": "{\\"score\\":85,\\"summary\\":\\"匹配度高\\",\\"risks\\":[\\"风险一\\",42]}"
                    }
                  }],
                  "usage": {
                    "prompt_tokens": 300,
                    "completion_tokens": 100,
                    "total_tokens": 400
                  }
                }
                """);

        assertRejectedAsInvalid(basicRequest());
    }

    // ---- Length validation: over-limit rejects, never truncates ----

    @Test
    void rejectsSummaryOver2000CodePoints() {
        String longSummary = "好".repeat(2500);
        respond(200, """
                {
                  "choices": [{
                    "message": {
                      "content": "{\\"score\\":85,\\"summary\\":\\"%s\\"}"
                    }
                  }],
                  "usage": {
                    "prompt_tokens": 300,
                    "completion_tokens": 100,
                    "total_tokens": 400
                  }
                }
                """.formatted(longSummary));

        assertRejectedAsInvalid(basicRequest());
    }

    @Test
    void rejectsGreetingOver60CodePoints() {
        respond(200, """
                {
                  "choices": [{
                    "message": {
                      "content": "{\\"score\\":85,\\"summary\\":\\"匹配度高\\",\\"greeting\\":\\"%s\\"}"
                    }
                  }],
                  "usage": {
                    "prompt_tokens": 300,
                    "completion_tokens": 100,
                    "total_tokens": 400
                  }
                }
                """.formatted("您好！".repeat(30)));

        assertRejectedAsInvalid(basicRequest());
    }

    @Test
    void rejectsNonStringGreeting() {
        respond(200, """
                {
                  "choices": [{
                    "message": {
                      "content": "{\\"score\\":85,\\"summary\\":\\"匹配度高\\",\\"greeting\\":42}"
                    }
                  }],
                  "usage": {
                    "prompt_tokens": 300,
                    "completion_tokens": 100,
                    "total_tokens": 400
                  }
                }
                """);

        assertRejectedAsInvalid(basicRequest());
    }

    // ---- Score validation (non-retryable) ----

    @Test
    void rejectsScoreBelowZeroAsNonRetryable() {
        respond(200, """
                {
                  "choices": [{
                    "message": {
                      "content": "{\\"score\\":-5,\\"summary\\":\\"匹配度分析\\"}"
                    }
                  }],
                  "usage": {
                    "prompt_tokens": 300,
                    "completion_tokens": 100,
                    "total_tokens": 400
                  }
                }
                """);

        assertThatThrownBy(() -> createClient().analyze(basicRequest()))
                .isInstanceOf(AiMatchException.class)
                .satisfies(e -> {
                    AiMatchException ex = (AiMatchException) e;
                    assertThat(ex.retryable()).isFalse();
                });
    }

    @Test
    void rejectsScoreAbove100AsNonRetryable() {
        respond(200, """
                {
                  "choices": [{
                    "message": {
                      "content": "{\\"score\\":120,\\"summary\\":\\"匹配度分析\\"}"
                    }
                  }],
                  "usage": {
                    "prompt_tokens": 300,
                    "completion_tokens": 100,
                    "total_tokens": 400
                  }
                }
                """);

        assertThatThrownBy(() -> createClient().analyze(basicRequest()))
                .isInstanceOf(AiMatchException.class)
                .satisfies(e -> {
                    AiMatchException ex = (AiMatchException) e;
                    assertThat(ex.retryable()).isFalse();
                });
    }

    @Test
    void rejectsNonIntegerScoreAsNonRetryable() {
        respond(200, """
                {
                  "choices": [{
                    "message": {
                      "content": "{\\"score\\":85.5,\\"summary\\":\\"匹配度分析\\"}"
                    }
                  }],
                  "usage": {
                    "prompt_tokens": 300,
                    "completion_tokens": 100,
                    "total_tokens": 400
                  }
                }
                """);

        assertThatThrownBy(() -> createClient().analyze(basicRequest()))
                .isInstanceOf(AiMatchException.class)
                .satisfies(e -> {
                    AiMatchException ex = (AiMatchException) e;
                    assertThat(ex.retryable()).isFalse();
                });
    }

    // ---- PII sanitization ----

    @Test
    void sanitizesPiiForPromptRemovesSensitiveData() {
        String text = "联系人李四，手机13912345678，email user@company.com\n现住址：广东省深圳市南山区科技园1号1001室";
        String sanitized = OpenAiMatchClient.sanitizeForPrompt(text);
        assertThat(sanitized).contains("[手机号已隐藏]");
        assertThat(sanitized).contains("[邮箱已隐藏]");
        assertThat(sanitized).contains("[详细住址已隐藏]");
        assertThat(sanitized).doesNotContain("科技园1号1001室");
    }

    @Test
    void sanitizesEveryDynamicPromptFieldBeforeNetworkSend() {
        AtomicReference<String> requestBody = new AtomicReference<>();
        server.createContext("/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = """
                    {"choices":[{"message":{"content":"{\\"score\\":80,\\"summary\\":\\"匹配\\"}"}}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        String phone = "13912345678";
        String email = "secret@example.com";
        AiMatchClient.MatchRequest request = new AiMatchClient.MatchRequest(
                "Java工程师 " + phone,
                "示例公司 " + email,
                "联系地址：广东省深圳市南山区科技园1号1001室",
                "手机" + phone + "，邮箱" + email,
                List.of("后端 " + phone),
                List.of("优先 " + email),
                List.of("排除 " + phone),
                List.of("详细地址：广东省深圳市福田区深南大道100号")
        );

        createClient().analyze(request);

        assertThat(requestBody.get())
                .doesNotContain(phone, email, "科技园1号1001室", "深南大道100号")
                .contains("[手机号已隐藏]", "[邮箱已隐藏]", "[详细住址已隐藏]");
    }

    // ---- Helper methods ----

    private void assertRejectedAsInvalid(AiMatchClient.MatchRequest request) {
        assertThatThrownBy(() -> createClient().analyze(request))
                .isInstanceOf(AiMatchException.class)
                .satisfies(e -> {
                    AiMatchException ex = (AiMatchException) e;
                    assertThat(ex.code()).isEqualTo("AI_RESPONSE_INVALID");
                    assertThat(ex.retryable()).isFalse();
                });
    }

    private AiMatchClient.MatchRequest basicRequest() {
        return new AiMatchClient.MatchRequest(
                "Java工程师", "示例公司", "岗位描述", "简历文本",
                java.util.List.of(), java.util.List.of(),
                java.util.List.of(), java.util.List.of()
        );
    }
}
