package com.flower.spirit.entity;

import java.io.Serializable;
import java.util.Date;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "biz_dtk_admin_sync_job")
public class DtkAdminSyncJobEntity implements Serializable {
	private static final long serialVersionUID = 1L;

	@Id
	@Column(length = 64)
	private String id;
	@Column(length = 16, nullable = false)
	private String mode;
	@Column(length = 16, nullable = false)
	private String status;
	@Column(name = "scanned_count", nullable = false)
	private int scannedCount;
	@Column(name = "added_count", nullable = false)
	private int addedCount;
	@Column(name = "deleted_count", nullable = false)
	private int deletedCount;
	@Column(name = "skipped_count", nullable = false)
	private int skippedCount;
	@Column(name = "failed_count", nullable = false)
	private int failedCount;
	@Column(name = "error_domain", length = 32)
	private String errorDomain;
	@Column(name = "error_code", length = 128)
	private String errorCode;
	@Column(name = "error_message", columnDefinition = "TEXT")
	private String errorMessage;
	@Column(name = "request_id", length = 64)
	private String requestId;
	@Column(name = "created_at", nullable = false)
	private Date createdAt;
	@Column(name = "started_at")
	private Date startedAt;
	@Column(name = "finished_at")
	private Date finishedAt;

	public String getId() { return id; }
	public void setId(String id) { this.id = id; }
	public String getMode() { return mode; }
	public void setMode(String mode) { this.mode = mode; }
	public String getStatus() { return status; }
	public void setStatus(String status) { this.status = status; }
	public int getScannedCount() { return scannedCount; }
	public void setScannedCount(int value) { this.scannedCount = value; }
	public int getAddedCount() { return addedCount; }
	public void setAddedCount(int value) { this.addedCount = value; }
	public int getDeletedCount() { return deletedCount; }
	public void setDeletedCount(int value) { this.deletedCount = value; }
	public int getSkippedCount() { return skippedCount; }
	public void setSkippedCount(int value) { this.skippedCount = value; }
	public int getFailedCount() { return failedCount; }
	public void setFailedCount(int value) { this.failedCount = value; }
	public String getErrorDomain() { return errorDomain; }
	public void setErrorDomain(String value) { this.errorDomain = value; }
	public String getErrorCode() { return errorCode; }
	public void setErrorCode(String value) { this.errorCode = value; }
	public String getErrorMessage() { return errorMessage; }
	public void setErrorMessage(String value) { this.errorMessage = value; }
	public String getRequestId() { return requestId; }
	public void setRequestId(String value) { this.requestId = value; }
	public Date getCreatedAt() { return createdAt; }
	public void setCreatedAt(Date value) { this.createdAt = value; }
	public Date getStartedAt() { return startedAt; }
	public void setStartedAt(Date value) { this.startedAt = value; }
	public Date getFinishedAt() { return finishedAt; }
	public void setFinishedAt(Date value) { this.finishedAt = value; }
}
