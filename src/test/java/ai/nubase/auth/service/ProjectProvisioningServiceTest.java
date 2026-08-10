package ai.nubase.auth.service;

import ai.nubase.auth.dto.response.admin.InitDatabaseResponse;
import ai.nubase.common.enums.DatabaseInitStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class ProjectProvisioningServiceTest {

    private static final UUID OWNER_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private DatabaseInitService databaseInitService;
    private ProjectOwnershipService projectOwnershipService;
    private CapturingExecutor executor;
    private ProjectProvisioningService service;

    @BeforeEach
    void setUp() {
        databaseInitService = mock(DatabaseInitService.class);
        projectOwnershipService = mock(ProjectOwnershipService.class);
        executor = new CapturingExecutor();
        service = new ProjectProvisioningService(
                databaseInitService,
                projectOwnershipService,
                executor);
    }

    @Test
    void queuesProvisioningWithoutBlockingAndRecordsOwnershipAfterSuccess() {
        when(databaseInitService.initializePhysicalDatabase("db-demo"))
                .thenReturn(InitDatabaseResponse.success(
                        null,
                        null,
                        null,
                        DatabaseInitStatus.INITIALIZED.name(),
                        List.of(),
                        10));

        ProjectProvisioningService.Submission submission =
                service.submit("db-demo", DatabaseInitStatus.PENDING_INIT.name(), OWNER_ID);

        assertThat(submission.state())
                .isEqualTo(ProjectProvisioningService.SubmissionState.QUEUED);
        assertThat(service.isRunning("db-demo")).isTrue();
        verify(databaseInitService, never()).initializePhysicalDatabase("db-demo");

        executor.runNext();

        verify(databaseInitService).initializePhysicalDatabase("db-demo");
        verify(projectOwnershipService).recordOwnership(OWNER_ID, "db-demo", null, null);
        assertThat(service.isRunning("db-demo")).isFalse();
    }

    @Test
    void deduplicatesConcurrentSubmissionsForTheSameProjectOnOneNode() {
        ProjectProvisioningService.Submission first =
                service.submit("db-demo", DatabaseInitStatus.PENDING_INIT.name(), OWNER_ID);
        ProjectProvisioningService.Submission duplicate =
                service.submit("db-demo", DatabaseInitStatus.PENDING_INIT.name(), OWNER_ID);

        assertThat(first.state())
                .isEqualTo(ProjectProvisioningService.SubmissionState.QUEUED);
        assertThat(duplicate.state())
                .isEqualTo(ProjectProvisioningService.SubmissionState.ALREADY_RUNNING);
        assertThat(executor.size()).isEqualTo(1);
    }

    @Test
    void skipsProjectsThatAreAlreadyInitialized() {
        ProjectProvisioningService.Submission submission =
                service.submit("db-demo", DatabaseInitStatus.INITIALIZED.name(), OWNER_ID);

        assertThat(submission.state())
                .isEqualTo(ProjectProvisioningService.SubmissionState.ALREADY_INITIALIZED);
        assertThat(executor.size()).isZero();
    }

    @Test
    void doesNotLogProvisioningFailureDetails(CapturedOutput output) {
        String sensitiveDetail = "private-password-bearing-sql";
        when(databaseInitService.initializePhysicalDatabase("db-demo"))
                .thenThrow(new IllegalStateException(sensitiveDetail));

        service.submit("db-demo", DatabaseInitStatus.PENDING_INIT.name(), OWNER_ID);
        executor.runNext();

        assertThat(output.getAll())
                .contains("errorType=IllegalStateException")
                .doesNotContain(sensitiveDetail);
    }

    private static final class CapturingExecutor implements Executor {

        private final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        void runNext() {
            tasks.remove().run();
        }

        int size() {
            return tasks.size();
        }
    }
}
