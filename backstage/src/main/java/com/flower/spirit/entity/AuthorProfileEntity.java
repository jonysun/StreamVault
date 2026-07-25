package com.flower.spirit.entity;

import java.io.Serializable;
import java.util.Date;

import com.flower.spirit.common.DataEntity;

import jakarta.persistence.Entity;
import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

@Entity
@Table(name = "biz_author_profile")
public class AuthorProfileEntity extends DataEntity<AuthorProfileEntity> implements Serializable {

	private static final long serialVersionUID = 8794577706279088910L;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Integer id;

	private String platform;

	@Column(length = 64)
	private String platformkey;

	private String authoruid;

	private String username;

	private String displayname;

	private String avatar;

	private String homepage;

	@Column(length = 1024)
	private String signature;

	private Date createtime;

	private Date updatetime;

	@Transient
	private String keyword;

	public Integer getId() {
		return id;
	}

	public void setId(Integer id) {
		this.id = id;
	}

	public String getPlatform() {
		return platform;
	}

	public void setPlatform(String platform) {
		this.platform = platform;
	}

	public String getPlatformkey() {
		return platformkey;
	}

	public void setPlatformkey(String platformkey) {
		this.platformkey = platformkey;
	}

	public String getAuthoruid() {
		return authoruid;
	}

	public void setAuthoruid(String authoruid) {
		this.authoruid = authoruid;
	}

	public String getUsername() {
		return username;
	}

	public void setUsername(String username) {
		this.username = username;
	}

	public String getDisplayname() {
		return displayname;
	}

	public void setDisplayname(String displayname) {
		this.displayname = displayname;
	}

	public String getAvatar() {
		return avatar;
	}

	public void setAvatar(String avatar) {
		this.avatar = avatar;
	}

	public String getHomepage() {
		return homepage;
	}

	public void setHomepage(String homepage) {
		this.homepage = homepage;
	}

	public String getSignature() {
		return signature;
	}

	public void setSignature(String signature) {
		this.signature = signature;
	}

	public Date getCreatetime() {
		return createtime;
	}

	public void setCreatetime(Date createtime) {
		this.createtime = createtime;
	}

	public Date getUpdatetime() {
		return updatetime;
	}

	public void setUpdatetime(Date updatetime) {
		this.updatetime = updatetime;
	}

	public String getKeyword() {
		return keyword;
	}

	public void setKeyword(String keyword) {
		this.keyword = keyword;
	}
}
