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
@Table(name = "biz_collect_run")
public class CollectRunEntity implements Serializable {

	private static final long serialVersionUID = 1L;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@Column(name = "collect_task_id", nullable = false)
	private Integer collectTaskId;
	@Column(name = "trigger_type", nullable = false)
	private String triggerType;
	@Column(nullable = false)
	private String state;
	@Column(name = "requested_limit")
	private Integer requestedLimit;
	@Column(name = "fetched_count")
	private Integer fetchedCount;
	@Column(name = "planned_count")
	private Integer plannedCount;
	@Column(name = "inserted_count")
	private Integer insertedCount;
	@Column(name = "skipped_existing_count")
	private Integer skippedExistingCount;
	@Column(name = "failed_item_count")
	private Integer failedItemCount;
	@Column(name = "started_at")
	private Date startedAt;
	@Column(name = "heartbeat_at")
	private Date heartbeatAt;
	@Column(name = "finished_at")
	private Date finishedAt;
	@Column(name = "error_code")
	private String errorCode;
	@Column(name = "error_message", length = 2048)
	private String errorMessage;
	@Column(name = "error_detail", length = 10000)
	private String errorDetail;
	@Column(name = "created_at", nullable = false)
	private Date createdAt;
}
