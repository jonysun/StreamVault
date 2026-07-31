package com.flower.spirit;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import jakarta.persistence.EntityManagerFactory;

import org.quartz.Scheduler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.flower.spirit.config.AppConfig;
import com.flower.spirit.service.ConfigService;

class SpiritApplicationSchemaOnlyTest {

    @Test
    void recognizesOnlyExplicitSchemaOnlyTrueArgument() {
        assertThat(SpiritApplication.isSchemaOnly(new String[] { "--streamvault.schema-only=true" })).isTrue();
        assertThat(SpiritApplication.isSchemaOnly(new String[] { "--streamvault.schema-only=TRUE" })).isTrue();
        assertThat(SpiritApplication.isSchemaOnly(new String[] { "--streamvault.schema-only=false" })).isFalse();
        assertThat(SpiritApplication.isSchemaOnly(new String[] { "--streamvault.schema-only" })).isFalse();
        assertThat(SpiritApplication.isSchemaOnly(new String[0])).isFalse();
    }

    @Test
    void schemaOnlyConfigurationDoesNotScanBusinessBeans() {
        new ApplicationContextRunner()
                .withUserConfiguration(SpiritApplication.SchemaOnlyConfiguration.class)
                .withPropertyValues(
                        "spring.datasource.url=jdbc:sqlite::memory:",
                        "spring.datasource.driver-class-name=org.sqlite.JDBC",
                        "spring.flyway.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(DataSource.class);
                    assertThat(context).doesNotHaveBean(AppConfig.class);
                    assertThat(context).doesNotHaveBean(ConfigService.class);
                    assertThat(context).doesNotHaveBean(EntityManagerFactory.class);
                    assertThat(context).doesNotHaveBean(Scheduler.class);
                });
    }
}
