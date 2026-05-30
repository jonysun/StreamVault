package com.flower.spirit.service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.httpclient.HttpException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.flower.spirit.common.AjaxEntity;
import com.flower.spirit.config.Global;
import com.flower.spirit.dao.CookiesConfigDao;
import com.flower.spirit.entity.CookiesConfigEntity;
import com.flower.spirit.entity.CookiesRequestEntity;
import com.flower.spirit.entity.GraphicContentEntity;
import com.flower.spirit.entity.VideoDataEntity;
import com.flower.spirit.executor.WeiBoExecutor;
import com.flower.spirit.utils.DouUtil;
import com.flower.spirit.utils.sendNotify;

@Service
public class CookiesConfigService {

	@Autowired
	private CookiesConfigDao cookiesConfigDao;
	
	@Autowired
	private VideoDataService videoDataService;
	
	@Autowired
	private GraphicContentService graphicContentService;
	
	@Autowired
	private WeiBoExecutor weiBoExecutor;

	@Autowired
	private PlatformCookieService platformCookieService;

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
		// CookiesConfigEntity cookiesConfigEntity =
		// cookiesConfigDao.findById(entity.getId()).get();
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

	// 向本地写入cookie

	public AjaxEntity writeCookies(CookiesRequestEntity cookiesRequestEntity) {
		try {
			String apppath = Global.apppath;
			String platform = cookiesRequestEntity.getPlatform();
			String cookies = cookiesRequestEntity.getCookies();

			// 确保cookies目录存在
			File cookieDir = new File(apppath + "/cookies");
			if (!cookieDir.exists()) {
				cookieDir.mkdirs();
			}

			// 写入cookie文件
			File cookieFile = new File(cookieDir, platform + ".txt");
			try (FileWriter writer = new FileWriter(cookieFile)) {
				writer.write(cookies);
			}

			return new AjaxEntity(Global.ajax_success, "Cookie保存成功", null);
		} catch (Exception e) {
			return new AjaxEntity(Global.ajax_uri_error, "Cookie保存失败: " + e.getMessage(), null);
		}
	}

	// 检查本地cookie状态
	public AjaxEntity checkCookies() {
		try {
			String apppath = Global.apppath;
			File cookieDir = new File(apppath + "/cookies");

			// 检查cookies目录
			if (!cookieDir.exists()) {
				return new AjaxEntity(Global.ajax_success, "cookies目录不存在", false);
			}

			// 检查文件状态
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
		//先检查dy  dy从video那  微博从图文拿  最后检测小红书 小红书cookie检测 不会 不做了  其他yt-dlp平台等后边再说
		String message ="";
		VideoDataEntity randomByVideoplatform = videoDataService.findRandomByVideoplatform("抖音");
		if(randomByVideoplatform!=null) {
			try {
				String cookie = platformCookieService.currentDouyinCookie("cookie_check");
				Map<String, String> bogus = DouUtil.getBogusWithCookie(randomByVideoplatform.getVideoid(), cookie);
				if(bogus!=null) {
					platformCookieService.reportSuccess("抖音", cookie);
					message =message+ "抖音:正常\n";
				}else {
					platformCookieService.reportRisk("抖音", cookie, "cookie status check failed");
					message =message+ "抖音:失效\n";
				}
			} catch (HttpException e) {message = message+"抖音:检测失败\n";} catch (Exception e) {message =message+ "抖音:检测失败\n";}
		}
		
		//检测微博
		GraphicContentEntity randomByPlatform = graphicContentService.findRandomByPlatform("weibo");
		if(randomByPlatform!=null) {
			String fetchWeiboDetail = weiBoExecutor.fetchWeiboDetail(randomByPlatform.getVideoid());
			if(fetchWeiboDetail!=null && !fetchWeiboDetail.contains("\"ok\":-100")) {
				message =message+ "微博:正常\n";
			}else {
				message =message+"微博:失效\n";
			}
		}
		
		sendNotify.sendMessage("StreamVault cookie 检测通知", message);
		
	}

}
