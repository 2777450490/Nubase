package ai.nubase.ai.gateway.billing;

import ai.nubase.ai.gateway.billing.BillingExceptions.BillingException;
import ai.nubase.ai.gateway.service.TokenCounterService;
import ai.nubase.common.context.MultiTenancyContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class BillingAdmissionFilter extends OncePerRequestFilter {

    private final BillingService billingService;
    private final BillingProperties properties;
    private final TokenCounterService tokenCounterService;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.startsWith("/v1/") || path.startsWith("/ai/") || path.startsWith("/openai/"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        UUID requestId = UUID.randomUUID();
        GatewayRequestContext.set(requestId);
        request.setAttribute(GatewayRequestContext.REQUEST_ATTRIBUTE, requestId.toString());
        response.setHeader(GatewayRequestContext.RESPONSE_HEADER, requestId.toString());

        boolean reserved = false;
        try {
            if (!billingService.isEnabled() || !isBillableJsonRequest(request)) {
                filterChain.doFilter(request, response);
                return;
            }

            CachedBodyHttpServletRequest cached = new CachedBodyHttpServletRequest(
                    request, properties.getMaximumRequestBytes());
            JsonNode root = parseJson(cached.body());
            String model = requiredText(root, "model");
            long maximumOutputTokens = outputLimit(root);
            String body = new String(cached.body(), StandardCharsets.UTF_8);
            int estimatedInputTokens = tokenCounterService.countTokens(body);
            String appCode = MultiTenancyContext.getAppCode();
            if (appCode == null || appCode.isBlank()) {
                throw new BillingException("project_context_missing", "Project context is required for billing");
            }

            billingService.reserve(
                    requestId,
                    appCode,
                    request.getHeader("Idempotency-Key"),
                    model,
                    request.getRequestURI(),
                    estimatedInputTokens,
                    maximumOutputTokens);
            reserved = true;
            filterChain.doFilter(cached, response);
        } catch (BillingException e) {
            if (reserved) {
                billingService.markAdmissionUncertain(requestId, "downstream_billing_exception_after_reservation");
                throw e;
            }
            writeBillingError(response, e);
        } catch (IllegalArgumentException e) {
            if (reserved) {
                billingService.markAdmissionUncertain(requestId, "downstream_invalid_request_after_reservation");
                throw e;
            }
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, "invalid_billing_request", e.getMessage());
        } catch (IOException | ServletException | RuntimeException e) {
            if (reserved) {
                billingService.markAdmissionUncertain(requestId, "downstream_exception_after_reservation");
            }
            throw e;
        } finally {
            GatewayRequestContext.clear();
        }
    }

    private boolean isBillableJsonRequest(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String contentType = request.getContentType();
        if (contentType == null || !contentType.toLowerCase().startsWith(MediaType.APPLICATION_JSON_VALUE)) {
            return false;
        }
        String path = request.getRequestURI();
        return path.endsWith("/messages")
                || path.endsWith("/messages/stream")
                || path.endsWith("/chat/completions")
                || path.endsWith("/responses")
                || path.endsWith("/responses/compact")
                || path.endsWith("/memories/trace_summarize");
    }

    private JsonNode parseJson(byte[] body) {
        try {
            return objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Request body must be valid JSON", e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read cached request body", e);
        }
    }

    private String requiredText(JsonNode root, String field) {
        JsonNode value = root == null ? null : root.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.asText().trim();
    }

    private long outputLimit(JsonNode root) {
        JsonNode value = root.get("max_output_tokens");
        if (value == null) {
            value = root.get("max_tokens");
        }
        if (value == null) {
            value = root.get("max_completion_tokens");
        }
        if (value == null || value.isNull()) {
            return properties.getDefaultMaxOutputTokens();
        }
        if (!value.canConvertToLong() || value.asLong() <= 0) {
            throw new IllegalArgumentException("max_tokens must be a positive integer");
        }
        return value.asLong();
    }

    private void writeBillingError(HttpServletResponse response, BillingException error) throws IOException {
        int status = switch (error.getCode()) {
            case "model_not_priced", "project_context_missing" -> HttpServletResponse.SC_BAD_REQUEST;
            case "duplicate_idempotency_key" -> HttpServletResponse.SC_CONFLICT;
            case "billing_account_not_found", "insufficient_balance" -> HttpServletResponse.SC_PAYMENT_REQUIRED;
            case "billing_account_unavailable" -> HttpServletResponse.SC_FORBIDDEN;
            default -> 422;
        };
        writeError(response, status, error.getCode(), error.getMessage());
    }

    private void writeError(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("type", code);
        detail.put("message", message);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("error", detail);
        payload.put("request_id", response.getHeader(GatewayRequestContext.RESPONSE_HEADER));
        objectMapper.writeValue(response.getWriter(), payload);
    }
}
