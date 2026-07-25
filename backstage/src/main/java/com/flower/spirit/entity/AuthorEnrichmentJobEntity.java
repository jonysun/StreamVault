package com.flower.spirit.entity;

import java.io.Serializable;
import java.util.Date;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "biz_author_enrichment_job")
public class AuthorEnrichmentJobEntity implements Serializable {

	private static final long serialVersionUID = 4580516144024485556L;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Integer id;

	@Column(name = "platform_key", nullable = false)
	private String platformKey;

	@Column(name = "author_uid", nullable = false)
	private String authorUid;

	@Column(nullable = false)
	private String state;

	@Column(nullable = false)
	private Integer priority;

	@Column(name = "attempt_count", nullable = false)
	private Integer attemptCount;

	@Column(name = "next_attempt_at", nullable = false)
	private Date nextAttemptAt;

	@Column(name = "locked_at")
	private Date lockedAt;

	@Column(name = "last_error_code")
	private String lastErrorCode;

	@Column(name = "last_error_message", length = 2048)
	private String lastErrorMessage;

	@Column(name = "created_at", nullable = false)
	private Date createdAt;

	@Column(name = "updated_at", nullable = false)
	private Date updatedAt;

	public Integer getId() {
		return id;
	}

	public void setId(Integer id) {
		this.id = id;
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

	public String getState() {
		return state;
	}

	public void setState(String state) {
		this.state = state;
	}

	public Integer getPriority() {
		return priority;
	}

	public void setPriority(Integer priority) {
		this.priority = priority;
	}

	public Integer getAttemptCount() {
		return attemptCount;
	}

	public void setAttemptCount(Integer attemptCount) {
		this.attemptCount = attemptCount;
	}

	public Date getNextAttemptAt() {
		return nextAttemptAt;
	}

	public void setNextAttemptAt(Date nextAttemptAt) {
		this.nextAttemptAt = nextAttemptAt;
	}

	public Date getLockedAt() {
		return lockedAt;
	}

	public void setLockedAt(Date lockedAt) {
		this.lockedAt = lockedAt;
	}

	public String getLastErrorCode() {
		return lastErrorCode;
	}

	public void setLastErrorCode(String lastErrorCode) {
		this.lastErrorCode = lastErrorCode;
	}

	public String getLastErrorMessage() {
		return lastErrorMessage;
	}

	public void setLastErrorMessage(String lastErrorMessage) {
		this.lastErrorMessage = lastErrorMessage;
	}

	public Date getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(Date createdAt) {
		this.createdAt = createdAt;
	}

	public Date getUpdatedAt() {
		return updatedAt;
	}

	public void setUpdatedAt(Date updatedAt) {
		this.updatedAt = updatedAt;
	}
}
