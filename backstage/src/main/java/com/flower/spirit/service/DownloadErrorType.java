package com.flower.spirit.service;

/** SQL predicates for the download center's named error filters, including older queue records. */
public enum DownloadErrorType {
	COOLDOWN("%s IN ('DTK_NODE_COOLDOWN','DOUYIN_GLOBAL_COOLDOWN') OR %s LIKE '%%dtk 节点仍在冷却%%'"),
	DOWNLOAD_TARGET_EXISTS("%s='DOWNLOAD_TARGET_EXISTS' OR %s LIKE '%%download target already exists:%%'"),
	REMOTE_UNAVAILABLE("%s IN ('DTK_WORK_NOT_FOUND','F2_WORK_UNAVAILABLE') OR %s LIKE '%%dtk api http status=404, endpoint=/api/v1/douyin/video,%%'");

	private final String predicate;

	DownloadErrorType(String predicate) {
		this.predicate = predicate;
	}

	/** Column names are supplied by the application's fixed SQL branches. */
	public String sql(String codeColumn, String messageColumn) {
		return "(" + predicate.formatted(codeColumn, "LOWER(COALESCE(" + messageColumn + ",''))") + ")";
	}
}
