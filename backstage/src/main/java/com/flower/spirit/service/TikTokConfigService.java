package com.flower.spirit.service;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.flower.spirit.common.AjaxEntity;
import com.flower.spirit.config.Global;
import com.flower.spirit.dao.TikTokConfigDao;
import com.flower.spirit.entity.TikTokConfigEntity;

@Service
public class TikTokConfigService {

	public static final int DEFAULT_RISK_COOLDOWN_MINUTES = 10;
	public static final int MIN_RISK_COOLDOWN_MINUTES = 1;
	public static final int MAX_RISK_COOLDOWN_MINUTES = 1440;
	
	
	@Autowired
	private TikTokConfigDao tikTokConfigDao;

	public List<TikTokConfigEntity> findAll(TikTokConfigEntity tikTokConfigEntity) {
		return tikTokConfigDao.findAll();
	}
	
	public TikTokConfigEntity getData() {
		List<TikTokConfigEntity> findAll = tikTokConfigDao.findAll();
		if(findAll.size() ==0) {
			TikTokConfigEntity tikTokConfigEntity = new TikTokConfigEntity();
			TikTokConfigEntity save = tikTokConfigDao.save(tikTokConfigEntity);
			return save;
		}
		return findAll.get(0);
	}

	public AjaxEntity updateTikTokConfig(TikTokConfigEntity tikTokConfigEntity) {
		Integer cooldownMinutes = tikTokConfigEntity.getRiskCooldownMinutes();
		if (cooldownMinutes == null) {
			tikTokConfigEntity.setRiskCooldownMinutes(DEFAULT_RISK_COOLDOWN_MINUTES);
		} else if (cooldownMinutes < MIN_RISK_COOLDOWN_MINUTES
				|| cooldownMinutes > MAX_RISK_COOLDOWN_MINUTES) {
			return new AjaxEntity(Global.ajax_uri_error, "风控冷却时间必须在 1 到 1440 分钟之间", tikTokConfigEntity);
		}
		if (isBlank(tikTokConfigEntity.getCookies()) && !isBlank(tikTokConfigEntity.getCookiepool())) {
			tikTokConfigEntity.setCookies(firstCookie(tikTokConfigEntity.getCookiepool()));
		}
		tikTokConfigDao.save(tikTokConfigEntity);
		if(null != tikTokConfigEntity.getCookies() && !"".equals(tikTokConfigEntity.getCookies())) {
			Global.tiktokCookie = tikTokConfigEntity.getCookies();
		}
		return new AjaxEntity(Global.ajax_success, "操作成功", tikTokConfigEntity);
	}

	public int getRiskCooldownMinutes() {
		Integer configured = getData().getRiskCooldownMinutes();
		return configured == null || configured < MIN_RISK_COOLDOWN_MINUTES
				|| configured > MAX_RISK_COOLDOWN_MINUTES
				? DEFAULT_RISK_COOLDOWN_MINUTES : configured;
	}

	private String firstCookie(String pool) {
		String[] lines = pool.split("\\r?\\n");
		for (String line : lines) {
			if (!isBlank(line)) {
				return line.trim();
			}
		}
		return "";
	}

	private boolean isBlank(String value) {
		return value == null || value.trim().isEmpty();
	}

}
