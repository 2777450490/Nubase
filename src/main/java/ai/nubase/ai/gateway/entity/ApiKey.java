package ai.nubase.ai.gateway.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * AI Gateway credential stored in each project's {@code ai_gateway.api_keys} table.
 * Supports the existing project service-role JWT and random nbk credentials.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "api_keys", schema = "ai_gateway")
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private UUID userId;

    /** Legacy plaintext field. Service-role and random credentials remain hash-only. */
    @Column(name = "api_key", length = 255)
    private String apiKey;

    /** SHA-256 credential hash used for inbound lookup. */
    @Column(name = "key_hash", unique = true, length = 64)
    private String keyHash;

    @Column(name = "key_prefix", length = 32)
    private String keyPrefix;

    @Column(name = "name", length = 255)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "scope", length = 64)
    private String scope;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (isActive == null) isActive = Boolean.TRUE;
        if (scope == null) scope = "all";
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
