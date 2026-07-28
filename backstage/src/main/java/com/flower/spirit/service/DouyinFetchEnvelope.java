package com.flower.spirit.service;

import java.util.List;
import java.util.Set;

import com.alibaba.fastjson.JSONObject;

public record DouyinFetchEnvelope(List<JSONObject> items, Set<String> newWorkIds,
		String outcome, int pagesFetched, int emptyPages, String lastCursor,
		JSONObject diagnostics) {
}
