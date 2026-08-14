package ai.nubase.agent.controller;

import ai.nubase.ai.gateway.billing.BillingProperties;
import ai.nubase.ai.gateway.billing.BillingService;
import ai.nubase.ai.gateway.repository.ApiKeyRepository;
import ai.nubase.ai.gateway.service.TokenCounterService;
import ai.nubase.auth.repository.UserRepository;
import ai.nubase.auth.service.JwtSecretService;
import ai.nubase.auth.service.OAuthStateService;
import ai.nubase.common.config.SecurityConfig;
import ai.nubase.postgrest.multidb.DatabaseConfig;
import ai.nubase.postgrest.multidb.DatabaseConfigRepository;
import ai.nubase.postgrest.multidb.RoutingDataSource;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Loads the real {@link SecurityConfig} chain so route-level authorization rules are
 * exercised (unlike {@link AgentMetadataControllerTest}, which stands the controller up
 * without Spring Security). Guards against /agent/v1 falling into the
 * anyRequest().authenticated() catch-all again.
 */
@WebMvcTest(AgentMetadataController.class)
@AutoConfigureMockMvc
@Import(SecurityConfig.class)
@ActiveProfiles("dev") // AdminInitAuthFilter refuses to boot outside dev without METADATA_SERVICE_ROLE_KEY
class AgentMetadataControllerSecurityTest {

    private static final String APP_CODE = "testproject";
    private static final String DB_KEY = "db_testproject";
    private static final String JWT_SECRET = "test-secret-0123456789-0123456789-0123456789";

    @Autowired
    private MockMvc mvc;

    // Dependencies of the real filters registered in SecurityConfig
    // (UnifiedMultiTenancyFilter, GatewayApiKeyAuthFilter, BillingAdmissionFilter).
    @MockBean
    private DatabaseConfigRepository databaseConfigRepository;
    @MockBean
    private RoutingDataSource routingDataSource;
    @MockBean
    private JwtSecretService jwtSecretService;
    @MockBean
    private OAuthStateService oauthStateService;
    @MockBean
    private UserRepository userRepository;
    @MockBean
    private ApiKeyRepository apiKeyRepository;
    @MockBean
    private BillingService billingService;
    @MockBean
    private BillingProperties billingProperties;
    @MockBean
    private TokenCounterService tokenCounterService;

    @Test
    void connectConfigAllowsValidProjectApikeyWithoutUserLogin() throws Exception {
        DatabaseConfig config = DatabaseConfig.builder()
                .appCode(APP_CODE)
                .dbKey(DB_KEY)
                .schemaName("public")
                .jwtSecret(JWT_SECRET)
                .enabled(true)
                .initStatus("INITIALIZED")
                .build();
        when(databaseConfigRepository.findByAppCode(APP_CODE)).thenReturn(config);
        when(routingDataSource.hasDataSource(DB_KEY)).thenReturn(true);

        SecretKey key = Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8));
        String apikey = Jwts.builder()
                .claim("ref", APP_CODE)
                .claim("role", "service_role")
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        mvc.perform(get("/agent/v1/connect-config")
                        .param("client", "codex")
                        .header("apikey", apikey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.client").value("codex"));
    }

    @Test
    void connectConfigStillRejectsMissingApikey() throws Exception {
        mvc.perform(get("/agent/v1/connect-config").param("client", "codex"))
                .andExpect(status().isUnauthorized());
    }
}
