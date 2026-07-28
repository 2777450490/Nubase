package ai.nubase.ai.gateway.catalog;

import java.math.BigDecimal;
import java.util.List;

public final class PublicModelCatalogDtos {

    private PublicModelCatalogDtos() {
    }

    public record CatalogResponse(
            List<ModelEntry> data,
            GatewayEndpoints endpoints
    ) {
    }

    public record ModelEntry(
            String slug,
            String name,
            String provider,
            List<String> protocols,
            Pricing pricing
    ) {
    }

    public record Pricing(
            String currency,
            BigDecimal inputPer1M,
            BigDecimal outputPer1M,
            BigDecimal cacheCreationPer1M,
            BigDecimal cacheReadPer1M
    ) {
    }

    public record GatewayEndpoints(
            OpenAIEndpoints openAI,
            AnthropicEndpoints anthropic
    ) {
    }

    public record OpenAIEndpoints(
            String basePath,
            String chatCompletionsPath,
            String responsesPath,
            String modelsPath
    ) {
    }

    public record AnthropicEndpoints(
            String basePath,
            String messagesPath,
            String countTokensPath,
            String modelsPath
    ) {
    }
}
