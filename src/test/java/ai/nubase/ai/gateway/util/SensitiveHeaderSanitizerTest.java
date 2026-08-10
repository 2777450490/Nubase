package ai.nubase.ai.gateway.util;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveHeaderSanitizerTest {

    @Test
    void keepsOnlyAllowlistedHeadersForPersistence() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "credential-value");
        headers.put("X-Client-API-Key", "client-key-value");
        headers.put("X-Goog-Api-Key", "google-key-value");
        headers.put("X-OpenAI-API-Key", "openai-key-value");
        headers.put("x-auth-key", "auth-key-value");
        headers.put("x-access-key", "access-key-value");
        headers.put("x-private-key", "private-key-value");
        headers.put("x-custom-header", "custom-value");
        headers.put("x-request-id", "request-123");
        headers.put("TraceParent", "00-trace-span-01");
        headers.put("tracestate", "vendor=opaque-value");
        headers.put("user-agent", "test-client");

        Map<String, String> sanitized =
                SensitiveHeaderSanitizer.sanitizeForPersistence(headers);

        assertThat(sanitized)
                .containsEntry("x-request-id", "[present]")
                .containsEntry("TraceParent", "[present]")
                .containsEntry("user-agent", "[present]")
                .doesNotContainKeys(
                        "Authorization",
                        "X-Client-API-Key",
                        "X-Goog-Api-Key",
                        "X-OpenAI-API-Key",
                        "x-auth-key",
                        "x-access-key",
                        "x-private-key",
                        "x-custom-header",
                        "tracestate");
        assertThat(sanitized.values())
                .doesNotContain("request-123", "00-trace-span-01", "test-client");
    }

    @Test
    void identifiesCredentialHeaderVariantsCaseInsensitively() {
        assertThat(List.of(
                        "Authorization",
                        "X-Client-API-Key",
                        "X-Goog-Api-Key",
                        "X-OpenAI-API-Key",
                        "x-auth-key",
                        "x-access-key",
                        "x-private-key",
                        "x-private_key",
                        "X-Credential",
                        "x-signature",
                        "x-session-token"))
                .allMatch(SensitiveHeaderSanitizer::isSensitive);

        assertThat(SensitiveHeaderSanitizer.isSensitive("x-request-id")).isFalse();
        assertThat(SensitiveHeaderSanitizer.isSensitive("traceparent")).isFalse();
        assertThat(SensitiveHeaderSanitizer.isSensitive("user-agent")).isFalse();
    }

    @Test
    void returnsEmptyImmutableMapForMissingHeaders() {
        assertThat(SensitiveHeaderSanitizer.sanitizeForPersistence(null)).isEmpty();
        assertThat(SensitiveHeaderSanitizer.sanitizeForPersistence(Map.of())).isEmpty();
    }
}
