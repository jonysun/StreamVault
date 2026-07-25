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
@Table(name = "biz_collect_run_event")
public class CollectRunEventEntity implements Serializable {

	private static final long serialVersionUID = 1L;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@Column(name = "run_id", nullable = false)
	private Long runId;
	@Column(nullable = false)
	private Integer sequence;
	@Column(nullable = false)
	private String level;
	@Column(nullable = false)
	private String stage;
	@Column(name = "event_code", nullable = false)
	private String eventCode;
	@Column(nullable = false, length = 4000)
	private String message;
	@Column(name = "work_id")
	private String workId;
	@Column(name = "created_at", nullable = false)
	private Date createdAt;
}
