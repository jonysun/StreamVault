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
@Table(name = "biz_job_queue")
public class JobQueueEntity implements Serializable {

	private static final long serialVersionUID = 1L;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@Column(name = "job_type", nullable = false)
	private String jobType;
	@Column(name = "dedupe_key", nullable = false)
	private String dedupeKey;
	@Column(nullable = false, length = 4000)
	private String payload;
	@Column(nullable = false)
	private String state;
	@Column(nullable = false)
	private Integer priority;
	@Column(name = "available_at", nullable = false)
	private Date availableAt;
	@Column(name = "attempt_count", nullable = false)
	private Integer attemptCount;
	@Column(name = "max_attempts", nullable = false)
	private Integer maxAttempts;
	@Column(name = "locked_by")
	private String lockedBy;
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
}
