package com.flower.spirit.web.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class DirectDataTemplateTest {

	@Test
	void exposesExplicitPreviewAndIngestModesAtTheExistingRoute() throws IOException {
		String template = template();

		assertThat(template).contains("id=\"directDataForm\"")
				.contains("name=\"operationMode\" value=\"preview\"")
				.contains("name=\"operationMode\" value=\"ingest\"")
				.contains("解析预览", "下载入库")
				.contains("apiUrl += '?type=2'", "apiUrl += '?type=1'")
				.doesNotContain("id=\"onlyGetLink\"");
	}

	@Test
	void rendersCanonicalPreviewResourcesAndSubmissionHistoryLink() throws IOException {
		String template = template();

		assertThat(template).contains("record.platformKey", "record.workId", "record.contentType")
				.contains("record.mediaResources", "mediaType === 'mixed'", "作者 UID", "作者用户名", "发布时间")
				.contains("response.taskId", "response.status")
				.contains("href=\"/admin/processHistoryList\"")
				.doesNotContain("<h5><i class=\"mdi mdi-information-outline\"></i> 使用说明</h5>");
	}

	@Test
	void externalMetadataIsNotInterpolatedIntoInlineEventHandlers() throws IOException {
		String template = template();

		assertThat(template).doesNotContain("onclick=\"copyToClipboard('")
				.doesNotContain("onclick=\"window.open('")
				.contains("data-copy-url=\"")
				.contains("data-open-url=\"")
				.contains("&quot;", "&#39;");
	}

	private String template() throws IOException {
		try (var input = getClass().getResourceAsStream("/templates/admin/directData.html")) {
			if (input == null) throw new IOException("directData template not found");
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
