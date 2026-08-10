package ai.nubase.postgrest.multidb;

import ai.nubase.common.enums.DatabaseInitStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@Testcontainers(disabledWithoutDocker = true)
class DatabaseConfigRepositoryProvisioningLeasePostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate jdbcTemplate;
    private DatabaseConfigRepository repository;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("DROP TABLE IF EXISTS public.database_configs");
        jdbcTemplate.execute("""
                CREATE TABLE public.database_configs (
                    db_key VARCHAR(50) PRIMARY KEY,
                    db_name VARCHAR(100),
                    description TEXT,
                    app_code VARCHAR(100),
                    app_name VARCHAR(255),
                    schema_name VARCHAR(100),
                    init_status VARCHAR(32),
                    init_message TEXT,
                    init_started_at TIMESTAMP,
                    init_completed_at TIMESTAMP,
                    health_status VARCHAR(20),
                    enabled BOOLEAN DEFAULT TRUE,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    service_role_token TEXT,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V14__fence_project_provisioning_leases.sql"),
                new ClassPathResource("db/migration/V15__fence_legacy_project_provisioning_workers.sql"))
                .execute(dataSource);
        repository = new DatabaseConfigRepository(jdbcTemplate, mock(EncryptionService.class));
    }

    @Test
    void onlyOneWorkerClaimsAnActiveLeaseAndAnExpiredLeaseCanBeTakenOver() throws Exception {
        insertProject("db-demo");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        List<Future<Optional<DatabaseConfigRepository.InitializationLease>>> attempts;
        try {
            attempts = List.of(
                    workers.submit(() -> claimAfterSignal(ready, start)),
                    workers.submit(() -> claimAfterSignal(ready, start)));
            ready.await();
            start.countDown();
            List<DatabaseConfigRepository.InitializationLease> claimed = attempts.stream()
                    .map(this::get)
                    .flatMap(Optional::stream)
                    .toList();
            assertThat(claimed).hasSize(1);
        } finally {
            workers.shutdownNow();
        }

        DatabaseConfigRepository.InitializationLease first = attempts.stream()
                .map(this::get)
                .flatMap(Optional::stream)
                .findFirst()
                .orElseThrow();
        assertThat(repository.tryStartInitialization("db-demo", Duration.ofMinutes(15)))
                .isEmpty();

        jdbcTemplate.update("""
                UPDATE public.database_configs
                SET init_lease_expires_at = NOW() - INTERVAL '1 second'
                WHERE db_key = ?
                """, "db-demo");

        DatabaseConfigRepository.InitializationLease second = repository
                .tryStartInitialization("db-demo", Duration.ofMinutes(15))
                .orElseThrow();
        assertThat(second.token()).isNotEqualTo(first.token());
        assertThat(second.previousStatus()).isEqualTo(DatabaseInitStatus.INITIALIZING.name());
        assertThat(second.startedAt()).isAfterOrEqualTo(first.startedAt());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT enabled FROM public.database_configs WHERE db_key = 'db-demo'
                """, Boolean.class)).isFalse();
    }

    @Test
    void legacyInitializingRowWaitsForTheConfiguredTimeoutBeforeTakeover() {
        jdbcTemplate.update("""
                INSERT INTO public.database_configs (
                    db_key, init_status, init_started_at, enabled)
                VALUES (?, ?, NOW(), TRUE)
                """, "db-legacy", DatabaseInitStatus.INITIALIZING.name());

        assertThat(repository.tryStartInitialization(
                "db-legacy", Duration.ofMinutes(15))).isEmpty();

        jdbcTemplate.update("""
                UPDATE public.database_configs
                SET init_started_at = NOW() - INTERVAL '16 minutes'
                WHERE db_key = ?
                """, "db-legacy");
        assertThat(repository.tryStartInitialization(
                "db-legacy", Duration.ofMinutes(15))).isPresent();
    }

    @Test
    void supersededWorkerCannotHeartbeatCompleteOrFailTheNewWorkersLease() {
        insertProject("db-demo");
        DatabaseConfigRepository.InitializationLease first = repository
                .tryStartInitialization("db-demo", Duration.ofMinutes(15))
                .orElseThrow();
        jdbcTemplate.update("""
                UPDATE public.database_configs
                SET init_lease_expires_at = NOW() - INTERVAL '1 second'
                WHERE db_key = ?
                """, "db-demo");
        DatabaseConfigRepository.InitializationLease second = repository
                .tryStartInitialization("db-demo", Duration.ofMinutes(15))
                .orElseThrow();

        assertThat(repository.renewInitializationLease(
                "db-demo", first.token(), Duration.ofMinutes(15))).isFalse();
        assertThat(repository.completeInitialization(
                "db-demo", first.token(), "stale success")).isFalse();
        assertThat(repository.failInitialization(
                "db-demo", first.token(), "stale failure")).isFalse();

        assertThat(repository.renewInitializationLease(
                "db-demo", second.token(), Duration.ofMinutes(15))).isTrue();
        assertThat(repository.completeInitialization(
                "db-demo", second.token(), "current success")).isTrue();
        assertThat(jdbcTemplate.queryForMap("""
                SELECT init_status, init_message, enabled,
                       init_lease_token, init_lease_expires_at
                FROM public.database_configs
                WHERE db_key = 'db-demo'
                """))
                .containsEntry("init_status", DatabaseInitStatus.INITIALIZED.name())
                .containsEntry("init_message", "current success")
                .containsEntry("enabled", true)
                .containsEntry("init_lease_token", null)
                .containsEntry("init_lease_expires_at", null);
    }

    @Test
    void supersededWorkerCannotOverwriteTheNewWorkersFailure() {
        insertProject("db-demo");
        DatabaseConfigRepository.InitializationLease first = repository
                .tryStartInitialization("db-demo", Duration.ofMinutes(15))
                .orElseThrow();
        jdbcTemplate.update("""
                UPDATE public.database_configs
                SET init_lease_expires_at = NOW() - INTERVAL '1 second'
                WHERE db_key = ?
                """, "db-demo");
        DatabaseConfigRepository.InitializationLease second = repository
                .tryStartInitialization("db-demo", Duration.ofMinutes(15))
                .orElseThrow();

        assertThat(repository.failInitialization(
                "db-demo", second.token(), "current failure")).isTrue();
        assertThat(repository.completeInitialization(
                "db-demo", first.token(), "stale success")).isFalse();
        assertThat(repository.failInitialization(
                "db-demo", first.token(), "stale failure")).isFalse();

        assertThat(jdbcTemplate.queryForMap("""
                SELECT init_status, init_message, enabled,
                       init_lease_token, init_lease_expires_at
                FROM public.database_configs
                WHERE db_key = 'db-demo'
                """))
                .containsEntry("init_status", DatabaseInitStatus.INIT_FAILED.name())
                .containsEntry("init_message", "current failure")
                .containsEntry("enabled", false)
                .containsEntry("init_lease_token", null)
                .containsEntry("init_lease_expires_at", null);
    }

    @Test
    void heartbeatRefreshesTheLegacyLeaseTimestampAndRejectsUnfencedTerminalWrites() {
        insertProject("db-demo");
        DatabaseConfigRepository.InitializationLease lease = repository
                .tryStartInitialization("db-demo", Duration.ofMinutes(15))
                .orElseThrow();
        jdbcTemplate.update("""
                UPDATE public.database_configs
                SET init_started_at = NOW() - INTERVAL '16 minutes'
                WHERE db_key = ?
                """, "db-demo");
        Instant staleStartedAt = jdbcTemplate.queryForObject("""
                SELECT init_started_at FROM public.database_configs WHERE db_key = 'db-demo'
                """, (rs, rowNum) -> rs.getTimestamp(1).toInstant());

        assertThat(repository.renewInitializationLease(
                "db-demo", lease.token(), Duration.ofMinutes(15))).isTrue();
        Instant renewedStartedAt = jdbcTemplate.queryForObject("""
                SELECT init_started_at FROM public.database_configs WHERE db_key = 'db-demo'
                """, (rs, rowNum) -> rs.getTimestamp(1).toInstant());
        assertThat(renewedStartedAt).isAfter(staleStartedAt);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE public.database_configs
                SET init_status = ?
                WHERE db_key = ?
                """, DatabaseInitStatus.INITIALIZED.name(), "db-demo"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT init_status, init_lease_token
                FROM public.database_configs
                WHERE db_key = 'db-demo'
                """))
                .containsEntry("init_status", DatabaseInitStatus.INITIALIZING.name())
                .containsEntry("init_lease_token", lease.token());
    }

    @Test
    void fencedGenerationRejectsAnOldWorkersTerminalWriteAfterTheLeaseIsCleared() {
        insertProject("db-demo");
        DatabaseConfigRepository.InitializationLease lease = repository
                .tryStartInitialization("db-demo", Duration.ofMinutes(15))
                .orElseThrow();

        assertThat(repository.completeInitialization(
                "db-demo", lease.token(), "current success")).isTrue();
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE public.database_configs
                SET init_status = ?,
                    init_message = ?
                WHERE db_key = ?
                """, DatabaseInitStatus.INIT_FAILED.name(), "stale legacy failure", "db-demo"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbcTemplate.queryForMap("""
                SELECT init_status, init_message, init_fence_version, enabled,
                       init_lease_token, init_lease_expires_at
                FROM public.database_configs
                WHERE db_key = 'db-demo'
                """))
                .containsEntry("init_status", DatabaseInitStatus.INITIALIZED.name())
                .containsEntry("init_message", "current success")
                .containsEntry("init_fence_version", 2L)
                .containsEntry("enabled", true)
                .containsEntry("init_lease_token", null)
                .containsEntry("init_lease_expires_at", null);
    }

    @Test
    void provisioningVisibilityIsSafeForLegacyWritersAndAllowsManualPause() {
        jdbcTemplate.update("""
                INSERT INTO public.database_configs (db_key, init_status, enabled)
                VALUES (?, ?, TRUE)
                """, "db-legacy", DatabaseInitStatus.PENDING_INIT.name());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT enabled FROM public.database_configs WHERE db_key = ?
                """, Boolean.class, "db-legacy")).isFalse();

        jdbcTemplate.update("""
                UPDATE public.database_configs
                SET init_status = ?
                WHERE db_key = ?
                """, DatabaseInitStatus.INITIALIZED.name(), "db-legacy");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT enabled FROM public.database_configs WHERE db_key = ?
                """, Boolean.class, "db-legacy")).isTrue();

        jdbcTemplate.update("""
                UPDATE public.database_configs
                SET enabled = FALSE
                WHERE db_key = ?
                """, "db-legacy");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT enabled FROM public.database_configs WHERE db_key = ?
                """, Boolean.class, "db-legacy")).isFalse();
    }

    private void insertProject(String dbKey) {
        jdbcTemplate.update("""
                INSERT INTO public.database_configs (db_key, init_status, enabled)
                VALUES (?, ?, TRUE)
                """, dbKey, DatabaseInitStatus.PENDING_INIT.name());
    }

    private Optional<DatabaseConfigRepository.InitializationLease> claimAfterSignal(
            CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        return repository.tryStartInitialization("db-demo", Duration.ofMinutes(15));
    }

    private Optional<DatabaseConfigRepository.InitializationLease> get(
            Future<Optional<DatabaseConfigRepository.InitializationLease>> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new AssertionError("Concurrent lease claim failed", e);
        }
    }
}
