package com.flower.spirit.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.flower.spirit.utils.CommandUtil;
import com.flower.spirit.utils.CommandUtil.F2CommandResult;

@Service
public class DouyinIncrementalFetchService {

	private static final List<String> REQUIRED_ENVELOPE_KEYS = List.of(
			"items", "newWorkIds", "outcome", "pagesFetched", "emptyPages",
			"lastCursor", "diagnostics");
	private static final Set<String> SUCCESSFUL_OUTCOMES = Set.of(
			"NO_PUBLIC_WORKS", "ACCOUNT_DEACTIVATED", "WORKS_UNAVAILABLE",
			"EMPTY_PAGINATION", "KNOWN_BOUNDARY", "INITIAL_LIMIT", "NO_MORE",
			"MAX_PAGE_GUARD");

	private final CommandRunner commandRunner;

	public DouyinIncrementalFetchService() {
		this(CommandUtil::f2IncrementalFetch);
	}

	DouyinIncrementalFetchService(CommandRunner commandRunner) {
		this.commandRunner = Objects.requireNonNull(commandRunner, "commandRunner");
	}

	public DouyinFetchEnvelope fetch(DouyinFetchRequest request) {
		validateRequest(request);
		Path knownIdsFile = null;
		Path outputFile = null;
		try {
			knownIdsFile = Files.createTempFile("stream-vault-douyin-known-", ".json");
			outputFile = Files.createTempFile("stream-vault-douyin-result-", ".json");
			Files.writeString(knownIdsFile,
					JSON.toJSONString(request.knownWorkIds() == null ? Set.of() : request.knownWorkIds()),
					StandardCharsets.UTF_8);

			F2CommandResult commandResult = commandRunner.run(request, knownIdsFile, outputFile);
			if (commandResult == null) {
				throw new IllegalStateException("Douyin incremental command returned no process result");
			}
			if (commandResult.exitCode() != 0) {
				String diagnostic = CommandUtil.sanitizeF2Output(commandResult.output());
				throw new IllegalStateException("Douyin incremental fetch failed exitCode="
						+ commandResult.exitCode() + " output=" + bounded(diagnostic, 2000));
			}
			if (!Files.exists(outputFile) || Files.size(outputFile) == 0) {
				throw new IllegalStateException("Douyin incremental fetch produced no result file content");
			}
			return parseResult(Files.readString(outputFile, StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new IllegalStateException("Douyin incremental fetch file protocol failed: " + e.getMessage(), e);
		} finally {
			deleteQuietly(outputFile);
			deleteQuietly(knownIdsFile);
		}
	}

	private DouyinFetchEnvelope parseResult(String text) {
		Object parsed;
		try {
			parsed = JSON.parse(text);
		} catch (RuntimeException e) {
			throw new IllegalStateException("Malformed Douyin fetch result: " + e.getClass().getSimpleName(), e);
		}
		if (parsed instanceof JSONArray legacyItems) {
			return parseLegacyArray(legacyItems);
		}
		if (!(parsed instanceof JSONObject object)) {
			throw new IllegalStateException("Malformed Douyin fetch result: root must be an object or array");
		}
		return parseEnvelope(object);
	}

	private DouyinFetchEnvelope parseEnvelope(JSONObject object) {
		List<String> missing = REQUIRED_ENVELOPE_KEYS.stream()
				.filter(key -> !object.containsKey(key) || object.get(key) == null)
				.toList();
		if (!missing.isEmpty()) {
			throw new IllegalStateException("Douyin fetch envelope missing required keys: " + missing);
		}
		JSONArray items = requireArray(object, "items");
		JSONArray newWorkIds = requireArray(object, "newWorkIds");
		String outcome = requireString(object, "outcome");
		if (!SUCCESSFUL_OUTCOMES.contains(outcome)) {
			throw new IllegalStateException("Douyin fetch envelope has unsupported outcome: " + outcome);
		}
		String lastCursor = requireString(object, "lastCursor");
		Object rawDiagnostics = object.get("diagnostics");
		if (!(rawDiagnostics instanceof JSONObject diagnostics)) {
			throw new IllegalStateException("Douyin fetch envelope key diagnostics must be an object");
		}
		return new DouyinFetchEnvelope(
				immutableItems(items),
				immutableIds(newWorkIds),
				outcome,
				requireInteger(object, "pagesFetched"),
				requireInteger(object, "emptyPages"),
				lastCursor,
				diagnostics);
	}

	private DouyinFetchEnvelope parseLegacyArray(JSONArray array) {
		List<JSONObject> items = immutableItems(array);
		LinkedHashSet<String> ids = new LinkedHashSet<>();
		for (JSONObject item : items) {
			String workId = item.getString("aweme_id");
			if (workId != null && !workId.isBlank()) {
				ids.add(workId);
			}
		}
		JSONObject diagnostics = new JSONObject(true);
		diagnostics.put("legacyArray", true);
		return new DouyinFetchEnvelope(items, Collections.unmodifiableSet(ids),
				"LEGACY_ARRAY", 0, 0, "", diagnostics);
	}

	private List<JSONObject> immutableItems(JSONArray array) {
		List<JSONObject> items = new ArrayList<>(array.size());
		for (int index = 0; index < array.size(); index++) {
			Object rawItem = array.get(index);
			if (!(rawItem instanceof JSONObject item)) {
				throw new IllegalStateException("Douyin fetch envelope items[" + index + "] must be an object");
			}
			items.add(item);
		}
		return List.copyOf(items);
	}

	private Set<String> immutableIds(JSONArray array) {
		LinkedHashSet<String> ids = new LinkedHashSet<>();
		for (int index = 0; index < array.size(); index++) {
			Object value = array.get(index);
			if (!(value instanceof String id)) {
				throw new IllegalStateException("Douyin fetch envelope newWorkIds[" + index + "] must be a string");
			}
			if (!id.isBlank()) {
				ids.add(id);
			}
		}
		return Collections.unmodifiableSet(ids);
	}

	private JSONArray requireArray(JSONObject object, String key) {
		Object rawValue = object.get(key);
		if (!(rawValue instanceof JSONArray value)) {
			throw new IllegalStateException("Douyin fetch envelope key " + key + " must be an array");
		}
		return value;
	}

	private String requireString(JSONObject object, String key) {
		Object value = object.get(key);
		if (!(value instanceof String text)) {
			throw new IllegalStateException("Douyin fetch envelope key " + key + " must be a string");
		}
		return text;
	}

	private int requireInteger(JSONObject object, String key) {
		Object value = object.get(key);
		if (!(value instanceof Number number)) {
			throw new IllegalStateException("Douyin fetch envelope key " + key + " must be an integer");
		}
		long result = number.longValue();
		if (result < 0 || result > Integer.MAX_VALUE || number.doubleValue() != result) {
			throw new IllegalStateException("Douyin fetch envelope key " + key + " must be a nonnegative integer");
		}
		return (int) result;
	}

	private void validateRequest(DouyinFetchRequest request) {
		Objects.requireNonNull(request, "request");
		if (request.secUserId() == null || request.secUserId().isBlank()) {
			throw new IllegalArgumentException("secUserId is required");
		}
		Objects.requireNonNull(request.mode(), "mode");
		if (request.knownBoundary() <= 0 || request.maxPages() <= 0
				|| request.emptyPageLimit() <= 0 || request.maxItems() < 0) {
			throw new IllegalArgumentException("Douyin fetch limits are invalid");
		}
	}

	private String bounded(String value, int maxLength) {
		if (value == null) {
			return "";
		}
		return value.length() <= maxLength ? value : value.substring(0, maxLength);
	}

	private void deleteQuietly(Path path) {
		if (path == null) {
			return;
		}
		try {
			Files.deleteIfExists(path);
		} catch (IOException ignored) {
			// A failed cleanup must not replace the fetch result or its root cause.
		}
	}

	@FunctionalInterface
	interface CommandRunner {
		F2CommandResult run(DouyinFetchRequest request, Path knownIdsFile, Path outputFile);
	}
}
