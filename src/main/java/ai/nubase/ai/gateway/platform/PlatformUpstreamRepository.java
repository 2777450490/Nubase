package ai.nubase.ai.gateway.platform;

import ai.nubase.common.enums.ApiProvider;
import ai.nubase.postgrest.multidb.EncryptionService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Metadata-DB access for platform-level unified upstreams
 * ({@code public.ai_gateway_platform_upstreams}).
 *
 * <p>The {@code auth_token} is encrypted at rest via {@link EncryptionService}; rows returned from
 * here carry the decrypted token in {@link PlatformUpstream#getAuthToken()} for server-side
 * forwarding only.</p>
 */
@Slf4j
@Repository
public class PlatformUpstreamRepository {

    private static final String COLUMNS =
            "id, name, provider, base_url, auth_token_encrypted, channel_code, supported_models, "
            + "chat_completions_path, is_default, is_active, timeout_ms, max_retries, priority, "
            + "max_input_tokens, description, created_at, updated_at";

    private final JdbcTemplate metadataJdbcTemplate;
    private final EncryptionService encryptionService;
    private final ObjectMapper objectMapper;

    public PlatformUpstreamRepository(
            @Qualifier("metadataJdbcTemplate") JdbcTemplate metadataJdbcTemplate,
            EncryptionService encryptionService,
            ObjectMapper objectMapper) {
        this.metadataJdbcTemplate = metadataJdbcTemplate;
        this.encryptionService = encryptionService;
        this.objectMapper = objectMapper;
    }

    public List<PlatformUpstream> findAllActive() {
        return metadataJdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM public.ai_gateway_platform_upstreams "
                        + "WHERE is_active = TRUE ORDER BY priority ASC, id ASC",
                mapper);
    }

    /**
     * Reads only non-secret fields required by the public model catalog.
     *
     * <p>This intentionally avoids selecting or decrypting upstream credentials.</p>
     */
    public List<CatalogModelSource> findAllActiveCatalogModels() {
        return metadataJdbcTemplate.query(
                "SELECT provider, supported_models FROM public.ai_gateway_platform_upstreams "
                        + "WHERE is_active = TRUE ORDER BY priority ASC, id ASC",
                (rs, rowNum) -> new CatalogModelSource(
                        ApiProvider.fromString(rs.getString("provider")),
                        fromJson(rs.getString("supported_models"))));
    }

    public List<PlatformUpstream> findAll() {
        return metadataJdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM public.ai_gateway_platform_upstreams "
                        + "ORDER BY priority ASC, id ASC",
                mapper);
    }

    public Optional<PlatformUpstream> findById(Long id) {
        List<PlatformUpstream> rows = metadataJdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM public.ai_gateway_platform_upstreams WHERE id = ?",
                mapper, id);
        return rows.stream().findFirst();
    }

    /**
     * Insert (id == null) or update a platform upstream. When {@code authToken} is null on an
     * update the existing encrypted token is preserved (blank/absent = "leave unchanged").
     */
    public PlatformUpstream save(PlatformUpstream u) {
        String modelsJson = toJson(u.getSupportedModels());
        String providerName = (u.getProvider() == null ? ApiProvider.CLAUDE : u.getProvider()).name();

        if (u.getId() == null) {
            String encrypted = encrypt(u.getAuthToken());
            Long id = metadataJdbcTemplate.queryForObject(
                    "INSERT INTO public.ai_gateway_platform_upstreams "
                            + "(name, provider, base_url, auth_token_encrypted, channel_code, supported_models, "
                            + " chat_completions_path, is_default, is_active, timeout_ms, max_retries, priority, "
                            + " max_input_tokens, description) "
                            + "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
                    Long.class,
                    u.getName(), providerName, u.getBaseUrl(), encrypted, u.getChannelCode(), modelsJson,
                    u.getChatCompletionsPath(), bool(u.getIsDefault(), false), bool(u.getIsActive(), true),
                    intOr(u.getTimeoutMs(), 60000), intOr(u.getMaxRetries(), 2), intOr(u.getPriority(), 100),
                    u.getMaxInputTokens(), u.getDescription());
            u.setId(id);
            return findById(id).orElse(u);
        }

        if (u.getAuthToken() != null && !u.getAuthToken().isBlank()) {
            metadataJdbcTemplate.update(
                    "UPDATE public.ai_gateway_platform_upstreams SET "
                            + "name = ?, provider = ?, base_url = ?, auth_token_encrypted = ?, channel_code = ?, "
                            + "supported_models = ?::jsonb, chat_completions_path = ?, is_default = ?, is_active = ?, "
                            + "timeout_ms = ?, max_retries = ?, priority = ?, max_input_tokens = ?, description = ?, "
                            + "updated_at = NOW() WHERE id = ?",
                    u.getName(), providerName, u.getBaseUrl(), encrypt(u.getAuthToken()), u.getChannelCode(),
                    modelsJson, u.getChatCompletionsPath(), bool(u.getIsDefault(), false), bool(u.getIsActive(), true),
                    intOr(u.getTimeoutMs(), 60000), intOr(u.getMaxRetries(), 2), intOr(u.getPriority(), 100),
                    u.getMaxInputTokens(), u.getDescription(), u.getId());
        } else {
            metadataJdbcTemplate.update(
                    "UPDATE public.ai_gateway_platform_upstreams SET "
                            + "name = ?, provider = ?, base_url = ?, channel_code = ?, "
                            + "supported_models = ?::jsonb, chat_completions_path = ?, is_default = ?, is_active = ?, "
                            + "timeout_ms = ?, max_retries = ?, priority = ?, max_input_tokens = ?, description = ?, "
                            + "updated_at = NOW() WHERE id = ?",
                    u.getName(), providerName, u.getBaseUrl(), u.getChannelCode(),
                    modelsJson, u.getChatCompletionsPath(), bool(u.getIsDefault(), false), bool(u.getIsActive(), true),
                    intOr(u.getTimeoutMs(), 60000), intOr(u.getMaxRetries(), 2), intOr(u.getPriority(), 100),
                    u.getMaxInputTokens(), u.getDescription(), u.getId());
        }
        return findById(u.getId()).orElse(u);
    }

    public void deleteById(Long id) {
        metadataJdbcTemplate.update("DELETE FROM public.ai_gateway_platform_upstreams WHERE id = ?", id);
    }

    private final RowMapper<PlatformUpstream> mapper = (ResultSet rs, int rowNum) -> {
        Integer maxInput = (Integer) rs.getObject("max_input_tokens");
        Timestamp created = rs.getTimestamp("created_at");
        Timestamp updated = rs.getTimestamp("updated_at");
        return PlatformUpstream.builder()
                .id(rs.getLong("id"))
                .name(rs.getString("name"))
                .provider(ApiProvider.fromString(rs.getString("provider")))
                .baseUrl(rs.getString("base_url"))
                .authToken(decrypt(rs.getString("auth_token_encrypted")))
                .channelCode(rs.getString("channel_code"))
                .supportedModels(fromJson(rs.getString("supported_models")))
                .chatCompletionsPath(rs.getString("chat_completions_path"))
                .isDefault(rs.getBoolean("is_default"))
                .isActive(rs.getBoolean("is_active"))
                .timeoutMs(rs.getInt("timeout_ms"))
                .maxRetries(rs.getInt("max_retries"))
                .priority(rs.getInt("priority"))
                .maxInputTokens(maxInput)
                .description(rs.getString("description"))
                .createdAt(created == null ? null : created.toInstant())
                .updatedAt(updated == null ? null : updated.toInstant())
                .build();
    };

    private String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return null;
        }
        try {
            return encryptionService.encrypt(plaintext);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt platform upstream auth token", e);
        }
    }

    private String decrypt(String encrypted) {
        if (encrypted == null || encrypted.isBlank()) {
            return null;
        }
        return encryptionService.decryptIfEncrypted(encrypted);
    }

    private String toJson(List<String> models) {
        try {
            return objectMapper.writeValueAsString(models == null ? List.of() : models);
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<String> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            log.warn("Failed to parse platform upstream supported_models: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private static boolean bool(Boolean v, boolean dflt) {
        return v == null ? dflt : v;
    }

    private static int intOr(Integer v, int dflt) {
        return v == null ? dflt : v;
    }

    public record CatalogModelSource(
            ApiProvider provider,
            List<String> supportedModels
    ) {
        public CatalogModelSource {
            supportedModels = supportedModels == null ? List.of() : List.copyOf(supportedModels);
        }
    }
}
