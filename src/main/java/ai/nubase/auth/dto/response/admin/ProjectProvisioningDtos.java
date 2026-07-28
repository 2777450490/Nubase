package ai.nubase.auth.dto.response.admin;

import java.time.Instant;

public final class ProjectProvisioningDtos {

    private ProjectProvisioningDtos() {
    }

    public record SubmissionResponse(
            String ref,
            String initStatus,
            String submissionState,
            String message
    ) {
    }

    public record StatusResponse(
            String ref,
            String initStatus,
            String initMessage,
            Boolean enabled,
            boolean running,
            Instant startedAt,
            Instant completedAt
    ) {
    }
}
