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
}
