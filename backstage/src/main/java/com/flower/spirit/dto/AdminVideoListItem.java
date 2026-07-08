package com.flower.spirit.dto;

import java.util.Date;

import com.flower.spirit.entity.VideoDataEntity;

public class AdminVideoListItem {

	private Integer id;
	private String videoid;
	private String videoname;
	private String videodesc;
	private String videoplatform;
	private String videocover;
	private String videounrealaddr;
	private String playurl;
	private String videoprivacy;
	private String videotag;
	private String videoauthor;
	private String authoruid;
	private String authorusername;
	private String publishtime;
	private Date createtime;
	private String hlsstatus;
	private String sourceurl;
	private String favorite;
	private String originaladdress;

	public static AdminVideoListItem from(VideoDataEntity video) {
		AdminVideoListItem item = new AdminVideoListItem();
		if (video == null) {
			return item;
		}
		item.setId(video.getId());
		item.setVideoid(video.getVideoid());
		item.setVideoname(video.getVideoname());
		item.setVideodesc(video.getVideodesc());
		item.setVideoplatform(video.getVideoplatform());
		item.setVideocover(video.getVideocover());
		item.setVideounrealaddr(video.getVideounrealaddr());
		item.setPlayurl(video.getPlayurl());
		item.setVideoprivacy(video.getVideoprivacy());
		item.setVideotag(video.getVideotag());
		item.setVideoauthor(video.getVideoauthor());
		item.setAuthoruid(video.getAuthoruid());
		item.setAuthorusername(video.getAuthorusername());
		item.setPublishtime(video.getPublishtime());
		item.setCreatetime(video.getCreatetime());
		item.setHlsstatus(video.getHlsstatus());
		item.setSourceurl(video.getSourceurl());
		item.setFavorite(video.getFavorite());
		item.setOriginaladdress(video.getOriginaladdress());
		return item;
	}

	public Integer getId() {
		return id;
	}

	public void setId(Integer id) {
		this.id = id;
	}

	public String getVideoid() {
		return videoid;
	}

	public void setVideoid(String videoid) {
		this.videoid = videoid;
	}

	public String getVideoname() {
		return videoname;
	}

	public void setVideoname(String videoname) {
		this.videoname = videoname;
	}

	public String getVideodesc() {
		return videodesc;
	}

	public void setVideodesc(String videodesc) {
		this.videodesc = videodesc;
	}

	public String getVideoplatform() {
		return videoplatform;
	}

	public void setVideoplatform(String videoplatform) {
		this.videoplatform = videoplatform;
	}

	public String getVideocover() {
		return videocover;
	}

	public void setVideocover(String videocover) {
		this.videocover = videocover;
	}

	public String getVideounrealaddr() {
		return videounrealaddr;
	}

	public void setVideounrealaddr(String videounrealaddr) {
		this.videounrealaddr = videounrealaddr;
	}

	public String getPlayurl() {
		return playurl;
	}

	public void setPlayurl(String playurl) {
		this.playurl = playurl;
	}

	public String getVideoprivacy() {
		return videoprivacy;
	}

	public void setVideoprivacy(String videoprivacy) {
		this.videoprivacy = videoprivacy;
	}

	public String getVideotag() {
		return videotag;
	}

	public void setVideotag(String videotag) {
		this.videotag = videotag;
	}

	public String getVideoauthor() {
		return videoauthor;
	}

	public void setVideoauthor(String videoauthor) {
		this.videoauthor = videoauthor;
	}

	public String getAuthoruid() {
		return authoruid;
	}

	public void setAuthoruid(String authoruid) {
		this.authoruid = authoruid;
	}

	public String getAuthorusername() {
		return authorusername;
	}

	public void setAuthorusername(String authorusername) {
		this.authorusername = authorusername;
	}

	public String getPublishtime() {
		return publishtime;
	}

	public void setPublishtime(String publishtime) {
		this.publishtime = publishtime;
	}

	public Date getCreatetime() {
		return createtime;
	}

	public void setCreatetime(Date createtime) {
		this.createtime = createtime;
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

	public String getFavorite() {
		return favorite;
	}

	public void setFavorite(String favorite) {
		this.favorite = favorite;
	}

	public String getOriginaladdress() {
		return originaladdress;
	}

	public void setOriginaladdress(String originaladdress) {
		this.originaladdress = originaladdress;
	}
}
