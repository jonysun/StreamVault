package com.flower.spirit.entity;

import java.io.Serializable;
import java.util.Date;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.TableGenerator;
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
	@GeneratedValue(strategy = GenerationType.TABLE,generator="biz_graphic_content")
	@TableGenerator(name = "biz_graphic_content", allocationSize = 1, table = "seq_common", pkColumnName = "seq_id", valueColumnName = "seq_count")
    private Integer id;
	
	private String originaladdress;
	
	private String videoid;
	
	public String platform;
	
	private String title;
	
	private String content;
	
	private String images;
	
	private String markroute;
	
	private String author;
	
	private String tags;
	
	private Date createtime;

	private String publishtime;

	private String sourceurl;

	@Transient
	private String publishStart;

	@Transient
	private String publishEnd;

	@Transient
	private String sortField;

	@Transient
	private String sortOrder;

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

	public String getMarkroute() {
		return markroute;
	}

	public void setMarkroute(String markroute) {
		this.markroute = markroute;
	}

	
}
