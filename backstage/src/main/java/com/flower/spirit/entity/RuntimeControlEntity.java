package com.flower.spirit.entity;

import java.io.Serializable;
import java.util.Date;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "biz_runtime_control")
public class RuntimeControlEntity implements Serializable {

	private static final long serialVersionUID = 1L;

	@Id
	@Column(name = "control_key", nullable = false)
	private String controlKey;
	@Column(nullable = false)
	private Integer enabled;
	@Column(name = "updated_at", nullable = false)
	private Date updatedAt;
	@Column(name = "updated_by")
	private String updatedBy;
	@Column(length = 1000)
	private String reason;
}
