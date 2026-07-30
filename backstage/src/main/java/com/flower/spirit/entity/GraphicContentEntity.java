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
import jakarta.persistence.Transient;

import com.flower.spirit.common.DataEntity;

@Entity
@Table(name = "biz_graphic_content")
public class GraphicContentEntity extends DataEntity<GraphicContentEntity> implements Serializable {

	/**
	 * 
	 */
	private static final long serialVersionUID = -4654916791710362219L;
	
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
	
	private String originaladdress;
	
	private String videoid;
	
	public String platform;
	
	@Column(length = 2000)
	private String title;
	
	@Lob
	private String content;
	
	@Lob
	private String images;
	
	private String markroute;
	
	private String author;

	private String authoruid;

	private String authorusername;

	private String authoravatar;

	private String secuid;

	private String uniqueid;

	@Lob
	private String jsonData;
	
	private String tags;
	
	private Date createtime;

	private String publishtime;

	private String sourceurl;

	@Column(length = 64)
	private String platformkey;

	@Column(length = 32)
	private String contenttype;

	@Column(length = 512)
	private String authorhomepage;

	@Lob
	private String metadataoverrides;

	private Date metadataeditedat;

	private String metadataeditedby;

	@Column(length = 32)
	private String privacy;

	@Column(length = 32)
	private String favorite;

	@Transient
	private String publishStart;

	@Transient
	private String publishEnd;

	@Transient
	private String sortField;

	@Transient
	private String sortOrder;

	@Transient
	private String blockwork;

	public Integer getId() {
		return id;
	}

	public void setId(Integer id) {
		this.id = id;
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

	public String getPlatform() {
		return platform;
	}

	public void setPlatform(String platform) {
		this.platform = platform;
	}

	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public String getContent() {
		return content;
	}

	public void setContent(String content) {
		this.content = content;
	}

	public String getImages() {
		return images;
	}

	public void setImages(String images) {
		this.images = images;
	}

	public String getAuthor() {
		return author;
	}

	public void setAuthor(String author) {
		this.author = author;
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

	public String getTags() {
		return tags;
	}

	public void setTags(String tags) {
		this.tags = tags;
	}

	public Date getCreatetime() {
		return createtime;
	}

	public void setCreatetime(Date createtime) {
		this.createtime = createtime;
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

	public String getMetadataoverrides() {
		return metadataoverrides;
	}

	public void setMetadataoverrides(String metadataoverrides) {
		this.metadataoverrides = metadataoverrides;
	}

	public Date getMetadataeditedat() {
		return metadataeditedat;
	}

	public void setMetadataeditedat(Date metadataeditedat) {
		this.metadataeditedat = metadataeditedat;
	}

	public String getMetadataeditedby() {
		return metadataeditedby;
	}

	public void setMetadataeditedby(String metadataeditedby) {
		this.metadataeditedby = metadataeditedby;
	}

	public String getPrivacy() {
		return privacy;
	}

	public void setPrivacy(String privacy) {
		this.privacy = privacy;
	}

	public String getFavorite() {
		return favorite;
	}

	public void setFavorite(String favorite) {
		this.favorite = favorite;
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

	public String getBlockwork() {
		return blockwork;
	}

	public void setBlockwork(String blockwork) {
		this.blockwork = blockwork;
	}

	public String getMarkroute() {
		return markroute;
	}

	public void setMarkroute(String markroute) {
		this.markroute = markroute;
	}

	
}
