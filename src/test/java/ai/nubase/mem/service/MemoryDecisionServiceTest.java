package ai.nubase.mem.service;

import ai.nubase.mem.llm.ChatLLMProvider;
import ai.nubase.mem.llm.ChatRequest;
import ai.nubase.mem.llm.LLMException;
import ai.nubase.mem.llm.LLMProviderRegistry;
import ai.nubase.mem.service.MemoryDecisionService.Decision;
import ai.nubase.mem.service.MemoryDecisionService.ExistingMemory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies decision-JSON parsing, normalization (uppercase events), and fallbacks.
 */
@ExtendWith(OutputCaptureExtension.class)
class MemoryDecisionServiceTest {

    private LLMProviderRegistry registry;
    private ChatLLMProvider chatProvider;
    private PromptLoader prompts;
    private MemoryDecisionService svc;

    @BeforeEach
    void setUp() throws Exception {
        registry = mock(LLMProviderRegistry.class);
        chatProvider = mock(ChatLLMProvider.class);
        when(registry.chat()).thenReturn(chatProvider);
        when(chatProvider.isAvailable()).thenReturn(true);
        prompts = new PromptLoader();
        prompts.load();
        ObjectMapper objectMapper = new ObjectMapper();
        svc = new MemoryDecisionService(registry, prompts, objectMapper);
    }

    @Test
    void providerUnavailable_fallsBackToAllAdd_withoutLlmCall() {
        when(chatProvider.isAvailable()).thenReturn(false);

        List<Decision> result = svc.decide(List.of(), List.of("fact1", "fact2"));

        assertThat(result).hasSize(2);
        assertThat(result).allSatisfy(d -> assertThat(d.getEvent()).isEqualTo("ADD"));
        org.mockito.Mockito.verify(chatProvider, org.mockito.Mockito.never()).chat(any());
    }

    @Test
    void parsesAddUpdateDeleteNone() {
        when(chatProvider.chat(any(ChatRequest.class))).thenReturn("""
                {
                  "memory": [
                    {"id": "new_0", "text": "Lives in Tokyo", "event": "ADD"},
                    {"id": "11111111-1111-1111-1111-111111111111", "text": "Likes ramen", "event": "UPDATE", "old_memory": "Likes noodles"},
                    {"id": "22222222-2222-2222-2222-222222222222", "event": "DELETE"},
                    {"id": "33333333-3333-3333-3333-333333333333", "event": "NONE"}
                  ]
                }
                """);

        List<Decision> result = svc.decide(
                List.of(ExistingMemory.builder().id("e1").text("Likes noodles").build()),
                List.of("Lives in Tokyo", "Likes ramen", "old", "already known"));

        assertThat(result).hasSize(4);
        assertThat(result.get(0).getEvent()).isEqualTo("ADD");
        assertThat(result.get(1).getEvent()).isEqualTo("UPDATE");
        assertThat(result.get(1).getOldMemory()).isEqualTo("Likes noodles");
        assertThat(result.get(2).getEvent()).isEqualTo("DELETE");
        assertThat(result.get(3).getEvent()).isEqualTo("NONE");
    }

    @Test
    void normalizesEventCaseToUpperCase() {
        when(chatProvider.chat(any(ChatRequest.class))).thenReturn(
                "{\"memory\": [{\"id\": \"new_0\", \"text\": \"x\", \"event\": \"add\"}]}");

        List<Decision> result = svc.decide(List.of(), List.of("x"));

        assertThat(result.get(0).getEvent()).isEqualTo("ADD");
    }

    @Test
    void fallsBackToAddAllOnLLMExceptionWithoutLoggingProviderMessage(CapturedOutput output) {
        String sentinel = "decision-provider-private-sentinel";
        when(chatProvider.chat(any(ChatRequest.class)))
                .thenThrow(new LLMException(sentinel));

        List<Decision> result = svc.decide(List.of(), List.of("fact1", "fact2"));

        assertThat(result).hasSize(2);
        assertThat(result).allSatisfy(d -> assertThat(d.getEvent()).isEqualTo("ADD"));
        assertThat(result.get(0).getText()).isEqualTo("fact1");
        assertThat(result.get(1).getText()).isEqualTo("fact2");
        assertThat(output.getAll())
                .contains("errorType=LLMException")
                .doesNotContain(sentinel);
    }

    @Test
    void fallsBackOnMalformedJsonWithoutLoggingRawResponse(CapturedOutput output) {
        String sentinel = "decision-response-private-sentinel";
        when(chatProvider.chat(any(ChatRequest.class))).thenReturn(sentinel);

        List<Decision> result = svc.decide(List.of(), List.of("fact1"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getEvent()).isEqualTo("ADD");
        assertThat(result.get(0).getText()).isEqualTo("fact1");
        assertThat(output.getAll())
                .contains("errorType=JsonParseException", "responseChars=" + sentinel.length())
                .doesNotContain(sentinel);
    }

    @Test
    void fallsBackOnNonArrayMemoryWithoutLoggingRawResponse(CapturedOutput output) {
        String sentinel = "decision-field-private-sentinel";
        String raw = "{\"memory\":\"" + sentinel + "\"}";
        when(chatProvider.chat(any(ChatRequest.class))).thenReturn(raw);

        List<Decision> result = svc.decide(List.of(), List.of("fact1"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getEvent()).isEqualTo("ADD");
        assertThat(output.getAll())
                .contains("nodeType=STRING", "responseChars=" + raw.length())
                .doesNotContain(sentinel);
    }

    @Test
    void noFactsShortCircuitsWithoutLlmCall() {
        List<Decision> result = svc.decide(List.of(), List.of());
        assertThat(result).isEmpty();
        org.mockito.Mockito.verify(chatProvider, org.mockito.Mockito.never()).chat(any());
    }
}
