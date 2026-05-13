package com.flower.spirit.entity;

import java.io.Serializable;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.TableGenerator;

import com.flower.spirit.common.DataEntity;

@Entity
@Table(name = "biz_process_history")
public class ProcessHistoryEntity  extends DataEntity<ProcessHistoryEntity> implements Serializable {

	/**
	 * 
	 */
	private static final long serialVersionUID = -8868330893350592420L;
	
	@Id
	@GeneratedValue(strategy = GenerationType.TABLE,generator="biz_process_history")
	@TableGenerator(name = "biz_process_history", allocationSize = 1, table = "seq_common", pkColumnName = "seq_id", valueColumnName = "seq_count")
    private Integer id;
	
	/**
	 * 源地址
	 */
	private String originaladdress;
	
	
	private String videoplatform;
	
	private String status;

	private String tasklog;
	
	private String createtime;

	
	
	
	public ProcessHistoryEntity() {
		super();
	}

	
	public ProcessHistoryEntity(Integer id, String originaladdress, String videoplatform, String status) {
		super();
		this.id = id;
		this.originaladdress = originaladdress;
		this.videoplatform = videoplatform;
		this.status = status;
	}


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

	public String getVideoplatform() {
		return videoplatform;
	}

	public void setVideoplatform(String videoplatform) {
		this.videoplatform = videoplatform;
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

	public String getTasklog() {
		return tasklog;
	}

	public void setTasklog(String tasklog) {
		this.tasklog = tasklog;
	}
	
	
	
	
	

}
