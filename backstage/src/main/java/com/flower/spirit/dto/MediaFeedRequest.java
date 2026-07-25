package com.flower.spirit.dto;

public class MediaFeedRequest {

	private String type;
	private String order;
	private Integer limit;
	private String cursor;
	private String platformKey;
	private String authorUid;

	public String getType() {
		return type;
	}

	public void setType(String type) {
		this.type = type;
	}

	public String getOrder() {
		return order;
	}

	public void setOrder(String order) {
		this.order = order;
	}

	public Integer getLimit() {
		return limit;
	}

	public void setLimit(Integer limit) {
		this.limit = limit;
	}

	public String getCursor() {
		return cursor;
	}

	public void setCursor(String cursor) {
		this.cursor = cursor;
	}

	public String getPlatformKey() {
		return platformKey;
	}

	public void setPlatformKey(String platformKey) {
		this.platformKey = platformKey;
	}

	public String getAuthorUid() {
		return authorUid;
	}

	public void setAuthorUid(String authorUid) {
		this.authorUid = authorUid;
	}
}
