package com.flower.spirit.dto;

import java.util.List;

public class DatabaseMaintenanceRequest {

	private String previewToken;
	private List<String> operations;
	private Integer batchSize;

	public String getPreviewToken() {
		return previewToken;
	}

	public void setPreviewToken(String previewToken) {
		this.previewToken = previewToken;
	}

	public List<String> getOperations() {
		return operations;
	}

	public void setOperations(List<String> operations) {
		this.operations = operations;
	}

	public Integer getBatchSize() {
		return batchSize;
	}

	public void setBatchSize(Integer batchSize) {
		this.batchSize = batchSize;
	}
}
