package ai.nubase.ai.gateway.catalog;

import ai.nubase.ai.gateway.billing.BillingModels.PriceVersion;
import ai.nubase.ai.gateway.billing.BillingService;
import ai.nubase.ai.gateway.catalog.PublicModelCatalogDtos.AnthropicEndpoints;
import ai.nubase.ai.gateway.catalog.PublicModelCatalogDtos.CatalogResponse;
import ai.nubase.ai.gateway.catalog.PublicModelCatalogDtos.GatewayEndpoints;
import ai.nubase.ai.gateway.catalog.PublicModelCatalogDtos.ModelEntry;
import ai.nubase.ai.gateway.catalog.PublicModelCatalogDtos.OpenAIEndpoints;
import ai.nubase.ai.gateway.catalog.PublicModelCatalogDtos.Pricing;
import ai.nubase.ai.gateway.platform.PlatformUpstreamRepository;
import ai.nubase.ai.gateway.platform.PlatformUpstreamRepository.CatalogModelSource;
import ai.nubase.common.enums.ApiProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class PublicModelCatalogService {

    private static final String WILDCARD_MODEL = "*";
    private static final String PUBLIC_PRICE_CURRENCY = "USD";
    private static final GatewayEndpoints ENDPOINTS = new GatewayEndpoints(
            new OpenAIEndpoints(
                    "/v1",
                    "/v1/chat/completions",
                    "/v1/responses",
                    "/v1/models"),
            new AnthropicEndpoints(
                    "",
                    "/v1/messages",
                    "/v1/messages/count_tokens",
                    "/v1/models"));

    private final PlatformUpstreamRepository platformUpstreamRepository;
    private final BillingService billingService;

    public CatalogResponse getCatalog() {
        Map<String, PriceVersion> prices = activeUsdPrices();
        Map<String, MutableCatalogModel> models = new LinkedHashMap<>();

        for (CatalogModelSource source : platformUpstreamRepository.findAllActiveCatalogModels()) {
            for (String rawModel : source.supportedModels()) {
                String model = trimToNull(rawModel);
                if (model == null || WILDCARD_MODEL.equals(model)) {
                    continue;
                }

                String normalizedModel = BillingService.normalizeModel(model);
                MutableCatalogModel catalogModel = models.computeIfAbsent(
                        normalizedModel,
                        ignored -> new MutableCatalogModel(model));
                catalogModel.protocols.add(toPublicProtocol(source.provider()));
            }
        }

        List<ModelEntry> entries = models.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> toEntry(entry.getValue(), prices.get(entry.getKey())))
                .toList();
        return new CatalogResponse(entries, ENDPOINTS);
    }

    private Map<String, PriceVersion> activeUsdPrices() {
        Map<String, PriceVersion> prices = new LinkedHashMap<>();
        for (PriceVersion price : billingService.listPrices(true)) {
            if (!PUBLIC_PRICE_CURRENCY.equalsIgnoreCase(price.currency())) {
                continue;
            }
            prices.putIfAbsent(price.normalizedModel(), price);
        }
        return prices;
    }

    private ModelEntry toEntry(MutableCatalogModel model, PriceVersion price) {
        List<String> protocols = model.protocols.stream()
                .sorted(Comparator.comparingInt(PublicModelCatalogService::protocolOrder))
                .toList();
        String displayName = price == null || trimToNull(price.displayName()) == null
                ? model.slug
                : price.displayName().trim();
        String provider = price == null || trimToNull(price.provider()) == null
                ? displayProvider(protocols)
                : displayProvider(price.provider());
        return new ModelEntry(
                model.slug,
                displayName,
                provider,
                protocols,
                toPricing(price));
    }

    private Pricing toPricing(PriceVersion price) {
        if (price == null) {
            return null;
        }
        return new Pricing(
                price.currency(),
                price.inputPricePer1M(),
                price.outputPricePer1M(),
                price.cacheCreationPricePer1M(),
                price.cacheReadPricePer1M());
    }

    private static String toPublicProtocol(ApiProvider provider) {
        return ApiProvider.OPENAI.equals(provider) ? "openai" : "anthropic";
    }

    private static int protocolOrder(String protocol) {
        return "openai".equals(protocol) ? 0 : 1;
    }

    private static String displayProvider(List<String> protocols) {
        if (protocols.size() > 1) {
            return "Multiple";
        }
        return protocols.isEmpty() ? "Unknown" : displayProvider(protocols.get(0));
    }

    private static String displayProvider(String provider) {
        if ("OPENAI".equalsIgnoreCase(provider) || "openai".equalsIgnoreCase(provider)) {
            return "OpenAI";
        }
        if ("CLAUDE".equalsIgnoreCase(provider) || "anthropic".equalsIgnoreCase(provider)) {
            return "Claude";
        }
        String normalized = provider.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty()
                ? "Unknown"
                : Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static final class MutableCatalogModel {
        private final String slug;
        private final Set<String> protocols = new LinkedHashSet<>();

        private MutableCatalogModel(String slug) {
            this.slug = slug;
        }
    }
}
