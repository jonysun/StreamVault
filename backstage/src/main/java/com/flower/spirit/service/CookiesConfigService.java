package com.flower.spirit.service;

import java.io.File;
import java.io.FileWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.flower.spirit.common.AjaxEntity;
import com.flower.spirit.config.Global;
import com.flower.spirit.dao.CookiesConfigDao;
import com.flower.spirit.entity.CookiesConfigEntity;
import com.flower.spirit.entity.CookiesRequestEntity;
import com.flower.spirit.entity.GraphicContentEntity;
import com.flower.spirit.executor.WeiBoExecutor;
import com.flower.spirit.utils.sendNotify;

@Service
public class CookiesConfigService {

	@Autowired
	private CookiesConfigDao cookiesConfigDao;

	@Autowired
	private GraphicContentService graphicContentService;

	@Autowired
	private WeiBoExecutor weiBoExecutor;

	@Autowired
	private DouyinCookieHealthService douyinCookieHealthService;

	public CookiesConfigEntity getData() {
		List<CookiesConfigEntity> findAll = cookiesConfigDao.findAll();
		if (findAll.size() == 0) {
			CookiesConfigEntity cookiesConfigEntity = new CookiesConfigEntity();
			cookiesConfigDao.save(cookiesConfigEntity);
			return cookiesConfigEntity;
		}
		return findAll.get(0);
	}

	public AjaxEntity updateCookie(CookiesConfigEntity entity) {
		if ((entity.getKuaishouCookie() == null || entity.getKuaishouCookie().trim().isEmpty())
				&& entity.getKuaishouCookiePool() != null && !entity.getKuaishouCookiePool().trim().isEmpty()) {
			entity.setKuaishouCookie(firstCookie(entity.getKuaishouCookiePool()));
		}
		Global.cookie_manage = entity;
		cookiesConfigDao.save(entity);
		return new AjaxEntity(Global.ajax_success, "更新成功", null);
	}

	private String firstCookie(String pool) {
		String[] lines = pool.split("\\r?\\n");
		for (String line : lines) {
			if (line != null && !line.trim().isEmpty()) {
				return line.trim();
			}
		}
		return "";
	}

	public AjaxEntity writeCookies(CookiesRequestEntity cookiesRequestEntity) {
		try {
			String apppath = Global.apppath;
			String platform = cookiesRequestEntity.getPlatform();
			String cookies = cookiesRequestEntity.getCookies();

			File cookieDir = new File(apppath + "/cookies");
			if (!cookieDir.exists()) {
				cookieDir.mkdirs();
			}

			File cookieFile = new File(cookieDir, platform + ".txt");
			try (FileWriter writer = new FileWriter(cookieFile)) {
				writer.write(cookies);
			}

			return new AjaxEntity(Global.ajax_success, "Cookie保存成功", null);
		} catch (Exception e) {
			return new AjaxEntity(Global.ajax_uri_error, "Cookie保存失败: " + e.getMessage(), null);
		}
	}

	public AjaxEntity checkCookies() {
		try {
			String apppath = Global.apppath;
			File cookieDir = new File(apppath + "/cookies");

			if (!cookieDir.exists()) {
				return new AjaxEntity(Global.ajax_success, "cookies目录不存在", false);
			}

			Map<String, Boolean> status = new HashMap<>();
			if (cookieDir.exists() && cookieDir.isDirectory()) {
				File[] files = cookieDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".txt"));
				if (files != null) {
					for (File file : files) {
						String platform = file.getName().replace(".txt", "");
						status.put(platform, true);
					}
				}
			}

			return new AjaxEntity(Global.ajax_success, "检查完成", status);
		} catch (Exception e) {
			return new AjaxEntity(Global.ajax_uri_error, "检查失败: " + e.getMessage(), null);
		}
	}

	public void checkCookieStatus() {
		String message = "";
		try {
			Map<String, Object> douyinStatus = douyinCookieHealthService.checkDouyinCookies(true);
			message = message + "抖音: 正常 " + douyinStatus.get("valid")
					+ " / 疑似降级 " + douyinStatus.get("degraded")
					+ " / 异常 " + douyinStatus.get("invalid") + "\n";
		} catch (Exception e) {
			message = message + "抖音: 检测失败\n";
		}

		GraphicContentEntity randomByPlatform = graphicContentService.findRandomByPlatform("weibo");
		if (randomByPlatform != null) {
			String fetchWeiboDetail = weiBoExecutor.fetchWeiboDetail(randomByPlatform.getVideoid());
			if (fetchWeiboDetail != null && !fetchWeiboDetail.contains("\"ok\":-100")) {
				message = message + "微博: 正常\n";
			} else {
				message = message + "微博: 失效\n";
			}
		}

		sendNotify.sendMessage("StreamVault cookie 检测通知", message);
	}
}
