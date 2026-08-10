package ai.nubase.ai.gateway.platform;

import ai.nubase.ai.gateway.dto.ApiUsageRecord;
import ai.nubase.ai.gateway.dto.TokenUsage;
import ai.nubase.ai.gateway.entity.ApiKey;
import ai.nubase.common.context.MultiTenancyContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.UUID;

/**
 * Writes the platform's central usage ledger — one row per gateway call, keyed by (appCode, userId)
 * — in addition to the per-tenant logs. This is what lets the platform see who, in which app,
 * consumed how many tokens, and whether the request was served by the project's own custom upstream
 * or the platform's unified config ({@code upstream_source}).
 *
 * <p>appCode is read from {@link MultiTenancyContext} (always resolved for a gateway request) and
 * the routing source from {@link GatewayRoutingContext} (set when the upstream was chosen, on the
 * same request thread). Failures here never disrupt request handling or the per-tenant tracking.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformUsageTrackingService {

    private final PlatformUsageRepository repository;

    /**
     * Record one call in the platform ledger.
     *
     * @param record  the request/usage record (model, endpoint, status, tokens, ...)
     * @param keyRow  the resolved gateway key row (may be transient with a null id for user-JWT calls)
     * @param userId  the resolved caller identity (nullable)
     * @param costUsd computed cost in USD (stats only)
     * @param costCny computed cost in CNY (stats only)
     */
    public void track(ApiUsageRecord record, ApiKey keyRow, UUID userId,
                      BigDecimal costUsd, BigDecimal costCny) {
        try {
            String appCode = MultiTenancyContext.getAppCode();
            if (appCode == null || appCode.isBlank()) {
                // Without an appCode the platform ledger has no project dimension; skip rather than
                // write an unattributable row.
                log.debug("platform usage skipped: no appCode in context (requestId={})", record.getRequestId());
                return;
            }

            GatewayRoutingContext.Routing routing = GatewayRoutingContext.get();
            String source = routing != null ? routing.source().code()
                    : GatewayRoutingContext.Source.CUSTOM.code();
            String upstreamName = routing != null ? routing.upstreamName() : null;

            TokenUsage usage = record.getTokenUsage() == null ? TokenUsage.empty() : record.getTokenUsage();

            PlatformUsageEntry entry = PlatformUsageEntry.builder()
                    .appCode(appCode)
                    .userId(userId)
                    .apiKeyId(keyRow != null ? keyRow.getId() : null)
                    .requestId(record.getRequestId())
                    .model(record.getModel())
                    .provider(inferProvider(record.getModel()))
                    .endpoint(record.getEndpoint())
                    .method(record.getMethod())
                    .statusCode(record.getStatusCode())
                    .upstreamName(upstreamName)
                    .upstreamSource(source)
                    .inputTokens(usage.getInputTokens())
                    .outputTokens(usage.getOutputTokens())
                    .cacheCreationInputTokens(usage.getCacheCreationInputTokens())
                    .cacheReadInputTokens(usage.getCacheReadInputTokens())
                    .totalTokens(usage.getTotalTokens())
                    .costUsd(costUsd)
                    .costCny(costCny)
                    .durationMs(record.getDurationMs())
                    .errorMessage(record.getErrorMessage())
                    .build();

            repository.insertUsageLog(entry);
            repository.upsertDailyUsage(entry);

            log.info("platform_usage.persist appCode={} userId={} source={} model={} tokens={} costUsd={}",
                    appCode, userId, source, record.getModel(), usage.getTotalTokens(), costUsd);
        } catch (Exception e) {
            // Never let platform bookkeeping break the request or the per-tenant tracking.
            log.error("Failed to record platform usage: errorType={}",
                    e.getClass().getSimpleName());
        }
    }

    private String inferProvider(String model) {
        if (model == null) {
            return null;
        }
        String m = model.toLowerCase(Locale.ROOT);
        if (m.contains("claude") || m.startsWith("anthropic")) {
            return "CLAUDE";
        }
        if (m.contains("gpt") || m.startsWith("openai") || m.startsWith("o1") || m.startsWith("o3")) {
            return "OPENAI";
        }
        return null;
    }
}
