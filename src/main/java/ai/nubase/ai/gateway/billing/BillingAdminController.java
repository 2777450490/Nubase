package ai.nubase.ai.gateway.billing;

import ai.nubase.ai.gateway.billing.BillingDtos.AccountUpsertRequest;
import ai.nubase.ai.gateway.billing.BillingDtos.BalanceAdjustmentRequest;
import ai.nubase.ai.gateway.billing.BillingDtos.PricePublishRequest;
import ai.nubase.ai.gateway.billing.BillingDtos.ReleaseRequest;
import ai.nubase.ai.gateway.billing.BillingModels.BillingAccount;
import ai.nubase.ai.gateway.billing.BillingModels.BillingRequest;
import ai.nubase.ai.gateway.billing.BillingModels.LedgerEntry;
import ai.nubase.ai.gateway.billing.BillingModels.PriceVersion;
import ai.nubase.ai.gateway.billing.BillingModels.RequestStatus;
import ai.nubase.ai.gateway.platform.PlatformUpstream;
import ai.nubase.ai.gateway.platform.PlatformUpstreamRepository;
import ai.nubase.postgrest.multidb.DatabaseConfigRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/ai-gateway/platform/v1/billing")
@RequiredArgsConstructor
public class BillingAdminController {

    private final BillingService billingService;
    private final PlatformUpstreamRepository upstreamRepository;
    private final DatabaseConfigRepository databaseConfigRepository;

    @GetMapping("/accounts")
    public ResponseEntity<List<BillingAccount>> accounts() {
        return ResponseEntity.ok(billingService.listAccounts());
    }

    @GetMapping("/accounts/{appCode}")
    public ResponseEntity<BillingAccount> account(@PathVariable String appCode) {
        return billingService.findAccount(appCode)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/accounts/{appCode}")
    public ResponseEntity<BillingAccount> upsertAccount(
            @PathVariable String appCode,
            @Valid @RequestBody AccountUpsertRequest body) {
        if (databaseConfigRepository.findByAppCode(appCode) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "project not found");
        }
        return ResponseEntity.ok(billingService.upsertAccount(
                appCode,
                body.currency(),
                body.creditLimit() == null ? BigDecimal.ZERO : body.creditLimit(),
                body.status()));
    }

    @PostMapping("/accounts/{appCode}/adjustments")
    public ResponseEntity<BillingAccount> adjustBalance(
            @PathVariable String appCode,
            @Valid @RequestBody BalanceAdjustmentRequest body,
            HttpServletRequest request) {
        return ResponseEntity.ok(billingService.adjustBalance(
                appCode,
                body.amount(),
                body.type(),
                body.idempotencyKey(),
                body.reason(),
                platformUserId(request)));
    }

    @GetMapping("/prices")
    public ResponseEntity<List<PriceVersion>> prices(
            @RequestParam(defaultValue = "true") boolean activeOnly) {
        return ResponseEntity.ok(billingService.listPrices(activeOnly));
    }

    @PostMapping("/prices")
    public ResponseEntity<PriceVersion> publishPrice(
            @Valid @RequestBody PricePublishRequest body,
            HttpServletRequest request) {
        PriceVersion price = billingService.publishPrice(
                body.model(),
                body.provider(),
                body.displayName(),
                body.currency(),
                body.inputPricePer1M(),
                body.outputPricePer1M(),
                body.cacheCreationPricePer1M(),
                body.cacheReadPricePer1M(),
                body.effectiveFrom(),
                platformUserId(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(price);
    }

    @GetMapping("/requests")
    public ResponseEntity<List<BillingRequest>> requests(
            @RequestParam(required = false) String appCode,
            @RequestParam(required = false) RequestStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(billingService.listRequests(appCode, status, page, size));
    }

    @GetMapping("/ledger")
    public ResponseEntity<List<LedgerEntry>> ledger(
            @RequestParam(required = false) String appCode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(billingService.listLedger(appCode, page, size));
    }

    @PostMapping("/requests/{requestId}/release")
    public ResponseEntity<Map<String, Object>> release(
            @PathVariable UUID requestId,
            @Valid @RequestBody ReleaseRequest body,
            HttpServletRequest request) {
        boolean released = billingService.release(requestId, body.reason(), platformUserId(request));
        if (!released) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "billing request not found");
        }
        return ResponseEntity.ok(Map.of("released", true, "requestId", requestId));
    }

    @GetMapping("/models/discovered")
    public ResponseEntity<List<Map<String, Object>>> discoveredModels() {
        Set<String> priced = new LinkedHashSet<>();
        for (PriceVersion price : billingService.listPrices(true)) {
            priced.add(price.normalizedModel());
        }

        Map<String, MutableDiscoveredModel> models = new LinkedHashMap<>();
        for (PlatformUpstream upstream : upstreamRepository.findAllActive()) {
            for (String model : upstream.getSupportedModels()) {
                if (model == null || model.isBlank()) {
                    continue;
                }
                String normalized = BillingService.normalizeModel(model);
                MutableDiscoveredModel discovered = models.computeIfAbsent(
                        normalized,
                        ignored -> new MutableDiscoveredModel(
                                model.trim(), normalized,
                                upstream.getProvider() == null ? null : upstream.getProvider().name()));
                discovered.channels.add(upstream.getChannelCode());
                discovered.upstreams.add(upstream.getName());
            }
        }

        List<Map<String, Object>> response = new ArrayList<>();
        models.values().stream()
                .sorted(Comparator.comparing(value -> value.normalizedModel))
                .forEach(value -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("model", value.model);
                    row.put("normalizedModel", value.normalizedModel);
                    row.put("provider", value.provider);
                    row.put("channels", value.channels.stream().filter(java.util.Objects::nonNull).sorted().toList());
                    row.put("upstreams", value.upstreams.stream().sorted().toList());
                    row.put("billingStatus", priced.contains(value.normalizedModel) ? "PRICED" : "UNPRICED");
                    response.add(row);
                });
        return ResponseEntity.ok(response);
    }

    private static UUID platformUserId(HttpServletRequest request) {
        Object value = request.getAttribute("platformUserId");
        if (value instanceof UUID uuid) {
            return uuid;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "platform identity is missing");
    }

    private static final class MutableDiscoveredModel {
        private final String model;
        private final String normalizedModel;
        private final String provider;
        private final Set<String> channels = new LinkedHashSet<>();
        private final Set<String> upstreams = new LinkedHashSet<>();

        private MutableDiscoveredModel(String model, String normalizedModel, String provider) {
            this.model = model;
            this.normalizedModel = normalizedModel;
            this.provider = provider == null ? null : provider.toUpperCase(Locale.ROOT);
        }
    }
}
