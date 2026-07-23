package com.flower.spirit.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import com.flower.spirit.dao.AuthorNameHistoryDao;
import com.flower.spirit.dao.AuthorProfileDao;
import com.flower.spirit.dao.CollectdDataDao;
import com.flower.spirit.dao.GraphicContentDao;
import com.flower.spirit.dao.VideoDataDao;
import com.flower.spirit.dto.AdminAuthorDeletionRequest;
import com.flower.spirit.dto.AdminDeleteWorkRequest;
import com.flower.spirit.entity.AuthorProfileEntity;
import com.flower.spirit.entity.CollectDataEntity;
import com.flower.spirit.entity.GraphicContentEntity;
import com.flower.spirit.entity.VideoDataEntity;
import com.flower.spirit.platform.PlatformCatalog;
import com.flower.spirit.platform.WorkMetadataValidationException;
import com.flower.spirit.task.QuartzTaskService;
import com.flower.spirit.utils.AuthorIdentityUtil;

import jakarta.annotation.PreDestroy;
import jakarta.persistence.criteria.Predicate;

@Service
public class AdminMediaManagementService {

	private static final Logger logger = LoggerFactory.getLogger(AdminMediaManagementService.class);
	private static final long CONFIRMATION_TTL_MILLIS = TimeUnit.MINUTES.toMillis(5);

	private final VideoDataDao videoDataDao;
	private final GraphicContentDao graphicContentDao;
	private final AuthorProfileDao authorProfileDao;
	private final AuthorNameHistoryDao authorNameHistoryDao;
	private final CollectdDataDao collectDataDao;
	private final BlockedWorkService blockedWorkService;
	private final AdminMediaFileService mediaFileService;
	private final HlsTranscodeService hlsTranscodeService;
	private final QuartzTaskService quartzTaskService;
	private final ThreadPoolExecutor deletionExecutor;
	private final ConcurrentMap<String, PreviewGrant> previewGrants = new ConcurrentHashMap<>();
	private final ConcurrentMap<String, JobProgress> jobs = new ConcurrentHashMap<>();
	private final ConcurrentMap<String, String> activeJobsByAuthor = new ConcurrentHashMap<>();

	public AdminMediaManagementService(VideoDataDao videoDataDao, GraphicContentDao graphicContentDao,
			AuthorProfileDao authorProfileDao, AuthorNameHistoryDao authorNameHistoryDao,
			CollectdDataDao collectDataDao, BlockedWorkService blockedWorkService,
			AdminMediaFileService mediaFileService, HlsTranscodeService hlsTranscodeService,
			QuartzTaskService quartzTaskService) {
		this.videoDataDao = videoDataDao;
		this.graphicContentDao = graphicContentDao;
		this.authorProfileDao = authorProfileDao;
		this.authorNameHistoryDao = authorNameHistoryDao;
		this.collectDataDao = collectDataDao;
		this.blockedWorkService = blockedWorkService;
		this.mediaFileService = mediaFileService;
		this.hlsTranscodeService = hlsTranscodeService;
		this.quartzTaskService = quartzTaskService;
		this.deletionExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
				new ArrayBlockingQueue<>(20), runnable -> {
					Thread thread = new Thread(runnable, "admin-author-delete");
					thread.setDaemon(true);
					return thread;
				});
	}

	public Map<String, Object> findWorkMetadata(String workType, Integer id) {
		validateWorkKey(workType, id);
		return "video".equals(normalizeType(workType)) ? videoMetadata(id) : graphicMetadata(id);
	}

	public DeleteWorkResult deleteWork(AdminDeleteWorkRequest request) {
		if (request == null) {
			throw new WorkMetadataValidationException("delete request is required");
		}
		validateWorkKey(request.getWorkType(), request.getId());
		return "video".equals(normalizeType(request.getWorkType()))
				? deleteVideo(request.getId(), request.shouldBlockWork())
				: deleteGraphic(request.getId(), request.shouldBlockWork());
	}

	public AuthorDeletionPreview previewAuthorDeletion(AdminAuthorDeletionRequest request) {
		AuthorIdentity identity = requireAuthorIdentity(request);
		long now = System.currentTimeMillis();
		previewGrants.entrySet().removeIf(entry -> entry.getValue().expiresAt() < now);
		List<VideoDataEntity> videos = findAuthorVideos(identity);
		List<GraphicContentEntity> graphics = findAuthorGraphics(identity);
		List<CollectDataEntity> tasks = findDirectAuthorTasks(identity);
		String token = UUID.randomUUID().toString();
		long expiresAt = now + CONFIRMATION_TTL_MILLIS;
		previewGrants.put(token, new PreviewGrant(identity, expiresAt));
		List<CollectionTaskSummary> taskSummaries = tasks.stream()
				.map(task -> new CollectionTaskSummary(task.getId(), task.getTaskname(), task.getOriginaladdress(),
						!"N".equalsIgnoreCase(task.getTaskenabled())))
				.toList();
		return new AuthorDeletionPreview(identity.platform(), identity.platformKey(), identity.authorUid(),
				videos.size(), graphics.size(), taskSummaries, token, Instant.ofEpochMilli(expiresAt).toString());
	}

	public AuthorDeletionStart startAuthorDeletion(AdminAuthorDeletionRequest request) {
		AuthorIdentity identity = requireAuthorIdentity(request);
		String token = request == null ? null : trimToNull(request.getConfirmationToken());
		PreviewGrant grant = token == null ? null : previewGrants.remove(token);
		if (grant == null || grant.expiresAt() < System.currentTimeMillis()
				|| !grant.identity().equals(identity)) {
			throw new WorkMetadataValidationException("author deletion confirmation is missing or expired");
		}
		String authorKey = identity.key();
		String jobId = UUID.randomUUID().toString();
		String existingJob = activeJobsByAuthor.putIfAbsent(authorKey, jobId);
		if (existingJob != null) {
			return new AuthorDeletionStart(existingJob, true);
		}
		JobProgress progress = new JobProgress(jobId, identity);
		jobs.put(jobId, progress);
		try {
			deletionExecutor.execute(() -> runAuthorDeletion(progress));
		} catch (RejectedExecutionException e) {
			jobs.remove(jobId);
			activeJobsByAuthor.remove(authorKey, jobId);
			throw new WorkMetadataValidationException("author deletion queue is full", e);
		}
		return new AuthorDeletionStart(jobId, false);
	}

	public AuthorDeletionStatus authorDeletionStatus(String jobId) {
		JobProgress progress = jobs.get(trimToNull(jobId));
		if (progress == null) {
			throw new WorkMetadataValidationException("author deletion job not found");
		}
		return progress.snapshot();
	}

	private void runAuthorDeletion(JobProgress progress) {
		AuthorIdentity identity = progress.identity;
		progress.start();
		try {
			List<CollectDataEntity> tasks = findDirectAuthorTasks(identity);
			progress.setTaskTotal(tasks.size());
			progress.phase("tasks", "正在停用作者收藏任务");
			for (CollectDataEntity task : tasks) {
				try {
					boolean running = quartzTaskService.isTaskRunning(task.getId());
					task.setTaskenabled("N");
					collectDataDao.save(task);
					if (running) {
						quartzTaskService.forceDeleteTask(task.getId());
					} else {
						quartzTaskService.removeTaskSchedule(task.getId());
					}
					if (quartzTaskService.isTaskRunning(task.getId())) {
						throw new WorkMetadataValidationException("collection task is still running");
					}
					progress.taskDone();
				} catch (RuntimeException e) {
					progress.fail("task:" + task.getId(), rootMessage(e));
					logger.error("[AuthorDelete] task disable failed platformKey={} authorUid={} taskId={}",
							identity.platformKey(), identity.authorUid(), task.getId(), e);
				}
			}
			if (progress.hasFailures()) {
				progress.finish();
				return;
			}
			// Query works only after direct author tasks have stopped, so anything committed
			// while cancellation was in progress is included in this deletion run.
			List<VideoDataEntity> videos = findAuthorVideos(identity);
			List<GraphicContentEntity> graphics = findAuthorGraphics(identity);
			progress.setWorkTotals(videos.size(), graphics.size());
			progress.phase("videos", "正在删除视频作品");
			for (VideoDataEntity video : videos) {
				try {
					deleteVideo(video.getId(), true);
					progress.videoDone();
				} catch (RuntimeException e) {
					progress.fail("video:" + video.getId(), rootMessage(e));
					logger.error("[AuthorDelete] video failed platformKey={} authorUid={} videoId={}",
							identity.platformKey(), identity.authorUid(), video.getId(), e);
				}
			}
			progress.phase("graphics", "正在删除图文作品");
			for (GraphicContentEntity graphic : graphics) {
				try {
					deleteGraphic(graphic.getId(), true);
					progress.graphicDone();
				} catch (RuntimeException e) {
					progress.fail("graphic:" + graphic.getId(), rootMessage(e));
					logger.error("[AuthorDelete] graphic failed platformKey={} authorUid={} graphicId={}",
							identity.platformKey(), identity.authorUid(), graphic.getId(), e);
				}
			}
			if (!progress.hasFailures()) {
				progress.phase("profile", "正在删除作者档案");
				deleteAuthorProfiles(identity);
			}
			progress.finish();
		} catch (RuntimeException e) {
			progress.fail("job", rootMessage(e));
			progress.finish();
			logger.error("[AuthorDelete] job failed platformKey={} authorUid={} phase={}",
					identity.platformKey(), identity.authorUid(), progress.phase, e);
		} finally {
			activeJobsByAuthor.remove(identity.key(), progress.jobId);
		}
	}

	private DeleteWorkResult deleteVideo(Integer id, boolean blockWork) {
		VideoDataEntity video = videoDataDao.findById(id)
				.orElseThrow(() -> new WorkMetadataValidationException("video work not found: " + id));
		if (!hlsTranscodeService.beginVideoDeletion(id)) {
			throw new WorkMetadataValidationException("video is currently being transcoded to HLS");
		}
		try {
			if (blockWork) {
				blockedWorkService.blockWork(video.getVideoplatform(), video.getVideoid(), "video",
						video.getVideoname(), video.getVideoauthor(), canonicalUid(video.getVideoplatform(),
								video.getAuthoruid(), video.getSecuid()), source(video.getSourceurl(), video.getOriginaladdress()),
						"manual-delete");
			}
			mediaFileService.deleteVideoMedia(video);
			videoDataDao.deleteById(id);
			return new DeleteWorkResult("video", id, "video:" + id, video.getVideoname(),
					canonicalUid(video.getVideoplatform(), video.getAuthoruid(), video.getSecuid()));
		} finally {
			hlsTranscodeService.endVideoDeletion(id);
		}
	}

	private DeleteWorkResult deleteGraphic(Integer id, boolean blockWork) {
		GraphicContentEntity graphic = graphicContentDao.findById(id)
				.orElseThrow(() -> new WorkMetadataValidationException("graphic work not found: " + id));
		if (blockWork) {
			blockedWorkService.blockWork(graphic.getPlatform(), graphic.getVideoid(), "graphic",
					graphic.getTitle(), graphic.getAuthor(), canonicalUid(graphic.getPlatform(),
							graphic.getAuthoruid(), graphic.getSecuid()), source(graphic.getSourceurl(), graphic.getOriginaladdress()),
					"manual-delete");
		}
		mediaFileService.deleteGraphicMedia(graphic);
		graphicContentDao.deleteById(id);
		return new DeleteWorkResult("graphic", id, "graphic:" + id, graphic.getTitle(),
				canonicalUid(graphic.getPlatform(), graphic.getAuthoruid(), graphic.getSecuid()));
	}

	private Map<String, Object> videoMetadata(Integer id) {
		VideoDataEntity video = videoDataDao.findById(id)
				.orElseThrow(() -> new WorkMetadataValidationException("video work not found: " + id));
		Map<String, Object> values = commonMetadata("video", video.getId(), video.getVideoplatform(),
				video.getAuthoruid(), video.getSecuid(), video.getVideoname(), video.getVideodesc(),
				video.getVideoauthor(), video.getAuthorusername(), video.getAuthoravatar(), video.getAuthorhomepage(),
				video.getPublishtime(), video.getSourceurl(), video.getVideotag(), video.getVideoprivacy(), video.getFavorite());
		values.put("coverUrl", video.getVideocover());
		return values;
	}

	private Map<String, Object> graphicMetadata(Integer id) {
		GraphicContentEntity graphic = graphicContentDao.findById(id)
				.orElseThrow(() -> new WorkMetadataValidationException("graphic work not found: " + id));
		return commonMetadata("graphic", graphic.getId(), graphic.getPlatform(), graphic.getAuthoruid(),
				graphic.getSecuid(), graphic.getTitle(), graphic.getContent(), graphic.getAuthor(),
				graphic.getAuthorusername(), graphic.getAuthoravatar(), graphic.getAuthorhomepage(),
				graphic.getPublishtime(), graphic.getSourceurl(), graphic.getTags(), graphic.getPrivacy(), graphic.getFavorite());
	}

	private Map<String, Object> commonMetadata(String type, Integer id, String platform, String authorUid,
			String secUid, String title, String description, String authorName, String authorUsername,
			String authorAvatar, String authorHomepage, String publishTime, String sourceUrl, String tags,
			String privacy, String favorite) {
		String canonicalUid = canonicalUid(platform, authorUid, secUid);
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("workType", type);
		values.put("id", id);
		values.put("platform", platform);
		values.put("authoruid", canonicalUid);
		values.put("title", title);
		values.put("description", description);
		values.put("authorName", authorName);
		values.put("authorUsername", authorUsername);
		values.put("authorAvatar", authorAvatar);
		values.put("authorHomepage", authorHomepage);
		values.put("authorSignature", findAuthorSignature(platform, canonicalUid));
		values.put("publishTime", publishTime);
		values.put("sourceUrl", sourceUrl);
		values.put("tags", tags);
		values.put("privacy", privacy);
		values.put("favorite", favorite);
		return values;
	}

	private String findAuthorSignature(String platform, String authorUid) {
		if (authorUid == null) {
			return null;
		}
		AuthorIdentity identity = requireAuthorIdentity(platform, authorUid);
		return matchingProfiles(identity).stream().map(AuthorProfileEntity::getSignature)
				.filter(AdminMediaManagementService::hasText).findFirst().orElse(null);
	}

	private void deleteAuthorProfiles(AuthorIdentity identity) {
		for (AuthorProfileEntity profile : matchingProfiles(identity)) {
			authorNameHistoryDao.deleteByAuthorprofileid(profile.getId());
			authorProfileDao.delete(profile);
		}
	}

	private List<AuthorProfileEntity> matchingProfiles(AuthorIdentity identity) {
		return authorProfileDao.findByAuthoruid(identity.authorUid()).stream()
				.filter(profile -> identity.platformKey().equals(resolvePlatformKey(profile.getPlatformkey(), profile.getPlatform())))
				.toList();
	}

	private List<VideoDataEntity> findAuthorVideos(AuthorIdentity identity) {
		return videoDataDao.findAll(videoAuthorSpec(identity));
	}

	private List<GraphicContentEntity> findAuthorGraphics(AuthorIdentity identity) {
		return graphicContentDao.findAll(graphicAuthorSpec(identity));
	}

	private Specification<VideoDataEntity> videoAuthorSpec(AuthorIdentity identity) {
		return (root, query, cb) -> cb.and(platformPredicate(root.get("platformkey"), root.get("videoplatform"), identity, cb),
				cb.or(cb.equal(root.get("authoruid"), identity.authorUid()), cb.equal(root.get("secuid"), identity.authorUid())));
	}

	private Specification<GraphicContentEntity> graphicAuthorSpec(AuthorIdentity identity) {
		return (root, query, cb) -> cb.and(platformPredicate(root.get("platformkey"), root.get("platform"), identity, cb),
				cb.or(cb.equal(root.get("authoruid"), identity.authorUid()), cb.equal(root.get("secuid"), identity.authorUid())));
	}

	private Predicate platformPredicate(jakarta.persistence.criteria.Path<String> platformKeyPath,
			jakarta.persistence.criteria.Path<String> legacyPath, AuthorIdentity identity,
			jakarta.persistence.criteria.CriteriaBuilder cb) {
		List<Predicate> matches = new ArrayList<>();
		matches.add(cb.equal(cb.lower(platformKeyPath), identity.platformKey()));
		for (String alias : platformAliases(identity)) {
			matches.add(cb.equal(cb.lower(legacyPath), alias));
		}
		return cb.or(matches.toArray(new Predicate[0]));
	}

	private Set<String> platformAliases(AuthorIdentity identity) {
		Set<String> aliases = new LinkedHashSet<>();
		aliases.add(identity.platform().toLowerCase(Locale.ROOT));
		aliases.add(identity.platformKey());
		for (String alias : PlatformMetadataCompatibilityService.resolveFilterAliases(identity.platform())) {
			if (hasText(alias)) {
				aliases.add(alias.trim().toLowerCase(Locale.ROOT));
			}
		}
		return aliases;
	}

	private List<CollectDataEntity> findDirectAuthorTasks(AuthorIdentity identity) {
		String postAddress = "post" + identity.authorUid();
		return collectDataDao.findAll((root, query, cb) -> cb.and(
				platformPredicate(root.get("platform"), root.get("platform"), identity, cb),
				cb.or(cb.equal(root.get("originaladdress"), identity.authorUid()),
						cb.equal(root.get("originaladdress"), postAddress))));
	}

	private AuthorIdentity requireAuthorIdentity(AdminAuthorDeletionRequest request) {
		if (request == null) {
			throw new WorkMetadataValidationException("author deletion request is required");
		}
		return requireAuthorIdentity(request.getPlatform(), request.getAuthoruid());
	}

	private AuthorIdentity requireAuthorIdentity(String platform, String authorUid) {
		String safePlatform = trimToNull(platform);
		String requestedUid = trimToNull(authorUid);
		if (safePlatform == null || requestedUid == null) {
			throw new WorkMetadataValidationException("platform and canonical author UID are required");
		}
		String platformKey = resolvePlatformKey(null, safePlatform);
		if ("douyin".equals(platformKey) && !AuthorIdentityUtil.isDouyinSecUid(requestedUid)) {
			throw new WorkMetadataValidationException("Douyin author deletion requires an MS4 sec_uid");
		}
		String safeUid = AuthorIdentityUtil.canonicalAuthorUid(safePlatform, requestedUid, requestedUid);
		if (safeUid == null) {
			throw new WorkMetadataValidationException("platform and canonical author UID are required");
		}
		return new AuthorIdentity(safePlatform, platformKey, safeUid);
	}

	private String resolvePlatformKey(String explicitKey, String platform) {
		String resolved = PlatformMetadataCompatibilityService.resolvePlatformKey(explicitKey, platform);
		if (hasText(resolved)) {
			return resolved.trim().toLowerCase(Locale.ROOT);
		}
		String fallback = first(explicitKey, platform);
		return fallback == null ? "" : PlatformCatalog.definitionForExtractor(fallback).getKey();
	}

	private void validateWorkKey(String workType, Integer id) {
		String type = normalizeType(workType);
		if (!"video".equals(type) && !"graphic".equals(type)) {
			throw new WorkMetadataValidationException("workType must be video or graphic");
		}
		if (id == null || id.intValue() <= 0) {
			throw new WorkMetadataValidationException("positive work id is required");
		}
	}

	private static String canonicalUid(String platform, String authorUid, String secUid) {
		return AuthorIdentityUtil.canonicalAuthorUid(platform, authorUid, secUid);
	}

	private static String normalizeType(String value) {
		return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
	}

	private static String source(String sourceUrl, String originalAddress) {
		return hasText(sourceUrl) ? sourceUrl : originalAddress;
	}

	private static String first(String first, String second) {
		return hasText(first) ? first.trim() : (hasText(second) ? second.trim() : null);
	}

	private static String trimToNull(String value) {
		return hasText(value) ? value.trim() : null;
	}

	private static boolean hasText(String value) {
		return value != null && !value.trim().isEmpty();
	}

	private static String rootMessage(Throwable error) {
		Throwable current = error;
		while (current.getCause() != null && current.getCause() != current) {
			current = current.getCause();
		}
		return hasText(current.getMessage()) ? current.getMessage() : current.getClass().getSimpleName();
	}

	@PreDestroy
	public void shutdown() {
		deletionExecutor.shutdownNow();
	}

	public record DeleteWorkResult(String workType, Integer id, String mediaKey, String title, String authorUid) {
	}

	public record CollectionTaskSummary(Integer id, String name, String originalAddress, boolean enabled) {
	}

	public record AuthorDeletionPreview(String platform, String platformKey, String authorUid, int videoCount,
			int graphicCount, List<CollectionTaskSummary> directTasks, String confirmationToken, String expiresAt) {
	}

	public record AuthorDeletionStart(String jobId, boolean alreadyRunning) {
	}

	public record AuthorDeletionStatus(String jobId, String state, String phase, String message, String platform,
			String platformKey, String authorUid, int totalVideos, int deletedVideos, int totalGraphics,
			int deletedGraphics, int totalTasks, int disabledTasks, List<String> failures, String startedAt,
			String completedAt) {
	}

	private record AuthorIdentity(String platform, String platformKey, String authorUid) {
		String key() {
			return platformKey + ":" + authorUid;
		}
	}

	private record PreviewGrant(AuthorIdentity identity, long expiresAt) {
	}

	private static final class JobProgress {
		private final String jobId;
		private final AuthorIdentity identity;
		private final List<String> failures = new ArrayList<>();
		private volatile String state = "queued";
		private volatile String phase = "queued";
		private volatile String message = "等待执行";
		private volatile int totalVideos;
		private volatile int deletedVideos;
		private volatile int totalGraphics;
		private volatile int deletedGraphics;
		private volatile int totalTasks;
		private volatile int disabledTasks;
		private volatile String startedAt;
		private volatile String completedAt;

		private JobProgress(String jobId, AuthorIdentity identity) {
			this.jobId = jobId;
			this.identity = identity;
		}

		private void start() {
			state = "running";
			startedAt = Instant.now().toString();
		}

		private void setTaskTotal(int tasks) {
			totalTasks = tasks;
		}

		private void setWorkTotals(int videos, int graphics) {
			totalVideos = videos;
			totalGraphics = graphics;
		}

		private void phase(String value, String text) {
			phase = value;
			message = text;
		}

		private void videoDone() {
			deletedVideos++;
		}

		private void graphicDone() {
			deletedGraphics++;
		}

		private void taskDone() {
			disabledTasks++;
		}

		private synchronized void fail(String key, String error) {
			failures.add(key + ": " + error);
		}

		private synchronized boolean hasFailures() {
			return !failures.isEmpty();
		}

		private void finish() {
			state = hasFailures() ? "partial_failure" : "completed";
			phase = "done";
			message = hasFailures() ? "删除完成，但有部分项目失败" : "删除完成";
			completedAt = Instant.now().toString();
		}

		private synchronized AuthorDeletionStatus snapshot() {
			return new AuthorDeletionStatus(jobId, state, phase, message, identity.platform(), identity.platformKey(),
					identity.authorUid(), totalVideos, deletedVideos, totalGraphics, deletedGraphics, totalTasks,
					disabledTasks, List.copyOf(failures), startedAt, completedAt);
		}
	}
}
