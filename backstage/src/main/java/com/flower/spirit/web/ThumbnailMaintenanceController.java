package com.flower.spirit.web;

import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.flower.spirit.config.Global;
import com.flower.spirit.service.WorkRefreshService;

/** The container CLI reuses running platform adapters, cookies and request controls. */
@RestController
public class ThumbnailMaintenanceController {

	private final WorkRefreshService refreshService;

	public ThumbnailMaintenanceController(WorkRefreshService refreshService) {
		this.refreshService = refreshService;
	}

	@PostMapping("/api/maintenance/thumbnail-source")
	public ResponseEntity<Map<String, String>> source(HttpServletRequest request,
			@RequestHeader(value = "X-StreamVault-Token", required = false) String token,
			@RequestParam Integer id) {
		if (!Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1").contains(request.getRemoteAddr())
				|| token == null || token.isBlank() || Global.apptoken == null
				|| !Global.apptoken.equals(token)) {
			return ResponseEntity.status(403).body(Map.of("error", "maintenance access denied"));
		}
		try {
			return ResponseEntity.ok(Map.of("coverUrl", refreshService.resolveVideoCover(id)));
		} catch (RuntimeException error) {
			// Provider exceptions can contain signed URLs. Do not return them to the CLI/logs.
			return ResponseEntity.status(502).body(Map.of("error", "cover source refresh failed"));
		}
	}
}
