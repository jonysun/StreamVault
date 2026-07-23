package com.flower.spirit.dto;

public class AdminDeleteWorkRequest {

	private String workType;
	private Integer id;
	private Boolean blockWork;

	public String getWorkType() {
		return workType;
	}

	public void setWorkType(String workType) {
		this.workType = workType;
	}

	public Integer getId() {
		return id;
	}

	public void setId(Integer id) {
		this.id = id;
	}

	public Boolean getBlockWork() {
		return blockWork;
	}

	public void setBlockWork(Boolean blockWork) {
		this.blockWork = blockWork;
	}

	public boolean shouldBlockWork() {
		return blockWork == null || blockWork.booleanValue();
	}
}
