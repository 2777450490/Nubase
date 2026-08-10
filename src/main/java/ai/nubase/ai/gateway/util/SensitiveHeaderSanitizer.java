package ai.nubase.ai.gateway.util;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class SensitiveHeaderSanitizer {

    private static final Set<String> SENSITIVE_HEADER_NAMES = Set.of(
            "authorization",
            "proxy-authorization",
            "x-api-key",
            "api-key",
            "apikey",
            "auth",
            "x-auth",
            "cookie",
            "set-cookie"
    );
    private static final Set<String> SENSITIVE_NAME_FRAGMENTS = Set.of(
            "authorization",
            "authentication",
            "credential",
            "secret",
            "signature",
            "token",
            "api-key",
            "apikey",
            "auth-key",
            "authkey",
            "access-key",
            "accesskey",
            "private-key",
            "privatekey"
    );
    private static final Set<String> DIAGNOSTIC_HEADER_NAMES = Set.of(
            "request-id",
            "x-request-id",
            "correlation-id",
            "x-correlation-id",
            "traceparent",
            "x-b3-traceid",
            "x-b3-spanid",
            "x-b3-parentspanid",
            "x-b3-sampled",
            "x-b3-flags",
            "x-amzn-trace-id"
    );
    private static final Set<String> PERSISTED_REQUEST_HEADER_ALLOWLIST = Set.of(
            "user-agent",
            "anthropic-version",
            "anthropic-beta"
    );
    private static final Set<String> LOGGED_RESPONSE_HEADER_ALLOWLIST = Set.of(
            "content-type",
            "content-length",
            "retry-after",
            "cf-ray"
    );
    private static final String PRESENT_VALUE = "[present]";

    private SensitiveHeaderSanitizer() {
    }

    /**
     * Returns whether a header carries credential-like data and must not be forwarded.
     */
    public static boolean isSensitive(String headerName) {
        if (headerName == null || headerName.isBlank()) {
            return false;
        }
        String normalizedName = headerName.trim()
                .toLowerCase(Locale.ROOT)
                .replace('_', '-')
                .replace('.', '-');
        if (SENSITIVE_HEADER_NAMES.contains(normalizedName)) {
            return true;
        }
        return SENSITIVE_NAME_FRAGMENTS.stream().anyMatch(normalizedName::contains);
    }

    /**
     * Returns only explicitly approved header names for durable storage. Values are replaced with
     * a presence marker because all incoming values are caller-controlled.
     */
    public static Map<String, String> sanitizeForPersistence(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return Map.of();
        }
        Map<String, String> sanitized = new LinkedHashMap<>();
        headers.forEach((name, value) -> {
            if (isSafeForPersistence(name)) {
                // Header values are caller-controlled. Even an allowlisted diagnostic name can
                // carry a credential or user content, so durable metadata records presence only.
                sanitized.put(name, PRESENT_VALUE);
            }
        });
        return Collections.unmodifiableMap(sanitized);
    }

    private static boolean isSafeForPersistence(String headerName) {
        String normalizedName = normalize(headerName);
        return normalizedName != null
                && (DIAGNOSTIC_HEADER_NAMES.contains(normalizedName)
                || PERSISTED_REQUEST_HEADER_ALLOWLIST.contains(normalizedName));
    }

    /**
     * Returns whether an upstream response header is approved for diagnostic logging.
     */
    public static boolean isSafeForResponseLogging(String headerName) {
        String normalizedName = normalize(headerName);
        return normalizedName != null
                && (DIAGNOSTIC_HEADER_NAMES.contains(normalizedName)
                || LOGGED_RESPONSE_HEADER_ALLOWLIST.contains(normalizedName));
    }

    private static String normalize(String headerName) {
        if (headerName == null || headerName.isBlank()) {
            return null;
        }
        return headerName.trim().toLowerCase(Locale.ROOT);
    }
}
