package ai.nubase.auth.service;

import ai.nubase.auth.dto.response.admin.InitDatabaseResponse;
import ai.nubase.common.enums.DatabaseInitStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

@Slf4j
@Service
public class ProjectProvisioningService {

    private final DatabaseInitService databaseInitService;
    private final ProjectOwnershipService projectOwnershipService;
    private final Executor executor;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public ProjectProvisioningService(
            DatabaseInitService databaseInitService,
            ProjectOwnershipService projectOwnershipService,
            @Qualifier("projectProvisioningExecutor") Executor executor) {
        this.databaseInitService = databaseInitService;
        this.projectOwnershipService = projectOwnershipService;
        this.executor = executor;
    }

    public Submission submit(String dbKey, String currentStatus, UUID ownerId) {
        if (DatabaseInitStatus.INITIALIZED.name().equalsIgnoreCase(currentStatus)) {
            return new Submission(SubmissionState.ALREADY_INITIALIZED);
        }
        if (!inFlight.add(dbKey)) {
            return new Submission(SubmissionState.ALREADY_RUNNING);
        }

        try {
            executor.execute(() -> provision(dbKey, ownerId));
            return new Submission(SubmissionState.QUEUED);
        } catch (RuntimeException e) {
            inFlight.remove(dbKey);
            throw e;
        }
    }

    public boolean isRunning(String dbKey) {
        return inFlight.contains(dbKey);
    }

    private void provision(String dbKey, UUID ownerId) {
        try {
            InitDatabaseResponse response = databaseInitService.initializePhysicalDatabase(dbKey);
            if (response.isSuccess()) {
                recordOwnership(ownerId, dbKey);
            } else {
                log.warn("Asynchronous project provisioning failed for dbKey={}: {}",
                        dbKey, response.getMessage());
            }
        } catch (Exception e) {
            log.error("Unexpected asynchronous project provisioning failure for dbKey={}", dbKey, e);
        } finally {
            inFlight.remove(dbKey);
        }
    }

    private void recordOwnership(UUID ownerId, String dbKey) {
        try {
            projectOwnershipService.recordOwnership(ownerId, dbKey, null, null);
        } catch (DataIntegrityViolationException race) {
            log.warn("Ownership write raced for dbKey={}; retrying once: {}", dbKey, race.getMessage());
            projectOwnershipService.recordOwnership(ownerId, dbKey, null, null);
        } catch (RuntimeException e) {
            log.error("Project {} initialized but ownership refresh failed", dbKey, e);
        }
    }

    public record Submission(SubmissionState state) {
    }

    public enum SubmissionState {
        QUEUED,
        ALREADY_RUNNING,
        ALREADY_INITIALIZED
    }
}
