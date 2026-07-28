package com.flower.spirit.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;

import org.junit.jupiter.api.Test;

import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;

class MediaIdentityGenerationTest {

	@Test
	void mediaEntitiesUseDatabaseNativeIdentityGeneration() throws Exception {
		assertIdentity(VideoDataEntity.class);
		assertIdentity(GraphicContentEntity.class);
	}

	private void assertIdentity(Class<?> entityType) throws Exception {
		Field id = entityType.getDeclaredField("id");
		GeneratedValue generatedValue = id.getAnnotation(GeneratedValue.class);

		assertThat(generatedValue).isNotNull();
		assertThat(generatedValue.strategy()).isEqualTo(GenerationType.IDENTITY);
		assertThat(generatedValue.generator()).isEmpty();
	}
}
