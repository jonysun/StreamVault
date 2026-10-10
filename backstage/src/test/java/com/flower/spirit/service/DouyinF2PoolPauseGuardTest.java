package com.flower.spirit.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.contains;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.flower.spirit.config.Global;
import com.flower.spirit.service.RuntimeControlSnapshot.RuntimeControlValue;
import com.flower.spirit.service.transaction.RuntimeControlTransaction;

class DouyinF2PoolPauseGuardTest {

	private final String previousProvider = Global.douyinProvider;

	@AfterEach
	void restoreProvider() {
		Global.douyinProvider = previousProvider;
	}

	@Test
	void pausesF2OnlyWhenConfiguredPoolIsExhaustedAndAutomaticallyResumes() {
		Global.douyinProvider = "F2";
		PlatformCookieService cookies = mock(PlatformCookieService.class);
		RuntimeControlService controls = mock(RuntimeControlService.class);
		when(controls.isInitialized()).thenReturn(true);
		when(cookies.douyinCookiePoolStatus()).thenReturn(Map.of("configured", 2, "available", 0,
				"earliestCooldownUntil", 1790000000000L));
		when(controls.snapshot()).thenReturn(snapshot(false));
		DouyinF2PoolPauseGuard guard = new DouyinF2PoolPauseGuard(cookies, controls);

		guard.reconcile();
		verify(controls).setAutomaticF2PoolPause(true, contains("最早恢复时间："));

		when(cookies.douyinCookiePoolStatus()).thenReturn(Map.of("configured", 2, "available", 1,
				"earliestCooldownUntil", 0L));
		when(controls.snapshot()).thenReturn(snapshot(true));
		guard.reconcile();
		verify(controls).setAutomaticF2PoolPause(false, "F2 Cookie 池已恢复或当前不是 F2-only 模式");
	}

	@Test
	void doesNotAutoPauseWhenProviderIsAutoOrPoolIsEmpty() {
		Global.douyinProvider = "AUTO";
		PlatformCookieService cookies = mock(PlatformCookieService.class);
		RuntimeControlService controls = mock(RuntimeControlService.class);
		when(controls.isInitialized()).thenReturn(true);
		when(cookies.douyinCookiePoolStatus()).thenReturn(Map.of("configured", 0, "available", 0,
				"earliestCooldownUntil", 0L));
		when(controls.snapshot()).thenReturn(snapshot(false));

		new DouyinF2PoolPauseGuard(cookies, controls).reconcile();

		verify(controls, never()).setAutomaticF2PoolPause(true, contains("最早恢复时间："));
	}

	private RuntimeControlSnapshot snapshot(boolean automaticPause) {
		RuntimeControlValue value = new RuntimeControlValue(automaticPause, "now", "system", "test");
		return new RuntimeControlSnapshot(false, false, false, false, automaticPause, false, false,
				Map.of(RuntimeControlTransaction.PAUSE_COLLECT_F2_POOL, value));
	}
}
