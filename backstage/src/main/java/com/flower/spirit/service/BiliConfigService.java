package com.flower.spirit.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson.JSONObject;
import com.flower.spirit.common.AjaxEntity;
import com.flower.spirit.config.Global;
import com.flower.spirit.dao.BiliConfigDao;
import com.flower.spirit.entity.BiliConfigEntity;
import com.flower.spirit.utils.BiliUtil;
import com.flower.spirit.utils.HttpUtil;
import com.flower.spirit.utils.sendNotify;

@Service
public class BiliConfigService {
	
	@Autowired
	private BiliConfigDao BiliConfigDao;
	
	private Logger logger = LoggerFactory.getLogger(BiliConfigService.class);
	
	public BiliConfigEntity getData() {
		List<BiliConfigEntity> findAll = BiliConfigDao.findAll();
		if(findAll.size() == 0) {
			BiliConfigEntity biliConfigEntity = new BiliConfigEntity();
			BiliConfigDao.save(biliConfigEntity);
			return biliConfigEntity;
		}
		return findAll.get(0);
	}

	/**
	 * 修改配置
	 * @param entity
	 * @return
	 */
	public AjaxEntity updateBiliConfig(BiliConfigEntity entity) {
		//这里先简单set 一下 没时间仔细看
		Optional<BiliConfigEntity> byId = BiliConfigDao.findById(entity.getId());
		BiliConfigEntity biliConfigEntity = byId.get();
		entity.setRefreshtoken(biliConfigEntity.getRefreshtoken());
		//这里先简单set 一下 没时间仔细看
		BiliConfigDao.save(entity);
		Global.bilicookies = entity.getBilicookies();
		if(null != entity.getBigmember() && entity.getBigmember().equals("是")) {
			Global.bilimember= true;
		}
		if(null != entity.getBitstream() && !"".equals(entity.getBitstream())) {
			Global.bilibitstream= entity.getBitstream();
		}
		if(null != entity.getOddmm() && entity.getOddmm().equals("1")) {
			Global.biliodddmm= true;
		}else {
			Global.biliodddmm= false;
		}
		if(null != entity.getCollectdmm() && entity.getCollectdmm().equals("1")) {
			Global.bilicollectdmm= true;
		}else {
			Global.bilicollectdmm= false;
		}
		if(null != entity.getCdnsort() && "1".equals(entity.getCdnsort())) {
			Global.cdnsort =true;
		}
		return new AjaxEntity(Global.ajax_success, "操作成功", entity);
	}

	/**
	 * 获取登录二维码
	 * @return
	 */
	public AjaxEntity getBiliCode() {
		String httpGet = HttpUtil.httpGet("https://passport.bilibili.com/x/passport-login/web/qrcode/generate", "UTF-8");
		
		return new AjaxEntity(Global.ajax_success,"请求成功", JSONObject.parseObject(httpGet));
	}

	/**
	 * 检测登录状态并设置ck
	 * @param qrcodekey
	 * @return
	 */
	public AjaxEntity checkBiliLogin(String qrcodekey) {
		Map<String, String> httpGet = HttpUtil.httpGetBypoll("https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key="+qrcodekey, "UTF-8");
		if(httpGet != null) {
			String cookie = httpGet.getOrDefault("cookie", null);
			String refresh_token = httpGet.getOrDefault("refresh_token", null);
			if(cookie== null) {
				return new AjaxEntity(Global.ajax_uri_error,"操作失败,请重新登录", null);
			}
			List<BiliConfigEntity> findAll = BiliConfigDao.findAll();
			if(findAll.size() == 0) {
				BiliConfigEntity biliConfigEntity = new BiliConfigEntity();
				biliConfigEntity.setBilicookies(cookie);
				biliConfigEntity.setRefreshtoken(refresh_token);
				biliConfigEntity.setOddmm("1");
				biliConfigEntity.setCollectdmm("0");
				Global.biliodddmm =true;
				Global.bilicollectdmm =false;
				Global.bilicookies = cookie;
				Global.bili_refresh_token = refresh_token;
				BiliConfigDao.save(biliConfigEntity);
				return new AjaxEntity(Global.ajax_success, "操作成功", biliConfigEntity);
			}else {
				BiliConfigEntity biliConfigEntity = findAll.get(0);
				biliConfigEntity.setBilicookies(cookie);
				biliConfigEntity.setRefreshtoken(refresh_token);
				BiliConfigDao.save(biliConfigEntity);
				Global.bili_refresh_token = refresh_token;
				Global.bilicookies =cookie;
				return new AjaxEntity(Global.ajax_success, "操作成功", biliConfigEntity);
			}
		}else {
			return new AjaxEntity(Global.ajax_uri_error,"操作失败,请重新登录", null);
		}
		
	}
	
	
	/**
	 * 判断是否需要刷新cookie 如何需要则刷新cookie
	 */
	public  void isNeedRefreshAndUpdate() {
		if (Global.bilicookies == null || Global.bilicookies.trim().isEmpty()) {
			logger.warn("[BiliCheck] skip refresh: bilicookies is null or blank");
			return;
		}
		String biliJctValue = Global.bilicookies.replaceAll("(?s).*bili_jct=([^;]+).*", "$1");
		if(biliJctValue != null && !biliJctValue.equals(Global.bilicookies)) {
			String url = "https://passport.bilibili.com/x/passport-login/web/cookie/info?=csrf"+biliJctValue;
			String checkCookie = HttpUtil.httpGetBili(url, "UTF-8", Global.bilicookies);
			JSONObject checkCookieObj = JSONObject.parseObject(checkCookie);
			if(checkCookieObj.getString("code").equals("0")) {
				JSONObject checkData = checkCookieObj.getJSONObject("data");
				Boolean refresh = checkData.getBoolean("refresh");
				Long timestamp = checkData.getLong("timestamp");
				if(refresh) {
					//需要刷新cookie
					String bili_refresh_token = Global.bili_refresh_token;
					if(Global.bili_refresh_token == null) {
						sendNotify.sendMessage("StreamVault通知", "当前BiliBili的Cookie,因需要刷新,因为此cookie为旧版更新前登录,无法自动刷新,请前往后台重新扫码登录");
						return ;
					}
					String correspondPath = BiliUtil.getCorrespondPath(String.format("refresh_%d", timestamp));
					String refresh_csrf = HttpUtil.httpGetBili("https://www.bilibili.com/correspond/1/"+correspondPath, Global.bilicookies);
					String rfurl = "https://passport.bilibili.com/x/passport-login/web/cookie/refresh";
					Map<String, String> data =  new HashMap<String, String>();
					data.put("csrf", biliJctValue);
					data.put("refresh_csrf", refresh_csrf);
					data.put("source", "main_web");
					data.put("refresh_token", bili_refresh_token);
					Map<String, String> httpGetBypoll = HttpUtil.httpPostBypoll(rfurl, data,Global.bilicookies);
					if(httpGetBypoll.getOrDefault("cookie", null) !=null) {
						String cookie = httpGetBypoll.getOrDefault("cookie", null);
						String refresh_token = httpGetBypoll.getOrDefault("refresh_token", null);
						//刷新成功  确认刷新
						biliJctValue =cookie.replaceAll("(?s).*bili_jct=([^;]+).*", "$1");
						//调用API 确认
						Map<String, String> confirmData = new HashMap<String, String>();
						confirmData.put("csrf", biliJctValue);
						confirmData.put("refresh_token", bili_refresh_token);
						String httpGetBili = HttpUtil.httpPost("https://passport.bilibili.com/x/passport-login/web/confirm/refresh", confirmData, cookie);
						String code = JSONObject.parseObject(httpGetBili).getString("code");
						if(code.equals("0")){
							List<BiliConfigEntity> findAll = BiliConfigDao.findAll();
							if(findAll.size() == 0) {
								BiliConfigEntity biliConfigEntity = new BiliConfigEntity();
								biliConfigEntity.setBilicookies(cookie);
								biliConfigEntity.setRefreshtoken(refresh_token);
								biliConfigEntity.setOddmm("1");
								biliConfigEntity.setCollectdmm("0");
								Global.biliodddmm =true;
								Global.bilicollectdmm =false;
								Global.bilicookies = cookie;
								Global.bili_refresh_token = refresh_token;
								BiliConfigDao.save(biliConfigEntity);

							}else {
								BiliConfigEntity biliConfigEntity = findAll.get(0);
								biliConfigEntity.setBilicookies(cookie);
								biliConfigEntity.setRefreshtoken(refresh_token);
								BiliConfigDao.save(biliConfigEntity);
								Global.bili_refresh_token = refresh_token;
								Global.bilicookies =cookie;
							}
							sendNotify.sendMessage("StreamVault通知", "当前BiliBili的Cookie,因需要刷新,已经被刷新");
						}else {
							sendNotify.sendMessage("StreamVault通知", "当前BiliBili的Cookie,因需要刷新,自动刷新失败建议登录后台重新扫码登录");
						}
						
					}
					
					
				}else {
					logger.info("当前bilibili cookie无需刷新");
				}
			}
		} else {
			logger.warn("[BiliCheck] skip refresh: bili_jct not found in cookie");
		}
		return;

	}
	

}
