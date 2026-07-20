package com.flower.spirit.platform;

public enum WorkContentType {
	VIDEO("video"),
	GRAPHIC("graphic"),
	MIXED("mixed");

	private final String value;

	WorkContentType(String value) {
		this.value = value;
	}

	public String getValue() {
		return value;
	}
}
