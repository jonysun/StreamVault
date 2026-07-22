package com.flower.spirit.web.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class AdminTemplateScriptSanityTest {

	private static final Pattern TOP_LEVEL_FUNCTION = Pattern.compile(
			"(?m)^    function\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\(");

	@Test
	void topLevelFunctionNamesAreUnique() throws IOException {
		for (String template : List.of("index.html", "config.html")) {
			String source = template(template);
			Map<String, Integer> counts = new HashMap<>();
			Matcher matcher = TOP_LEVEL_FUNCTION.matcher(source);
			while (matcher.find()) {
				counts.merge(matcher.group(1), 1, Integer::sum);
			}
			List<String> duplicates = new ArrayList<>();
			counts.forEach((name, count) -> {
				if (count > 1) duplicates.add(name);
			});
			assertThat(duplicates)
					.as("duplicate top-level JavaScript functions in %s", template)
					.isEmpty();
		}
	}

	private String template(String name) throws IOException {
		try (var input = getClass().getResourceAsStream("/templates/admin/" + name)) {
			if (input == null) throw new IOException(name + " template not found");
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
