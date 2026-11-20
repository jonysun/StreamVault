package com.flower.spirit.dto;

public class MediaFeedRequest {

	private String type;
	private String order;
	/**
	 * 排序键：{@code publishtime}（作者发布时间，默认）或 {@code createtime}（下载时间）。
	 *
	 * 按下载时间排是为了解决「刚下载一个很久以前发布的老作品，它却插在列表最前面，
	 * 看着像没下载成功」的困惑（后台的图文列表默认就是按 id 倒序 = 下载顺序）。
	 */
	private String sortBy;
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

	public String getSortBy() {
		return sortBy;
	}

	public void setSortBy(String sortBy) {
		this.sortBy = sortBy;
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
