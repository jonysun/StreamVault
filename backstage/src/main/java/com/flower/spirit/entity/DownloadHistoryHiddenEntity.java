package com.flower.spirit.entity;

import java.io.Serializable;
import java.util.Date;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "biz_download_history_hidden")
public class DownloadHistoryHiddenEntity implements Serializable {

	private static final long serialVersionUID = 1L;
	@Id
	@Column(name = "record_key", length = 80)
	private String recordKey;
	@Column(name = "source_type", nullable = false, length = 32)
	private String sourceType;
	@Column(name = "source_id", nullable = false)
	private Long sourceId;
	@Column(name = "hidden_at", nullable = false)
	private Date hiddenAt;

	protected DownloadHistoryHiddenEntity() {
	}
}
