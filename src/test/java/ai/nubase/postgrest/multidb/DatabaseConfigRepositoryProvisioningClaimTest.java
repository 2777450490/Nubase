package ai.nubase.postgrest.multidb;

import ai.nubase.common.enums.DatabaseInitStatus;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatabaseConfigRepositoryProvisioningClaimTest {

    @Test
    void returnsTheDatabaseGeneratedLeaseWhenTheConditionalClaimWins() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        UUID token = UUID.fromString("11111111-1111-1111-1111-111111111111");
        DatabaseConfigRepository.InitializationLease expectedLease =
                new DatabaseConfigRepository.InitializationLease(
                        token,
                        DatabaseInitStatus.PENDING_INIT.name(),
                        Instant.parse("2026-08-10T01:00:00Z"),
                        Instant.parse("2026-08-10T01:15:00Z"));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(expectedLease), List.of());
        DatabaseConfigRepository repository = new DatabaseConfigRepository(
                jdbcTemplate,
                mock(EncryptionService.class));

        assertThat(repository.tryStartInitialization("db-demo", Duration.ofMinutes(15)))
                .contains(expectedLease);
        assertThat(repository.tryStartInitialization("db-demo", Duration.ofMinutes(15)))
                .isEqualTo(Optional.empty());
    }

    @Test
    void fencesHeartbeatCompletionAndFailureWithTheLeaseToken() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.update(anyString(), any(Object[].class)))
                .thenReturn(1, 0, 1);
        DatabaseConfigRepository repository = new DatabaseConfigRepository(
                jdbcTemplate,
                mock(EncryptionService.class));
        UUID token = UUID.fromString("22222222-2222-2222-2222-222222222222");

        assertThat(repository.renewInitializationLease(
                "db-demo", token, Duration.ofMinutes(15))).isTrue();
        assertThat(repository.completeInitialization(
                "db-demo", token, "Physical database initialized successfully")).isFalse();
        assertThat(repository.failInitialization(
                "db-demo", token, "Physical database initialization failed")).isTrue();

        verify(jdbcTemplate, times(3)).update(anyString(), any(Object[].class));
    }
}
