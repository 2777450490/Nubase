package ai.nubase.ai.gateway.billing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class BillingReconciliationJob {

    private final BillingRepository repository;
    private final BillingProperties properties;

    @Scheduled(fixedDelayString = "${nubase.ai-gateway.billing.reconcile-scan-ms:60000}")
    public void markExpiredReservations() {
        if (!properties.isEnabled()) {
            return;
        }
        Instant cutoff = Instant.now().minus(properties.getReservationTtl());
        int marked = repository.markExpiredReservations(cutoff);
        if (marked > 0) {
            log.warn("billing.reconcile_scan marked={} cutoff={}", marked, cutoff);
        }
    }
}
