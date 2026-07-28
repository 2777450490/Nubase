package ai.nubase.ai.gateway.billing;

import ai.nubase.ai.gateway.billing.BillingExceptions.BillingException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice(assignableTypes = {BillingAdminController.class, ProjectBillingController.class})
public class BillingExceptionHandler {

    @ExceptionHandler(BillingException.class)
    public ResponseEntity<Map<String, Object>> billing(BillingException error) {
        HttpStatus status = switch (error.getCode()) {
            case "billing_account_not_found", "model_not_priced" -> HttpStatus.NOT_FOUND;
            case "duplicate_idempotency_key", "invalid_billing_request_state" -> HttpStatus.CONFLICT;
            case "insufficient_balance", "billing_account_unavailable" -> HttpStatus.UNPROCESSABLE_ENTITY;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(error(error.getCode(), error.getMessage()));
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
    public ResponseEntity<Map<String, Object>> invalid(Exception error) {
        return ResponseEntity.badRequest().body(error("invalid_request", error.getMessage()));
    }

    private Map<String, Object> error(String code, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("error", code);
        payload.put("message", message);
        return payload;
    }
}
