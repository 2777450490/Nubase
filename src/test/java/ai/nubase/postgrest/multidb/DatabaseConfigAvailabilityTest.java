package ai.nubase.postgrest.multidb;

import ai.nubase.common.enums.DatabaseInitStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseConfigAvailabilityTest {

    @Test
    void onlyInitializedAndLegacyEnabledProjectsAreAvailable() {
        assertThat(config(true, DatabaseInitStatus.INITIALIZED.name()).isAvailable()).isTrue();
        assertThat(config(true, null).isAvailable()).isTrue();

        assertThat(config(true, DatabaseInitStatus.PENDING_INIT.name()).isAvailable()).isFalse();
        assertThat(config(true, DatabaseInitStatus.INITIALIZING.name()).isAvailable()).isFalse();
        assertThat(config(true, DatabaseInitStatus.INIT_FAILED.name()).isAvailable()).isFalse();
        assertThat(config(false, DatabaseInitStatus.INITIALIZED.name()).isAvailable()).isFalse();
        assertThat(config(null, DatabaseInitStatus.INITIALIZED.name()).isAvailable()).isFalse();
    }

    private DatabaseConfig config(Boolean enabled, String initStatus) {
        return DatabaseConfig.builder()
                .enabled(enabled)
                .initStatus(initStatus)
                .build();
    }
}
