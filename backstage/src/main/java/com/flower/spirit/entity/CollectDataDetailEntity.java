package com.flower.spirit.entity;

import java.io.Serializable;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Column;
import jakarta.persistence.Table;
import jakarta.persistence.TableGenerator;

import com.flower.spirit.common.DataEntity;

@Entity
@Table(name = "biz_collect_data_detail")
public class CollectDataDetailEntity extends DataEntity<CollectDataDetailEntity> implements Serializable  {

	/**
	 * 
	 */
	private static final long serialVersionUID = -2752642646580560817L;
	
	
	@Id
	@GeneratedValue(strategy = GenerationType.TABLE,generator="biz_collect_data_detail")
	@TableGenerator(name = "biz_collect_data_detail", allocationSize = 1, table = "seq_common", pkColumnName = "seq_id", valueColumnName = "seq_count")
    private Integer id;
	
	private Integer dataid;
	
	private String videoid;
	
	@Column(length = 2000)
	private String videoname;
	
	private String originaladdress;
	
	private String status;

	private String mediatype;

	@Lob
	private String detailjson;

	@Lob
	private String processlog;

	private String errorcode;

	private String errormsg;
	
	private String createtime;
	
	
	public Integer getId() {
		return id;
	}

	public void setId(Integer id) {
		this.id = id;
	}

	public Integer getDataid() {
		return dataid;
	}

	public void setDataid(Integer dataid) {
		this.dataid = dataid;
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

	public String getOriginaladdress() {
		return originaladdress;
	}

	public void setOriginaladdress(String originaladdress) {
		this.originaladdress = originaladdress;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public String getCreatetime() {
		return createtime;
	}

	public void setCreatetime(String createtime) {
		this.createtime = createtime;
	}

	public String getMediatype() {
		return mediatype;
	}

	public void setMediatype(String mediatype) {
		this.mediatype = mediatype;
	}

	public String getDetailjson() {
		return detailjson;
	}

	public void setDetailjson(String detailjson) {
		this.detailjson = detailjson;
	}

	public String getProcesslog() {
		return processlog;
	}

	public void setProcesslog(String processlog) {
		this.processlog = processlog;
	}

	public String getErrorcode() {
		return errorcode;
	}

	public void setErrorcode(String errorcode) {
		this.errorcode = errorcode;
	}

	public String getErrormsg() {
		return errormsg;
	}

	public void setErrormsg(String errormsg) {
		this.errormsg = errormsg;
	}

	
	
	
	
	

}
