package com.flower.spirit.entity;

import java.io.Serializable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.TableGenerator;

@Entity
@Table(name = "biz_tiktok_config")
public class TikTokConfigEntity implements Serializable {

	/**
	 * 
	 */
	private static final long serialVersionUID = 2472625323664839181L;
	
	@Id
	@GeneratedValue(strategy = GenerationType.TABLE,generator="biz_tiktok_config")
	@TableGenerator(name = "biz_tiktok_config", allocationSize = 1, table = "seq_common", pkColumnName = "seq_id", valueColumnName = "seq_count")
    private Integer id;
	
	@Column(columnDefinition = "TEXT")
	private String cookies;

	@Column(columnDefinition = "TEXT")
	private String cookiepool;

	private String cookiestrategy;

	@Column(name = "risk_cooldown_minutes")
	private Integer riskCooldownMinutes;

	@Column(name = "author_list_soft_block_auto_retry_enabled")
	private Boolean authorListSoftBlockAutoRetryEnabled;

	@Column(name = "douyin_probe_sec_user_id", length = 255)
	private String douyinProbeSecUserId;
	
	/**
	 * 解析server
	 */
	private String analysisserver;

	public Integer getId() {
		return id;
	}

	public void setId(Integer id) {
		this.id = id;
	}

	public String getCookies() {
		return cookies;
	}

	public void setCookies(String cookies) {
		this.cookies = cookies;
	}

	public String getCookiepool() {
		return cookiepool;
	}

	public void setCookiepool(String cookiepool) {
		this.cookiepool = cookiepool;
	}

	public String getCookiestrategy() {
		return cookiestrategy;
	}

	public void setCookiestrategy(String cookiestrategy) {
		this.cookiestrategy = cookiestrategy;
	}

	public Integer getRiskCooldownMinutes() {
		return riskCooldownMinutes;
	}

	public void setRiskCooldownMinutes(Integer riskCooldownMinutes) {
		this.riskCooldownMinutes = riskCooldownMinutes;
	}

	public Boolean getAuthorListSoftBlockAutoRetryEnabled() {
		return authorListSoftBlockAutoRetryEnabled;
	}

	public void setAuthorListSoftBlockAutoRetryEnabled(Boolean authorListSoftBlockAutoRetryEnabled) {
		this.authorListSoftBlockAutoRetryEnabled = authorListSoftBlockAutoRetryEnabled;
	}

	public String getDouyinProbeSecUserId() {
		return douyinProbeSecUserId;
	}

	public void setDouyinProbeSecUserId(String douyinProbeSecUserId) {
		this.douyinProbeSecUserId = douyinProbeSecUserId;
	}

	public String getAnalysisserver() {
		return analysisserver;
	}

	public void setAnalysisserver(String analysisserver) {
		this.analysisserver = analysisserver;
	}
	
	
	

}
