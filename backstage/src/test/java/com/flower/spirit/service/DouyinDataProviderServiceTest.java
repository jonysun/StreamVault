package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.alibaba.fastjson.JSONObject;
import com.flower.spirit.config.Global;

@ExtendWith(MockitoExtension.class)
class DouyinDataProviderServiceTest {

	@Mock
	private F2DouyinDataProvider f2;

	@Mock
	private DtkDouyinDataProvider dtk;

	private String provider;
	private DouyinDataProviderService service;

	@BeforeEach
	void setUp() {
		provider = Global.douyinProvider;
		Global.douyinProvider = "AUTO";
		service = new DouyinDataProviderService(f2, dtk);
	}

	@AfterEach
	void tearDown() {
		Global.douyinProvider = provider;
	}

	@Test
	void autoUsesF2WhenItSucceeds() {
		DouyinFetchRequest request = request("cookie-value");
		DouyinFetchEnvelope expected = envelope("F2");
		when(f2.fetchAuthorWorks(request)).thenReturn(expected);

		assertThat(service.fetchAuthorWorks(request)).isSameAs(expected);
		verify(dtk, never()).fetchAuthorWorks(request);
	}

	@Test
	void autoFailsOverOnlyForRetryableF2Failure() {
		DouyinFetchRequest request = request("cookie-value");
		DouyinFetchEnvelope expected = envelope("DTK");
		when(f2.fetchAuthorWorks(request)).thenThrow(new CollectFetchException("F2_UPSTREAM_EMPTY_RESPONSE", "empty response"));
		when(dtk.fetchAuthorWorks(request)).thenReturn(expected);

		assertThat(service.fetchAuthorWorks(request)).isSameAs(expected);
		verify(dtk).fetchAuthorWorks(request);
	}

	@Test
	void autoDoesNotFailOverForDeterministicF2ParameterFailure() {
		DouyinFetchRequest request = request("cookie-value");
		CollectFetchException expected = new CollectFetchException("F2_INVALID_SOURCE", "invalid source");
		when(f2.fetchAuthorWorks(request)).thenThrow(expected);

		assertThatThrownBy(() -> service.fetchAuthorWorks(request)).isSameAs(expected);
		verify(dtk, never()).fetchAuthorWorks(request);
	}

	@Test
	void autoWithNoCookieStartsWithDtk() {
		DouyinFetchRequest request = request("");
		DouyinFetchEnvelope expected = envelope("DTK");
		when(dtk.fetchAuthorWorks(request)).thenReturn(expected);

		assertThat(service.fetchAuthorWorks(request)).isSameAs(expected);
		verify(f2, never()).fetchAuthorWorks(request);
	}

	@Test
	void dtkFailureIsNotRetriedThroughF2() {
		DouyinFetchRequest request = request("cookie-value");
		CollectFetchException expected = new CollectFetchException("DTK_UPSTREAM_RISK_CONTROL", "risk control");
		when(f2.fetchAuthorWorks(request)).thenThrow(new CollectFetchException("F2_UPSTREAM_TIMEOUT", "timeout"));
		when(dtk.fetchAuthorWorks(request)).thenThrow(expected);

		assertThatThrownBy(() -> service.fetchAuthorWorks(request)).isSameAs(expected);
		verify(f2).fetchAuthorWorks(request);
		verify(dtk).fetchAuthorWorks(request);
	}

	@Test
	void autoDirectFallsBackWhenF2ReturnsNoResult() {
		when(f2.fetchDirect("https://www.douyin.com/video/1", "cookie-value")).thenReturn(null);
		when(dtk.fetchDirect("https://www.douyin.com/video/1")).thenReturn(java.util.Map.of("awemeid", "1"));

		assertThat(service.fetchDirect("https://www.douyin.com/video/1", "cookie-value"))
				.containsEntry("awemeid", "1");
		verify(dtk).fetchDirect("https://www.douyin.com/video/1");
	}

	private DouyinFetchRequest request(String cookie) {
		return new DouyinFetchRequest("sec-user-id", Set.of(), null, 0, 1, 1,
				DouyinFetchMode.INITIAL, 10, cookie);
	}

	private DouyinFetchEnvelope envelope(String provider) {
		JSONObject diagnostics = new JSONObject(true);
		diagnostics.put("provider", provider);
		return new DouyinFetchEnvelope(List.of(), Set.of(), "NO_PUBLIC_WORKS", 1, 0,
				"0", "0", true, false, 0, diagnostics);
	}
}
