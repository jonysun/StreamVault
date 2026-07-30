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
@Table(name = "biz_database_maintenance_operation")
public class DatabaseMaintenanceOperationEntity implements Serializable {

	private static final long serialVersionUID = 1L;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@Column(name = "preview_token_hash", nullable = false, unique = true, length = 80)
	private String previewTokenHash;
	@Column(name = "db_fingerprint", nullable = false, length = 80)
	private String dbFingerprint;
	@Column(nullable = false, columnDefinition = "TEXT")
	private String operations;
	@Column(nullable = false, length = 32)
	private String status;
	@Column(name = "current_operation", length = 64)
	private String currentOperation;
	@Column(name = "last_processed_id")
	private Long lastProcessedId;
	@Column(name = "processed_rows", nullable = false)
	private Long processedRows;
	@Column(name = "estimated_rows", nullable = false)
	private Long estimatedRows;
	@Column(name = "batch_size", nullable = false)
	private Integer batchSize;
	@Column(name = "error_message", length = 2048)
	private String errorMessage;
	@Column(name = "created_at", nullable = false)
	private Date createdAt;
	@Column(name = "updated_at", nullable = false)
	private Date updatedAt;
}
