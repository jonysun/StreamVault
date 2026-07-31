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
@Table(name = "biz_collect_run_item")
public class CollectRunItemEntity implements Serializable {

	private static final long serialVersionUID = 1L;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@Column(name = "run_id", nullable = false)
	private Long runId;
	@Column(nullable = false)
	private Integer ordinal;
	@Column(name = "platform_key", nullable = false)
	private String platformKey;
	@Column(name = "work_id", nullable = false)
	private String workId;
	@Column(name = "author_uid")
	private String authorUid;
	@Column(name = "nickname_snapshot")
	private String nicknameSnapshot;
	@Column(name = "title_snapshot", length = 2000)
	private String titleSnapshot;
	@Column(name = "publish_time")
	private String publishTime;
	@Column(name = "media_type")
	private String mediaType;
	@Column(nullable = false)
	private String decision;
	@Column(name = "process_state", nullable = false)
	private String processState;
	@Column(name = "error_code")
	private String errorCode;
	@Column(name = "error_message", length = 2048)
	private String errorMessage;
	@Column(name = "created_at", nullable = false)
	private Date createdAt;
	@Column(name = "updated_at", nullable = false)
	private Date updatedAt;
	@Column(name = "attempt_count", nullable = false, columnDefinition = "INTEGER DEFAULT 0")
	private Integer attemptCount;
	@Column(name = "max_attempts", nullable = false, columnDefinition = "INTEGER DEFAULT 4")
	private Integer maxAttempts;
	@Column(name = "available_at")
	private Date availableAt;
	@Column(name = "locked_by")
	private String lockedBy;
	@Column(name = "locked_at")
	private Date lockedAt;
	@Column(name = "started_at")
	private Date startedAt;
	@Column(name = "finished_at")
	private Date finishedAt;
	@Column(name = "error_detail", columnDefinition = "TEXT")
	private String errorDetail;
	@Column(name = "queue_generation", length = 32)
	private String queueGeneration;

	public Integer getAttemptCount() {
		return attemptCount;
	}

	public void setAttemptCount(Integer attemptCount) {
		this.attemptCount = attemptCount;
	}

	public Integer getMaxAttempts() {
		return maxAttempts;
	}

	public void setMaxAttempts(Integer maxAttempts) {
		this.maxAttempts = maxAttempts;
	}

	public Date getAvailableAt() {
		return availableAt;
	}

	public void setAvailableAt(Date availableAt) {
		this.availableAt = availableAt;
	}

	public String getLockedBy() {
		return lockedBy;
	}

	public void setLockedBy(String lockedBy) {
		this.lockedBy = lockedBy;
	}

	public Date getLockedAt() {
		return lockedAt;
	}

	public void setLockedAt(Date lockedAt) {
		this.lockedAt = lockedAt;
	}

	public Date getStartedAt() {
		return startedAt;
	}

	public void setStartedAt(Date startedAt) {
		this.startedAt = startedAt;
	}

	public Date getFinishedAt() {
		return finishedAt;
	}

	public void setFinishedAt(Date finishedAt) {
		this.finishedAt = finishedAt;
	}

	public String getErrorDetail() {
		return errorDetail;
	}

	public void setErrorDetail(String errorDetail) {
		this.errorDetail = errorDetail;
	}

	public String getQueueGeneration() {
		return queueGeneration;
	}

	public void setQueueGeneration(String queueGeneration) {
		this.queueGeneration = queueGeneration;
	}
}
