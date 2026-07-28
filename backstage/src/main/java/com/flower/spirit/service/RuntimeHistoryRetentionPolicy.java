package com.flower.spirit.service;

public final class RuntimeHistoryRetentionPolicy {

	public static final int NON_FAILED_RUN_ITEM_DAYS = 90;
	public static final int FAILED_RUN_ITEM_DAYS = 365;
	public static final int TERMINAL_HISTORY_DAYS = 90;

	private RuntimeHistoryRetentionPolicy() {
	}
}
