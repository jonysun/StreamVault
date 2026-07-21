package com.flower.spirit.dto;

import java.util.LinkedHashMap;
import java.util.Map;

public class UpdateWorkMetadataRequest {

	private String workType;
	private Integer id;
	private Map<String, Object> overrides = new LinkedHashMap<>();
	private boolean syncAuthorProfile;

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

	public Map<String, Object> getOverrides() {
		return overrides;
	}

	public void setOverrides(Map<String, Object> overrides) {
		this.overrides = overrides == null ? new LinkedHashMap<>() : new LinkedHashMap<>(overrides);
	}

	public boolean isSyncAuthorProfile() {
		return syncAuthorProfile;
	}

	public void setSyncAuthorProfile(boolean syncAuthorProfile) {
		this.syncAuthorProfile = syncAuthorProfile;
	}
}
