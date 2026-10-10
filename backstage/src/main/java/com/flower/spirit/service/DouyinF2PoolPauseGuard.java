package com.flower.spirit.service;

import java.util.Map;
import java.time.Instant;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.flower.spirit.config.Global;
import com.flower.spirit.service.RuntimeControlSnapshot.RuntimeControlValue;
import com.flower.spirit.service.transaction.RuntimeControlTransaction;

@Service
public class DouyinF2PoolPauseGuard {

	private final PlatformCookieService cookieService;
	private final RuntimeControlService runtimeControlService;

	public DouyinF2PoolPauseGuard(PlatformCookieService cookieService,
			RuntimeControlService runtimeControlService) {
		this.cookieService = cookieService;
		this.runtimeControlService = runtimeControlService;
	}

	@Scheduled(fixedDelayString = "${streamvault.douyin.f2-pool-guard-delay-ms:10000}")
	public void reconcile() {
		if (!runtimeControlService.isInitialized()) return;
		boolean f2Only = "F2".equalsIgnoreCase(Global.douyinProvider);
		Map<String, Object> pool = cookieService.douyinCookiePoolStatus();
		int configured = ((Number) pool.get("configured")).intValue();
		int available = ((Number) pool.get("available")).intValue();
		long earliestCooldownUntil = ((Number) pool.get("earliestCooldownUntil")).longValue();
		boolean shouldPause = f2Only && configured > 0 && available == 0;
		String reason = shouldPause
				? "F2 Cookie 池全部冷却，最早恢复时间："
						+ Instant.ofEpochMilli(earliestCooldownUntil) + "；到期后自动恢复，保留手动暂停状态"
				: "F2 Cookie 池已恢复或当前不是 F2-only 模式";
		RuntimeControlValue current = runtimeControlService.snapshot().values()
				.get(RuntimeControlTransaction.PAUSE_COLLECT_F2_POOL);
		if (current == null || current.enabled() != shouldPause) {
			runtimeControlService.setAutomaticF2PoolPause(shouldPause, reason);
		}
	}
}
