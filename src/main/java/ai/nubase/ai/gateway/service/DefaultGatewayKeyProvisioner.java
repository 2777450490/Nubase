package ai.nubase.ai.gateway.service;

import ai.nubase.ai.gateway.util.GatewayKeyUtil;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/** Registers the existing project service-role key for AI Gateway usage tracking. */
@Component
public class DefaultGatewayKeyProvisioner {

    static final String DEFAULT_NAME = "Default service role key";
    static final String DEFAULT_DESCRIPTION = "Automatically registered project service-role key";

    /**
     * Registers the existing service-role credential by hash. No new credential is generated and
     * the JWT plaintext remains in metadata storage only.
     */
    public void provision(DataSource tenantDataSource, String serviceRoleKey) {
        if (tenantDataSource == null) {
            throw new IllegalArgumentException("tenantDataSource is required");
        }
        if (serviceRoleKey == null || serviceRoleKey.isBlank()) {
            throw new IllegalArgumentException("serviceRoleKey is required");
        }

        String normalizedKey = serviceRoleKey.trim();
        String keyHash = GatewayKeyUtil.sha256Hex(normalizedKey);
        JdbcTemplate jdbc = new JdbcTemplate(tenantDataSource);
        try {
            jdbc.update(
                    """
                    INSERT INTO ai_gateway.api_keys
                        (key_hash, key_prefix, name, description, scope, is_active)
                    SELECT ?, ?, ?, ?, 'all', TRUE
                    WHERE NOT EXISTS (
                        SELECT 1 FROM ai_gateway.api_keys WHERE key_hash = ?
                    )
                    """,
                    keyHash,
                    GatewayKeyUtil.displayPrefix(normalizedKey),
                    DEFAULT_NAME,
                    DEFAULT_DESCRIPTION,
                    keyHash);
        } catch (DuplicateKeyException ignored) {
            // Concurrent retries may race after the existence check. The unique key_hash wins.
        }
    }
}
