package ai.nubase.postgrest.multidb;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatabaseConfigRepositoryProjectVisibilityTest {

    @Test
    void listsProvisioningProjectsButKeepsPausedInitializedProjectsHidden() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(0L);
        DatabaseConfigRepository repository = new DatabaseConfigRepository(
                jdbcTemplate,
                mock(EncryptionService.class));
        UUID userId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        repository.findVisibleProjects(false, userId, 20, 0);
        repository.countVisibleProjects(false, userId);

        ArgumentCaptor<String> listSql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(listSql.capture(), any(RowMapper.class), any(Object[].class));
        ArgumentCaptor<String> countSql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForObject(countSql.capture(), eq(Long.class), any(Object[].class));

        assertVisibilityPredicate(listSql.getValue());
        assertVisibilityPredicate(countSql.getValue());
    }

    private static void assertVisibilityPredicate(String sql) {
        assertThat(sql)
                .contains("c.enabled = true")
                .contains("c.init_status IN ('PENDING_INIT', 'INITIALIZING', 'INIT_FAILED')")
                .doesNotContain("c.init_status = 'INITIALIZED'");
    }
}
