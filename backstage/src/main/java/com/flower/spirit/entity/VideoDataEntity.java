package com.flower.spirit.entity;

import java.io.Serializable;
import java.util.Date;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Column;
import jakarta.persistence.Table;
import jakarta.persistence.TableGenerator;
import jakarta.persistence.Transient;

import com.flower.spirit.common.DataEntity;


/**
 * 视频资源
 * @author flower
 *
 */
@Entity
@Table(name = "biz_video")
public class VideoDataEntity  extends DataEntity<VideoDataEntity> implements Serializable {

	/**
	 * 
	 */
	private static final long serialVersionUID = 7980669221676123703L;
	
	@Id
	@GeneratedValue(strategy = GenerationType.TABLE,generator="biz_video_seq")
	@TableGenerator(name = "biz_video_seq", allocationSize = 1, table = "seq_common", pkColumnName = "seq_id", valueColumnName = "seq_count")
    private Integer id;
	
	/**
	 * 对应视频站的视频id
	 */
	private String videoid;
	
	/**
	 * 源地址
	 */
	public String originaladdress;
	
	private String videoname;
	
	private String videoauthor;

	private String authoruid;

	private String authorusername;

	private String authoravatar;

	private String secuid;

	private String uniqueid;

	@Lob
	private String jsonData;
	
	private String videodesc;
	
	private String videoprivacy;  //视频是否隐私模式  隐私模式 则不会直接显示 图片
	
	private String videotag;     //视频tag
	
	private String videoplatform;
	
	private String videocover;
	
	private String videounrealaddr;
	
	private String videoaddr;
	
	private Date createtime;

	@Column(name = "videoinfo", insertable = false, updatable = false)
	private String videoinfo;

	private String publishtime;

	private String sourceurl;

	private String favorite;
	
	
	
	
	public VideoDataEntity() {
		super();
	}

	public VideoDataEntity(String videoid,String videoname, String videodesc, String videoplatform, String videocover,
			String videoaddr,String videounrealaddr,String originaladdress) {
		super();
		this.videoid = videoid;
		this.videoname = videoname;
		this.videodesc = videodesc;
		this.videoplatform = videoplatform;
		this.videocover = videocover;
		this.videoaddr = videoaddr;
		this.createtime = new Date();
		this.videounrealaddr =videounrealaddr;
		this.originaladdress  = originaladdress;
	}

	public Integer getId() {
		return id;
	}

	public void setId(Integer id) {
		this.id = id;
	}

	public String getVideoname() {
		return videoname;
	}

	public void setVideoname(String videoname) {
		this.videoname = videoname;
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

	public String getVideoaddr() {
		return videoaddr;
	}

	public void setVideoaddr(String videoaddr) {
		this.videoaddr = videoaddr;
	}

	public Date getCreatetime() {
		return createtime;
	}

	public void setCreatetime(Date createtime) {
		this.createtime = createtime;
	}

	public String getVideodesc() {
		return videodesc;
	}

	public void setVideodesc(String videodesc) {
		this.videodesc = videodesc;
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

	public String getVideounrealaddr() {
		return videounrealaddr;
	}

	public void setVideounrealaddr(String videounrealaddr) {
		this.videounrealaddr = videounrealaddr;
	}

	public String getOriginaladdress() {
		return originaladdress;
	}

	public void setOriginaladdress(String originaladdress) {
		this.originaladdress = originaladdress;
	}

	public String getVideoid() {
		return videoid;
	}

	public void setVideoid(String videoid) {
		this.videoid = videoid;
	}

	public String getVideoinfo() {
		return isString(videoinfo) ? videoinfo : jsonData;
	}

	public void setVideoinfo(String videoinfo) {
		this.videoinfo = videoinfo;
		if (!isString(this.jsonData)) {
			this.jsonData = videoinfo;
		}
	}

	public String getPublishtime() {
		return publishtime;
	}

	public void setPublishtime(String publishtime) {
		this.publishtime = publishtime;
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

	public String getAuthoravatar() {
		return authoravatar;
	}

	public void setAuthoravatar(String authoravatar) {
		this.authoravatar = authoravatar;
	}

	public String getSecuid() {
		return secuid;
	}

	public void setSecuid(String secuid) {
		this.secuid = secuid;
	}

	public String getUniqueid() {
		return uniqueid;
	}

	public void setUniqueid(String uniqueid) {
		this.uniqueid = uniqueid;
	}

	public String getJsonData() {
		return jsonData;
	}

	public void setJsonData(String jsonData) {
		this.jsonData = jsonData;
	}

	private boolean isString(String value) {
		return value != null && !value.trim().isEmpty();
	}
	
	/**
	 * 排除的平台（用于查询时排除特定平台的视频，不持久化到数据库）
	 */
	@Transient
	private String excludePlatform;

	@Transient
	private String publishStart;

	@Transient
	private String publishEnd;

	@Transient
	private String sortField;

	@Transient
	private String sortOrder;

	@Transient
	private String playurl;

	@Transient
	private String hlsstatus;

	@Transient
	private String blockwork;

	@Transient
	private String randomMode;

	@Transient
	private String randomSeed;

	@Transient
	private String mediaType;
	
	public String getExcludePlatform() {
		return excludePlatform;
	}
	
	public void setExcludePlatform(String excludePlatform) {
		this.excludePlatform = excludePlatform;
	}

	public String getPublishStart() {
		return publishStart;
	}

	public void setPublishStart(String publishStart) {
		this.publishStart = publishStart;
	}

	public String getPublishEnd() {
		return publishEnd;
	}

	public void setPublishEnd(String publishEnd) {
		this.publishEnd = publishEnd;
	}

	public String getSortField() {
		return sortField;
	}

	public void setSortField(String sortField) {
		this.sortField = sortField;
	}

	public String getSortOrder() {
		return sortOrder;
	}

	public void setSortOrder(String sortOrder) {
		this.sortOrder = sortOrder;
	}

	public String getPlayurl() {
		return playurl;
	}

	public void setPlayurl(String playurl) {
		this.playurl = playurl;
	}

	public String getHlsstatus() {
		return hlsstatus;
	}

	public void setHlsstatus(String hlsstatus) {
		this.hlsstatus = hlsstatus;
	}

	public String getBlockwork() {
		return blockwork;
	}

	public void setBlockwork(String blockwork) {
		this.blockwork = blockwork;
	}

	public String getRandomMode() {
		return randomMode;
	}

	public void setRandomMode(String randomMode) {
		this.randomMode = randomMode;
	}

	public String getRandomSeed() {
		return randomSeed;
	}

	public void setRandomSeed(String randomSeed) {
		this.randomSeed = randomSeed;
	}

	public String getMediaType() {
		return mediaType;
	}

	public void setMediaType(String mediaType) {
		this.mediaType = mediaType;
	}
	

}
