package com.flower.spirit.dto;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class AdminMediaFeedItem {

	private String type;
	private Integer id;
	private String mediaKey;
	private String videoid;
	private String platform;
	private String author;
	private String displayAuthor;
	private String profileAuthorUid;
	private String authoruid;
	private String secuid;
	private String authorusername;
	private String uniqueid;
	private String authoravatar;
	private String title;
	private String desc;
	private String publishTime;
	private Date createTime;
	private String cover;
	private String playurl;
	private String fallbackUrl;
	private String hlsstatus;
	private String sourceurl;
	private String originaladdress;
	private String favorite;
	private String privacy;
	private String platformkey;
	private String contenttype;
	private String authorhomepage;
	private List<AdminMediaSlide> slides = new ArrayList<>();

	public String getType() {
		return type;
	}

	public void setType(String type) {
		this.type = type;
	}

	public Integer getId() {
		return id;
	}

	public void setId(Integer id) {
		this.id = id;
	}

	public String getMediaKey() {
		return mediaKey;
	}

	public void setMediaKey(String mediaKey) {
		this.mediaKey = mediaKey;
	}

	public String getVideoid() {
		return videoid;
	}

	public void setVideoid(String videoid) {
		this.videoid = videoid;
	}

	public String getPlatform() {
		return platform;
	}

	public void setPlatform(String platform) {
		this.platform = platform;
	}

	public String getAuthor() {
		return author;
	}

	public void setAuthor(String author) {
		this.author = author;
	}

	public String getDisplayAuthor() {
		return displayAuthor;
	}

	public void setDisplayAuthor(String displayAuthor) {
		this.displayAuthor = displayAuthor;
	}

	public String getProfileAuthorUid() {
		return profileAuthorUid;
	}

	public void setProfileAuthorUid(String profileAuthorUid) {
		this.profileAuthorUid = profileAuthorUid;
	}

	public String getAuthoruid() {
		return authoruid;
	}

	public void setAuthoruid(String authoruid) {
		this.authoruid = authoruid;
	}

	public String getSecuid() {
		return secuid;
	}

	public void setSecuid(String secuid) {
		this.secuid = secuid;
	}

	public String getAuthorusername() {
		return authorusername;
	}

	public void setAuthorusername(String authorusername) {
		this.authorusername = authorusername;
	}

	public String getUniqueid() {
		return uniqueid;
	}

	public void setUniqueid(String uniqueid) {
		this.uniqueid = uniqueid;
	}

	public String getAuthoravatar() {
		return authoravatar;
	}

	public void setAuthoravatar(String authoravatar) {
		this.authoravatar = authoravatar;
	}

	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public String getDesc() {
		return desc;
	}

	public void setDesc(String desc) {
		this.desc = desc;
	}

	public String getPublishTime() {
		return publishTime;
	}

	public void setPublishTime(String publishTime) {
		this.publishTime = publishTime;
	}

	public Date getCreateTime() {
		return createTime;
	}

	public void setCreateTime(Date createTime) {
		this.createTime = createTime;
	}

	public String getCover() {
		return cover;
	}

	public void setCover(String cover) {
		this.cover = cover;
	}

	public String getPlayurl() {
		return playurl;
	}

	public void setPlayurl(String playurl) {
		this.playurl = playurl;
	}

	public String getFallbackUrl() {
		return fallbackUrl;
	}

	public void setFallbackUrl(String fallbackUrl) {
		this.fallbackUrl = fallbackUrl;
	}

	public String getHlsstatus() {
		return hlsstatus;
	}

	public void setHlsstatus(String hlsstatus) {
		this.hlsstatus = hlsstatus;
	}

	public String getSourceurl() {
		return sourceurl;
	}

	public void setSourceurl(String sourceurl) {
		this.sourceurl = sourceurl;
	}

	public String getOriginaladdress() {
		return originaladdress;
	}

	public void setOriginaladdress(String originaladdress) {
		this.originaladdress = originaladdress;
	}

	public String getFavorite() {
		return favorite;
	}

	public void setFavorite(String favorite) {
		this.favorite = favorite;
	}

	public String getPrivacy() {
		return privacy;
	}

	public void setPrivacy(String privacy) {
		this.privacy = privacy;
	}

	public String getPlatformkey() {
		return platformkey;
	}

	public void setPlatformkey(String platformkey) {
		this.platformkey = platformkey;
	}

	public String getContenttype() {
		return contenttype;
	}

	public void setContenttype(String contenttype) {
		this.contenttype = contenttype;
	}

	public String getAuthorhomepage() {
		return authorhomepage;
	}

	public void setAuthorhomepage(String authorhomepage) {
		this.authorhomepage = authorhomepage;
	}

	public List<AdminMediaSlide> getSlides() {
		return slides;
	}

	public void setSlides(List<AdminMediaSlide> slides) {
		this.slides = slides == null ? new ArrayList<>() : slides;
	}
}
