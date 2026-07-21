package com.flower.spirit.common;

import com.fasterxml.jackson.annotation.JsonInclude;

public class AjaxEntity {
	
	private String resCode;
	
	private String message;
	
	private Object record;

	@JsonInclude(JsonInclude.Include.NON_NULL)
	private Integer taskId;

	@JsonInclude(JsonInclude.Include.NON_NULL)
	private String platformKey;

	@JsonInclude(JsonInclude.Include.NON_NULL)
	private String mode;

	@JsonInclude(JsonInclude.Include.NON_NULL)
	private String status;
	
	

	public AjaxEntity(String resCode, String message, Object record) {
		super();
		this.resCode = resCode;
		this.message = message;
		this.record = record;
	}


	public String getResCode() {
		return resCode;
	}

	public void setResCode(String resCode) {
		this.resCode = resCode;
	}

	public String getMessage() {
		return message;
	}

	public void setMessage(String message) {
		this.message = message;
	}

	public Object getRecord() {
		return record;
	}

	public void setRecord(Object record) {
		this.record = record;
	}

	public Integer getTaskId() {
		return taskId;
	}

	public void setTaskId(Integer taskId) {
		this.taskId = taskId;
	}

	public String getPlatformKey() {
		return platformKey;
	}

	public void setPlatformKey(String platformKey) {
		this.platformKey = platformKey;
	}

	public String getMode() {
		return mode;
	}

	public void setMode(String mode) {
		this.mode = mode;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}
	
	

}
