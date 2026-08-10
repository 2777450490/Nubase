package ai.nubase.auth.service;

import ai.nubase.common.enums.DatabaseInitStatus;
import ai.nubase.postgrest.multidb.DatabaseConfigRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectProvisioningLeaseServiceTest {

    private static final Duration LEASE_TIMEOUT = Duration.ofMinutes(15);
    private static final UUID FIRST_TOKEN =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SECOND_TOKEN =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void twoWorkersCannotOwnTheSameActiveLease() {
        DatabaseConfigRepository repository = mock(DatabaseConfigRepository.class);
        ScheduledExecutorService scheduler = scheduler();
        when(repository.tryStartInitialization("db-demo", LEASE_TIMEOUT))
                .thenReturn(Optional.of(lease(FIRST_TOKEN)), Optional.empty());
        ProjectProvisioningLeaseService firstWorker =
                new ProjectProvisioningLeaseService(repository, scheduler, LEASE_TIMEOUT);
        ProjectProvisioningLeaseService secondWorker =
                new ProjectProvisioningLeaseService(repository, scheduler, LEASE_TIMEOUT);

        Optional<ProjectProvisioningLeaseService.LeaseHandle> first =
                firstWorker.tryAcquire("db-demo");
        Optional<ProjectProvisioningLeaseService.LeaseHandle> second =
                secondWorker.tryAcquire("db-demo");

        assertThat(first).isPresent();
        assertThat(second).isEmpty();
        first.orElseThrow().close();
    }

    @Test
    void delayedOldWorkerCannotCompleteAfterItsTokenWasSuperseded() {
        DatabaseConfigRepository repository = mock(DatabaseConfigRepository.class);
        ScheduledExecutorService scheduler = scheduler();
        when(repository.tryStartInitialization("db-demo", LEASE_TIMEOUT))
                .thenReturn(Optional.of(lease(FIRST_TOKEN)), Optional.of(lease(SECOND_TOKEN)));
        when(repository.completeInitialization("db-demo", FIRST_TOKEN, "stale success"))
                .thenReturn(false);
        ProjectProvisioningLeaseService service =
                new ProjectProvisioningLeaseService(repository, scheduler, LEASE_TIMEOUT);

        ProjectProvisioningLeaseService.LeaseHandle oldWorker =
                service.tryAcquire("db-demo").orElseThrow();
        ProjectProvisioningLeaseService.LeaseHandle newWorker =
                service.tryAcquire("db-demo").orElseThrow();

        assertThat(oldWorker.complete("stale success")).isFalse();
        verify(repository).completeInitialization("db-demo", FIRST_TOKEN, "stale success");
        verify(repository, never()).completeInitialization(
                eq("db-demo"), eq(SECOND_TOKEN), any());
        newWorker.close();
    }

    @Test
    void delayedOldWorkerCannotFailAfterItsTokenWasSuperseded() {
        DatabaseConfigRepository repository = mock(DatabaseConfigRepository.class);
        ScheduledExecutorService scheduler = scheduler();
        when(repository.tryStartInitialization("db-demo", LEASE_TIMEOUT))
                .thenReturn(Optional.of(lease(FIRST_TOKEN)), Optional.of(lease(SECOND_TOKEN)));
        when(repository.failInitialization("db-demo", FIRST_TOKEN, "stale failure"))
                .thenReturn(false);
        ProjectProvisioningLeaseService service =
                new ProjectProvisioningLeaseService(repository, scheduler, LEASE_TIMEOUT);

        ProjectProvisioningLeaseService.LeaseHandle oldWorker =
                service.tryAcquire("db-demo").orElseThrow();
        ProjectProvisioningLeaseService.LeaseHandle newWorker =
                service.tryAcquire("db-demo").orElseThrow();

        assertThat(oldWorker.fail("stale failure")).isFalse();
        verify(repository).failInitialization("db-demo", FIRST_TOKEN, "stale failure");
        verify(repository, never()).failInitialization(
                eq("db-demo"), eq(SECOND_TOKEN), any());
        newWorker.close();
    }

    @Test
    void failedBackgroundHeartbeatFencesAllLaterWorkByThatHandle() {
        DatabaseConfigRepository repository = mock(DatabaseConfigRepository.class);
        ScheduledExecutorService scheduler = scheduler();
        when(repository.tryStartInitialization("db-demo", LEASE_TIMEOUT))
                .thenReturn(Optional.of(lease(FIRST_TOKEN)));
        when(repository.renewInitializationLease("db-demo", FIRST_TOKEN, LEASE_TIMEOUT))
                .thenReturn(false);
        ProjectProvisioningLeaseService service =
                new ProjectProvisioningLeaseService(repository, scheduler, LEASE_TIMEOUT);
        ProjectProvisioningLeaseService.LeaseHandle handle =
                service.tryAcquire("db-demo").orElseThrow();

        ArgumentCaptor<Runnable> heartbeat = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleAtFixedRate(
                heartbeat.capture(), anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS));
        heartbeat.getValue().run();

        assertThatThrownBy(handle::renewOrThrow)
                .isInstanceOf(ProjectProvisioningLeaseService.LeaseLostException.class);
        assertThat(handle.complete("stale success")).isFalse();
        assertThat(handle.fail("stale failure")).isFalse();
        verify(repository, never()).completeInitialization(any(), any(), any());
        verify(repository, never()).failInitialization(any(), any(), any());
    }

    private static ScheduledExecutorService scheduler() {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        doReturn(future).when(scheduler).scheduleAtFixedRate(
                any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS));
        return scheduler;
    }

    private static DatabaseConfigRepository.InitializationLease lease(UUID token) {
        return new DatabaseConfigRepository.InitializationLease(
                token,
                DatabaseInitStatus.PENDING_INIT.name(),
                Instant.parse("2026-08-10T01:00:00Z"),
                Instant.parse("2026-08-10T01:15:00Z"));
    }
}
