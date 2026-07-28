package ai.nubase.ai.gateway.billing;

import java.util.UUID;

public final class GatewayRequestContext {

    public static final String RESPONSE_HEADER = "x-nubase-request-id";
    public static final String REQUEST_ATTRIBUTE = GatewayRequestContext.class.getName() + ".requestId";

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private GatewayRequestContext() {
    }

    public static void set(UUID requestId) {
        CURRENT.set(requestId);
    }

    public static UUID current() {
        return CURRENT.get();
    }

    public static String currentOrNewString() {
        UUID requestId = CURRENT.get();
        return (requestId == null ? UUID.randomUUID() : requestId).toString();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
