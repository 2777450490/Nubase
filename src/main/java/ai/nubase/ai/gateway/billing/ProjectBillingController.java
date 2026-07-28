package ai.nubase.ai.gateway.billing;

import ai.nubase.ai.gateway.billing.BillingModels.BillingAccount;
import ai.nubase.ai.gateway.billing.BillingModels.BillingRequest;
import ai.nubase.ai.gateway.billing.BillingModels.LedgerEntry;
import ai.nubase.ai.gateway.billing.BillingModels.RequestStatus;
import ai.nubase.auth.annotation.RequireServiceRole;
import ai.nubase.common.context.MultiTenancyContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/ai-gateway/admin/v1/billing")
@RequireServiceRole
@RequiredArgsConstructor
public class ProjectBillingController {

    private final BillingService billingService;

    @GetMapping("/account")
    public ResponseEntity<BillingAccount> account() {
        String appCode = requireAppCode();
        return billingService.findAccount(appCode)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/requests")
    public ResponseEntity<List<BillingRequest>> requests(
            @RequestParam(required = false) RequestStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(billingService.listRequests(requireAppCode(), status, page, size));
    }

    @GetMapping("/ledger")
    public ResponseEntity<List<LedgerEntry>> ledger(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(billingService.listLedger(requireAppCode(), page, size));
    }

    private String requireAppCode() {
        String appCode = MultiTenancyContext.getAppCode();
        if (appCode == null || appCode.isBlank()) {
            throw new IllegalStateException("Project context is required");
        }
        return appCode;
    }
}
