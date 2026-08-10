package ai.nubase.postgrest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@EnabledIfEnvironmentVariable(named = "RUN_DB_IT", matches = "true")
@SpringBootTest
@TestPropertySource(properties = {
    "pgrst.db-uri=jdbc:postgresql://localhost:5432/postgres",
    "pgrst.db-schemas=public",
    "pgrst.db-anon-role=anon"
})
class PostgRESTApplicationTests {

    @Test
    void contextLoads() {
        // Application context should load successfully
    }
}
