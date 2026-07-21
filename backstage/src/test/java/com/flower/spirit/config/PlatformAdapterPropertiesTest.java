package com.flower.spirit.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class PlatformAdapterPropertiesTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withUserConfiguration(AdapterPropertiesConfiguration.class);

	@Test
	void defaultsMissingPlatformsToLegacyAndBindsExplicitModes() {
		contextRunner.withPropertyValues("streamvault.adapter.youtube=new")
				.run(context -> {
					assertThat(context).hasNotFailed();
					PlatformAdapterProperties properties = context.getBean(PlatformAdapterProperties.class);
					assertThat(properties.modeFor("youtube")).isEqualTo(PlatformAdapterProperties.Mode.NEW);
					assertThat(properties.modeFor("douyin")).isEqualTo(PlatformAdapterProperties.Mode.LEGACY);
				});
	}

	@Test
	void rejectsInvalidAdapterModeDuringStartup() {
		contextRunner.withPropertyValues("streamvault.adapter.youtube=invalid")
				.run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class);
				});
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(PlatformAdapterProperties.class)
	static class AdapterPropertiesConfiguration {
	}
}
