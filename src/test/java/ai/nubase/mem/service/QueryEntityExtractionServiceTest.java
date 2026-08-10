package ai.nubase.mem.service;

import ai.nubase.mem.config.MemProperties;
import ai.nubase.mem.llm.ChatLLMProvider;
import ai.nubase.mem.llm.ChatRequest;
import ai.nubase.mem.llm.LLMException;
import ai.nubase.mem.llm.LLMProviderRegistry;
import ai.nubase.mem.service.FactExtractionService.ExtractedEntity;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class QueryEntityExtractionServiceTest {

    private LLMProviderRegistry registry;
    private ChatLLMProvider chat;
    private PromptLoader prompts;
    private MemProperties props;
    private MemConfigResolver resolver;
    private QueryEntityExtractionService svc;

    @BeforeEach
    void setUp() throws Exception {
        registry = mock(LLMProviderRegistry.class);
        chat = mock(ChatLLMProvider.class);
        when(registry.chat()).thenReturn(chat);
        when(chat.isAvailable()).thenReturn(true);
        prompts = new PromptLoader();
        prompts.load();
        props = new MemProperties();
        resolver = mock(MemConfigResolver.class);
        when(resolver.searchEntityBoostEnabled()).thenReturn(props.getSearch().isEntityBoostEnabled());
        svc = new QueryEntityExtractionService(registry, prompts, new ObjectMapper(), props, resolver);
    }

    @Test
    void providerUnavailable_returnsEmptyWithoutLlmCall() {
        when(chat.isAvailable()).thenReturn(false);
        List<ExtractedEntity> entities = svc.extract("who is John?");
        assertThat(entities).isEmpty();
        verify(chat, never()).chat(any());
    }

    @Test
    void parsesEntitiesFromJson() {
        when(chat.chat(any(ChatRequest.class))).thenReturn(
                "{\"entities\":[{\"text\":\"John\",\"type\":\"person\"},{\"text\":\"Tokyo\",\"type\":\"location\"}]}");

        List<ExtractedEntity> entities = svc.extract("who is John in Tokyo?");

        assertThat(entities).hasSize(2);
        assertThat(entities.get(0).getText()).isEqualTo("John");
        assertThat(entities.get(1).getType()).isEqualTo("location");
    }

    @Test
    void disabledByConfigSkipsLlmCall() {
        props.getSearch().setEntityBoostEnabled(false);
        when(resolver.searchEntityBoostEnabled()).thenReturn(false);

        List<ExtractedEntity> entities = svc.extract("who is John?");

        assertThat(entities).isEmpty();
        verify(chat, never()).chat(any());
    }

    @Test
    void emptyQueryReturnsEmpty() {
        assertThat(svc.extract(null)).isEmpty();
        assertThat(svc.extract("")).isEmpty();
        assertThat(svc.extract("   ")).isEmpty();
        verify(chat, never()).chat(any());
    }

    @Test
    void llmFailureLogsOnlySafeMetadata(CapturedOutput output) {
        String query = "query-input-private-sentinel";
        String failure = "query-provider-private-sentinel";
        when(chat.chat(any(ChatRequest.class))).thenThrow(new LLMException(failure));

        List<ExtractedEntity> entities = svc.extract(query);

        assertThat(entities).isEmpty();
        assertThat(output.getAll())
                .contains("errorType=LLMException", "queryChars=" + query.length())
                .doesNotContain(query, failure);
    }

    @Test
    void malformedResponseLogsOnlySafeMetadata(CapturedOutput output) {
        String sentinel = "query-response-private-sentinel";
        when(chat.chat(any(ChatRequest.class))).thenReturn(sentinel);

        assertThat(svc.extract("anything")).isEmpty();
        assertThat(output.getAll())
                .contains("Failed to parse query-entity JSON", "errorType=", "responseChars=")
                .doesNotContain(sentinel);
    }

    @Test
    void emptyEntitiesArrayReturnsEmpty() {
        when(chat.chat(any(ChatRequest.class))).thenReturn("{\"entities\":[]}");
        assertThat(svc.extract("the weather today")).isEmpty();
    }
}
