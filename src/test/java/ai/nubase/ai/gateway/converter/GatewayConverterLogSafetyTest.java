package ai.nubase.ai.gateway.converter;

import ai.nubase.ai.gateway.dto.openai.OpenAIChoice;
import ai.nubase.ai.gateway.dto.openai.OpenAIFunctionCall;
import ai.nubase.ai.gateway.dto.openai.OpenAIMessage;
import ai.nubase.ai.gateway.dto.openai.OpenAIResponse;
import ai.nubase.ai.gateway.dto.openai.OpenAIToolCall;
import ai.nubase.ai.gateway.testsupport.LogCapture;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayConverterLogSafetyTest {

    private static final String SENSITIVE_CONTENT = "converter-log-sensitive-content";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void malformedToolArgumentsAreNotWrittenToLogs() {
        OpenAIToClaudeConverter converter = new OpenAIToClaudeConverter(objectMapper);
        String malformedArguments = "{\"secret\":\"" + SENSITIVE_CONTENT;
        OpenAIResponse response = OpenAIResponse.builder()
                .choices(List.of(OpenAIChoice.builder()
                        .message(OpenAIMessage.builder()
                                .toolCalls(List.of(OpenAIToolCall.builder()
                                        .id("call_test")
                                        .function(OpenAIFunctionCall.builder()
                                                .name("test_tool")
                                                .arguments(malformedArguments)
                                                .build())
                                        .build()))
                                .build())
                        .finishReason("tool_calls")
                        .build()))
                .build();

        try (LogCapture logs = LogCapture.forClass(OpenAIToClaudeConverter.class)) {
            String converted = converter.convertResponse(response, "test-model");

            assertThat(converted).contains("\"input\":{}");
            assertThat(logs.formattedMessages())
                    .noneMatch(message -> message.contains(SENSITIVE_CONTENT))
                    .anyMatch(message -> message.contains("argumentBytes="));
        }
    }

    @Test
    void unexpectedContentLogsOnlyNodeType() throws Exception {
        ClaudeToOpenAIConverter converter = new ClaudeToOpenAIConverter(objectMapper);
        String request = "{\"model\":\"test-model\",\"messages\":[{\"role\":\"user\","
                + "\"content\":{\"secret\":\"" + SENSITIVE_CONTENT + "\"}}]}";

        try (LogCapture logs = LogCapture.forClass(ClaudeToOpenAIConverter.class)) {
            var converted = converter.convertRequest(request);

            assertThat(converted.getMessages().get(0).getContent()).contains(SENSITIVE_CONTENT);
            assertThat(logs.formattedMessages())
                    .noneMatch(message -> message.contains(SENSITIVE_CONTENT))
                    .anyMatch(message -> message.contains("nodeType=OBJECT"));
        }
    }

    @Test
    void missingContentIsHandledWithoutNullPointerException() throws Exception {
        ClaudeToOpenAIConverter converter = new ClaudeToOpenAIConverter(objectMapper);
        String request = "{\"model\":\"test-model\",\"messages\":[{\"role\":\"user\"}]}";

        try (LogCapture logs = LogCapture.forClass(ClaudeToOpenAIConverter.class)) {
            var converted = converter.convertRequest(request);

            assertThat(converted.getMessages().get(0).getContent()).isEmpty();
            assertThat(logs.formattedMessages())
                    .anyMatch(message -> message.contains("nodeType=missing"));
        }
    }
}
