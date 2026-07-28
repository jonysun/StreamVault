package com.flower.spirit.dto;

public record CollectTaskListItem(Integer id, String taskid, String platform, String taskname, String taskstatus,
		String createtime, String endtime, String count, String carriedout, String originaladdress, String monitoring,
		String taskenabled, String lastCheckTime, String lastid, Integer maxcur, Integer omaxcur, String generatenfo,
		String taskcron, String lastfetchtime, Integer lastfetchcount, Long activeJobId, Long activeRunId,
		String jobState, String runState, Integer queuePosition, String heartbeatAt,
		String fetchState, long downloadQueued, long downloadRunning, long downloadRetryWait,
		long downloadCompleted, long downloadSkipped, long downloadFailed,
		String latestStopReason, String latestFetchWarning) {
}
