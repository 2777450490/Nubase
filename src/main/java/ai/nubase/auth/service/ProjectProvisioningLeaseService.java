package ai.nubase.auth.service;

import ai.nubase.postgrest.multidb.DatabaseConfigRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Owns the lifecycle of a physical-database initialization lease.
 *
 * <p>Repository compare-and-set operations are the correctness boundary. The scheduled heartbeat
 * prevents a healthy slow worker from expiring, while explicit renewals before large phases stop a
 * worker promptly after its token has been superseded.
 */
@Slf4j
@Service
public class ProjectProvisioningLeaseService {

    private static final long MAX_HEARTBEAT_INTERVAL_MILLIS = 30_000L;

    private final DatabaseConfigRepository repository;
    private final ScheduledExecutorService heartbeatExecutor;
    private final Duration leaseTimeout;
    private final long heartbeatIntervalMillis;

    public ProjectProvisioningLeaseService(
            DatabaseConfigRepository repository,
            @Qualifier("projectProvisioningLeaseHeartbeatExecutor")
            ScheduledExecutorService heartbeatExecutor,
            @Value("${nubase.project-provisioning.lease-timeout:15m}") Duration leaseTimeout) {
        if (leaseTimeout == null || leaseTimeout.isZero() || leaseTimeout.isNegative()
                || leaseTimeout.toMillis() == 0L) {
            throw new IllegalArgumentException(
                    "Project provisioning lease timeout must be at least one millisecond");
        }
        this.repository = repository;
        this.heartbeatExecutor = heartbeatExecutor;
        this.leaseTimeout = leaseTimeout;
        this.heartbeatIntervalMillis = Math.max(
                1L,
                Math.min(leaseTimeout.toMillis() / 3L, MAX_HEARTBEAT_INTERVAL_MILLIS));
    }

    public Optional<LeaseHandle> tryAcquire(String dbKey) {
        Optional<DatabaseConfigRepository.InitializationLease> claimed =
                repository.tryStartInitialization(dbKey, leaseTimeout);
        if (claimed.isEmpty()) {
            return Optional.empty();
        }

        LeaseHandle handle = new LeaseHandle(dbKey, claimed.orElseThrow());
        try {
            handle.startHeartbeat();
            return Optional.of(handle);
        } catch (RuntimeException schedulingFailure) {
            try {
                handle.fail("Unable to start project provisioning lease heartbeat");
            } catch (RuntimeException releaseFailure) {
                schedulingFailure.addSuppressed(releaseFailure);
            }
            throw schedulingFailure;
        }
    }

    public class LeaseHandle implements AutoCloseable {

        private final Object monitor = new Object();
        private final String dbKey;
        private final UUID token;
        private final String previousStatus;
        private boolean lost;
        private boolean terminal;
        private ScheduledFuture<?> heartbeatFuture;

        private LeaseHandle(
                String dbKey, DatabaseConfigRepository.InitializationLease initializationLease) {
            this.dbKey = dbKey;
            this.token = initializationLease.token();
            this.previousStatus = initializationLease.previousStatus();
        }

        public String previousStatus() {
            return previousStatus;
        }

        /** Renew synchronously before entering a substantial initialization phase. */
        public void renewOrThrow() {
            synchronized (monitor) {
                requireUsable();
                boolean renewed = repository.renewInitializationLease(dbKey, token, leaseTimeout);
                if (!renewed) {
                    markLost();
                    throw new LeaseLostException(dbKey);
                }
            }
        }

        public boolean complete(String message) {
            synchronized (monitor) {
                if (lost || terminal) {
                    return false;
                }
                boolean completed = repository.completeInitialization(dbKey, token, message);
                terminal = true;
                if (!completed) {
                    lost = true;
                }
                cancelHeartbeat();
                return completed;
            }
        }

        public boolean fail(String message) {
            synchronized (monitor) {
                if (lost || terminal) {
                    return false;
                }
                boolean failed = repository.failInitialization(dbKey, token, message);
                terminal = true;
                if (!failed) {
                    lost = true;
                }
                cancelHeartbeat();
                return failed;
            }
        }

        @Override
        public void close() {
            synchronized (monitor) {
                terminal = true;
                cancelHeartbeat();
            }
        }

        private void startHeartbeat() {
            synchronized (monitor) {
                heartbeatFuture = heartbeatExecutor.scheduleAtFixedRate(
                        this::heartbeat,
                        heartbeatIntervalMillis,
                        heartbeatIntervalMillis,
                        TimeUnit.MILLISECONDS);
            }
        }

        private void heartbeat() {
            synchronized (monitor) {
                if (lost || terminal) {
                    return;
                }
                try {
                    if (!repository.renewInitializationLease(dbKey, token, leaseTimeout)) {
                        log.warn("Project provisioning lease was superseded for dbKey={}", dbKey);
                        markLost();
                    }
                } catch (RuntimeException e) {
                    // A transient database error is not proof of ownership loss. The next heartbeat,
                    // an explicit phase renewal, or the fenced terminal update decides ownership.
                    log.warn("Project provisioning lease heartbeat failed for dbKey={}, errorType={}",
                            dbKey, e.getClass().getSimpleName());
                }
            }
        }

        private void requireUsable() {
            if (lost || terminal) {
                throw new LeaseLostException(dbKey);
            }
        }

        private void markLost() {
            lost = true;
            terminal = true;
            cancelHeartbeat();
        }

        private void cancelHeartbeat() {
            if (heartbeatFuture != null) {
                heartbeatFuture.cancel(false);
                heartbeatFuture = null;
            }
        }
    }

    public static final class LeaseLostException extends IllegalStateException {

        public LeaseLostException(String dbKey) {
            super("Project provisioning lease was lost for dbKey: " + dbKey);
        }
    }
}
