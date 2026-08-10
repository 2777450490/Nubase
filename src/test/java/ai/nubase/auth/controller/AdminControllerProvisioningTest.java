package ai.nubase.auth.controller;

import ai.nubase.auth.service.AdminService;
import ai.nubase.auth.service.DatabaseInitService;
import ai.nubase.auth.service.PlatformExternalIdentityService;
import ai.nubase.auth.service.ProjectOwnershipService;
import ai.nubase.auth.service.ProjectProvisioningService;
import ai.nubase.auth.service.RlsPolicyExportService;
import ai.nubase.auth.service.SchemaDdlExportService;
import ai.nubase.auth.service.SchemaInitService;
import ai.nubase.auth.service.SqlExecutionService;
import ai.nubase.common.enums.DatabaseInitStatus;
import ai.nubase.metadata.repository.PlatformUserProjectRepository;
import ai.nubase.metadata.repository.PlatformUserRepository;
import ai.nubase.metadata.repository.SqlExecutionRecordRepository;
import ai.nubase.metadata.repository.SqlSnippetRepository;
import ai.nubase.postgrest.multidb.DatabaseConfig;
import ai.nubase.postgrest.multidb.DatabaseConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static ai.nubase.test.ControllerTestSupport.mockMvc;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminControllerProvisioningTest {

    private static final UUID PLATFORM_USER_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private DatabaseConfigRepository databaseConfigRepository;
    private PlatformUserProjectRepository platformUserProjectRepository;
    private ProjectProvisioningService projectProvisioningService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        databaseConfigRepository = mock(DatabaseConfigRepository.class);
        platformUserProjectRepository = mock(PlatformUserProjectRepository.class);
        projectProvisioningService = mock(ProjectProvisioningService.class);
        mvc = mockMvc(new AdminController(
                mock(AdminService.class),
                mock(SqlExecutionService.class),
                mock(SchemaInitService.class),
                mock(DatabaseInitService.class),
                projectProvisioningService,
                mock(SchemaDdlExportService.class),
                mock(RlsPolicyExportService.class),
                databaseConfigRepository,
                mock(PlatformUserRepository.class),
                platformUserProjectRepository,
                mock(SqlSnippetRepository.class),
                mock(SqlExecutionRecordRepository.class),
                mock(PlatformExternalIdentityService.class),
                mock(ProjectOwnershipService.class)));
    }

    @Test
    void submitsProvisioningForProjectOwner() throws Exception {
        DatabaseConfig config = projectConfig(DatabaseInitStatus.PENDING_INIT);
        when(databaseConfigRepository.findByAppCode("demo")).thenReturn(config);
        when(platformUserProjectRepository.existsByUserIdAndDbKey(
                PLATFORM_USER_ID,
                "db-demo")).thenReturn(true);
        when(projectProvisioningService.submit(
                "db-demo",
                DatabaseInitStatus.PENDING_INIT.name(),
                PLATFORM_USER_ID))
                .thenReturn(new ProjectProvisioningService.Submission(
                        ProjectProvisioningService.SubmissionState.QUEUED));

        mvc.perform(post("/auth/v1/admin/projects/demo/provision")
                        .requestAttr("platformUserId", PLATFORM_USER_ID)
                        .requestAttr("platformIsSuperAdmin", false))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.ref").value("demo"))
                .andExpect(jsonPath("$.submissionState").value("QUEUED"));

        verify(projectProvisioningService).submit(
                "db-demo",
                DatabaseInitStatus.PENDING_INIT.name(),
                PLATFORM_USER_ID);
    }

    @Test
    void reportsPersistedInitializingStateAsRunningAcrossNodes() throws Exception {
        DatabaseConfig config = projectConfig(DatabaseInitStatus.INITIALIZING);
        when(databaseConfigRepository.findByAppCode("demo")).thenReturn(config);
        when(platformUserProjectRepository.existsByUserIdAndDbKey(
                PLATFORM_USER_ID,
                "db-demo")).thenReturn(true);
        when(projectProvisioningService.isRunning("db-demo")).thenReturn(false);

        mvc.perform(get("/auth/v1/admin/projects/demo/provision")
                        .requestAttr("platformUserId", PLATFORM_USER_ID)
                        .requestAttr("platformIsSuperAdmin", false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.initStatus").value("INITIALIZING"))
                .andExpect(jsonPath("$.running").value(true));
    }

    private DatabaseConfig projectConfig(DatabaseInitStatus status) {
        return DatabaseConfig.builder()
                .dbKey("db-demo")
                .appCode("demo")
                .initStatus(status.name())
                .enabled(false)
                .build();
    }
}
