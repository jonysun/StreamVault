package com.flower.spirit.service;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.alibaba.fastjson.JSONObject;
import com.flower.spirit.common.AjaxEntity;
import com.flower.spirit.config.Global;
import com.flower.spirit.dao.VideoDataDao;
import com.flower.spirit.dto.AdminVideoListItem;
import com.flower.spirit.entity.VideoDataEntity;
import com.flower.spirit.utils.BiliUtil;
import com.flower.spirit.utils.CommandUtil;
import com.flower.spirit.utils.StringUtil;


@Service
public class VideoDataService {
	
    @Value("${file.save}")
    private String savefile;
    
    @Value("${file.save.path}")
    private String uploadRealPath;
	
	
	@Autowired
	private VideoDataDao videoDataDao;

	@PersistenceContext
	private EntityManager entityManager;

	@Autowired
	private HlsTranscodeService hlsTranscodeService;

	@Autowired
	private BlockedWorkService blockedWorkService;

	@Autowired
	private DouyinWorkMaintenanceService douyinWorkMaintenanceService;

	private Logger logger = LoggerFactory.getLogger(VideoDataService.class);

	public List<VideoDataEntity> findByVideoid(String videoid) {
		return videoDataDao.findByVideoid(videoid);
	}
	
	
	public AjaxEntity findPage(VideoDataEntity res) {
		return findPage(res, false);
	}

	public AjaxEntity findPage(VideoDataEntity res, boolean lite) {
		if (lite) {
			return findLitePage(res);
		}
		int pageNo = res == null ? 0 : Math.max(0, res.getPageNo());
		int pageSize = res == null ? 25 : Math.max(1, res.getPageSize());
		PageRequest of = PageRequest.of(pageNo, pageSize);
		boolean randomMode = res != null && "1".equals(String.valueOf(res.getRandomMode()));
		String randomSeed = res == null ? null : res.getRandomSeed();
		Specification<VideoDataEntity> specification = buildFindSpecification(res, randomMode);

		Page<VideoDataEntity> findAll;
		if (randomMode) {
			List<VideoDataEntity> all = videoDataDao.findAll(specification);
			stabilizeRandomSourceOrder(all);
			long seed = randomSeed == null ? System.nanoTime() : randomSeed.hashCode();
			java.util.Random random = new java.util.Random(seed);
			java.util.Collections.shuffle(all, random);
			int from = Math.min(pageNo * pageSize, all.size());
			int to = Math.min(from + pageSize, all.size());
			List<VideoDataEntity> pageList = from >= to ? new ArrayList<>() : all.subList(from, to);
			findAll = new PageImpl<>(pageList, of, all.size());
		} else {
			findAll = videoDataDao.findAll(specification, of);
		}
		if (findAll != null && findAll.getContent() != null) {
			enrichVideoItems(findAll.getContent());
		}
		if (lite) {
			List<AdminVideoListItem> lightweightItems = findAll.getContent().stream()
					.map(AdminVideoListItem::from)
					.toList();
			Page<AdminVideoListItem> lightweightPage = new PageImpl<>(lightweightItems, of, findAll.getTotalElements());
			return new AjaxEntity(Global.ajax_success, "数据获取成功", lightweightPage);
		}
		return new AjaxEntity(Global.ajax_success, "数据获取成功", findAll);
	}

	private AjaxEntity findLitePage(VideoDataEntity res) {
		int pageNo = res == null ? 0 : Math.max(0, res.getPageNo());
		int pageSize = res == null ? 25 : Math.max(1, res.getPageSize());
		PageRequest pageRequest = PageRequest.of(pageNo, pageSize);
		boolean randomMode = res != null && "1".equals(String.valueOf(res.getRandomMode()));
		String randomSeed = res == null ? null : res.getRandomSeed();

		List<VideoDataEntity> pageItems;
		long totalElements;
		if (randomMode) {
			List<VideoDataEntity> allItems = findLiteItems(res, randomMode, null, null);
			stabilizeRandomSourceOrder(allItems);
			long seed = randomSeed == null ? System.nanoTime() : randomSeed.hashCode();
			java.util.Collections.shuffle(allItems, new java.util.Random(seed));
			int from = Math.min(pageNo * pageSize, allItems.size());
			int to = Math.min(from + pageSize, allItems.size());
			pageItems = from >= to ? new ArrayList<>() : new ArrayList<>(allItems.subList(from, to));
			totalElements = allItems.size();
		} else {
			totalElements = countLiteItems(res);
			pageItems = findLiteItems(res, randomMode, pageNo * pageSize, pageSize);
		}

		enrichVideoItems(pageItems);
		List<AdminVideoListItem> lightweightItems = pageItems.stream()
				.map(AdminVideoListItem::from)
				.toList();
		Page<AdminVideoListItem> lightweightPage = new PageImpl<>(lightweightItems, pageRequest, totalElements);
		return new AjaxEntity(Global.ajax_success, "数据获取成功", lightweightPage);
	}

	private List<VideoDataEntity> findLiteItems(VideoDataEntity res, boolean randomMode, Integer offset, Integer limit) {
		CriteriaBuilder cb = entityManager.getCriteriaBuilder();
		CriteriaQuery<Tuple> query = cb.createTupleQuery();
		Root<VideoDataEntity> root = query.from(VideoDataEntity.class);
		query.multiselect(
				root.get("id").alias("id"),
				root.get("videoid").alias("videoid"),
				root.get("videoname").alias("videoname"),
				root.get("videodesc").alias("videodesc"),
				root.get("videoplatform").alias("videoplatform"),
				root.get("videocover").alias("videocover"),
				root.get("videounrealaddr").alias("videounrealaddr"),
				root.get("videoprivacy").alias("videoprivacy"),
				root.get("videotag").alias("videotag"),
				root.get("videoauthor").alias("videoauthor"),
				root.get("authoruid").alias("authoruid"),
				root.get("authorusername").alias("authorusername"),
				root.get("authoravatar").alias("authoravatar"),
				root.get("publishtime").alias("publishtime"),
				root.get("createtime").alias("createtime"),
				root.get("sourceurl").alias("sourceurl"),
				root.get("favorite").alias("favorite"),
				root.get("originaladdress").alias("originaladdress"),
				root.get("videoaddr").alias("videoaddr"));
		query.where(buildLitePredicates(res, root, cb));
		if (!randomMode) {
			query.orderBy(buildLiteOrders(res, root, cb));
		}
		var typedQuery = entityManager.createQuery(query);
		if (offset != null) {
			typedQuery.setFirstResult(offset);
		}
		if (limit != null) {
			typedQuery.setMaxResults(limit);
		}
		return new ArrayList<>(typedQuery.getResultList().stream()
				.map(this::toLiteVideoEntity)
				.toList());
	}

	private long countLiteItems(VideoDataEntity res) {
		CriteriaBuilder cb = entityManager.getCriteriaBuilder();
		CriteriaQuery<Long> query = cb.createQuery(Long.class);
		Root<VideoDataEntity> root = query.from(VideoDataEntity.class);
		query.select(cb.count(root));
		query.where(buildLitePredicates(res, root, cb));
		return entityManager.createQuery(query).getSingleResult();
	}

	private Predicate[] buildLitePredicates(VideoDataEntity res, Root<VideoDataEntity> root, CriteriaBuilder cb) {
		List<Predicate> predicates = new ArrayList<>();
		if (res == null) {
			return predicates.toArray(new Predicate[0]);
		}
		if (StringUtil.isString(res.getVideoname()) && StringUtil.isString(res.getVideodesc())) {
			predicates.add(cb.or(
					cb.like(root.get("videoname"), "%" + res.getVideoname() + "%"),
					cb.like(root.get("videodesc"), "%" + res.getVideodesc() + "%")));
		} else if (StringUtil.isString(res.getVideoname())) {
			predicates.add(cb.like(root.get("videoname"), "%" + res.getVideoname() + "%"));
		} else if (StringUtil.isString(res.getVideodesc())) {
			predicates.add(cb.like(root.get("videodesc"), "%" + res.getVideodesc() + "%"));
		}
		if (StringUtil.isString(res.getVideoplatform())) {
			predicates.add(cb.like(root.get("videoplatform"), "%" + res.getVideoplatform() + "%"));
		}
		if (StringUtil.isString(res.getExcludePlatform())) {
			String[] excludePlatforms = res.getExcludePlatform().split(",");
			for (String platform : excludePlatforms) {
				String trimmedPlatform = platform != null ? platform.trim() : "";
				if (!trimmedPlatform.isEmpty()) {
					predicates.add(cb.notLike(cb.lower(root.get("videoplatform")), "%" + trimmedPlatform.toLowerCase() + "%"));
				}
			}
		}
		if (StringUtil.isString(res.getVideotag())) {
			predicates.add(cb.like(root.get("videotag"), "%" + res.getVideotag() + "%"));
		}
		if (StringUtil.isString(res.getVideoauthor())) {
			String[] authors = res.getVideoauthor().split(",");
			List<Predicate> authorPredicates = new ArrayList<>();
			for (String author : authors) {
				String trimmed = author == null ? "" : author.trim();
				if (!trimmed.isEmpty()) {
					authorPredicates.add(cb.like(root.get("videoauthor"), "%" + trimmed + "%"));
				}
			}
			if (!authorPredicates.isEmpty()) {
				predicates.add(cb.or(authorPredicates.toArray(new Predicate[0])));
			}
		}
		if (StringUtil.isString(res.getPublishStart())) {
			predicates.add(cb.greaterThanOrEqualTo(root.get("publishtime"), res.getPublishStart().trim() + " 00:00:00"));
		}
		if (StringUtil.isString(res.getPublishEnd())) {
			predicates.add(cb.lessThanOrEqualTo(root.get("publishtime"), res.getPublishEnd().trim() + " 23:59:59"));
		}
		if ("1".equals(res.getFavorite())) {
			predicates.add(cb.equal(root.get("favorite"), "1"));
		}
		return predicates.toArray(new Predicate[0]);
	}

	private List<Order> buildLiteOrders(VideoDataEntity res, Root<VideoDataEntity> root, CriteriaBuilder cb) {
		String sortField = resolveVideoSortField(res == null ? null : res.getSortField());
		String sortOrder = resolveSortOrder(res == null ? null : res.getSortOrder(), Global.videoListSortOrder);
		if ("id".equals(sortField)) {
			return "asc".equalsIgnoreCase(sortOrder) ? List.of(cb.asc(root.get("id"))) : List.of(cb.desc(root.get("id")));
		}
		if ("asc".equalsIgnoreCase(sortOrder)) {
			return List.of(cb.asc(root.get(sortField)), cb.desc(root.get("id")));
		}
		return List.of(cb.desc(root.get(sortField)), cb.desc(root.get("id")));
	}

	private VideoDataEntity toLiteVideoEntity(Tuple tuple) {
		VideoDataEntity video = new VideoDataEntity();
		video.setId(tuple.get("id", Integer.class));
		video.setVideoid(tuple.get("videoid", String.class));
		video.setVideoname(tuple.get("videoname", String.class));
		video.setVideodesc(tuple.get("videodesc", String.class));
		video.setVideoplatform(tuple.get("videoplatform", String.class));
		video.setVideocover(tuple.get("videocover", String.class));
		video.setVideounrealaddr(tuple.get("videounrealaddr", String.class));
		video.setVideoprivacy(tuple.get("videoprivacy", String.class));
		video.setVideotag(tuple.get("videotag", String.class));
		video.setVideoauthor(tuple.get("videoauthor", String.class));
		video.setAuthoruid(tuple.get("authoruid", String.class));
		video.setAuthorusername(tuple.get("authorusername", String.class));
		video.setAuthoravatar(tuple.get("authoravatar", String.class));
		video.setPublishtime(tuple.get("publishtime", String.class));
		video.setCreatetime(tuple.get("createtime", Date.class));
		video.setSourceurl(tuple.get("sourceurl", String.class));
		video.setFavorite(tuple.get("favorite", String.class));
		video.setOriginaladdress(tuple.get("originaladdress", String.class));
		video.setVideoaddr(tuple.get("videoaddr", String.class));
		return video;
	}

	public AjaxEntity findAll(VideoDataEntity res) {
		boolean randomMode = res != null && "1".equals(String.valueOf(res.getRandomMode()));
		String randomSeed = res == null ? null : res.getRandomSeed();
		List<VideoDataEntity> list = videoDataDao.findAll(buildFindSpecification(res, randomMode));
		if (randomMode) {
			stabilizeRandomSourceOrder(list);
			long seed = randomSeed == null ? System.nanoTime() : randomSeed.hashCode();
			java.util.Collections.shuffle(list, new java.util.Random(seed));
		}
		enrichVideoItems(list);
		return new AjaxEntity(Global.ajax_success, "查询成功", list);
	}

	private Specification<VideoDataEntity> buildFindSpecification(VideoDataEntity res, boolean randomMode) {
		return (root, query, cb) -> {
			List<Predicate> predicates = new ArrayList<>();

	        if (res != null) {
	            if (StringUtil.isString(res.getVideoname()) && StringUtil.isString(res.getVideodesc())) {
	                predicates.add(cb.or(
	                        cb.like(root.get("videoname"), "%" + res.getVideoname() + "%"),
	                        cb.like(root.get("videodesc"), "%" + res.getVideodesc() + "%")
	                ));
	            } else if (StringUtil.isString(res.getVideoname())) {
	                predicates.add(cb.like(root.get("videoname"), "%" + res.getVideoname() + "%"));
	            } else if (StringUtil.isString(res.getVideodesc())) {
	                predicates.add(cb.like(root.get("videodesc"), "%" + res.getVideodesc() + "%"));
	            }

	            if (StringUtil.isString(res.getVideoplatform())) {
	                predicates.add(cb.like(root.get("videoplatform"), "%" + res.getVideoplatform() + "%"));
	            }
	            
	            // 排除指定平台的视频（支持多个平台，逗号分隔）
	            if (StringUtil.isString(res.getExcludePlatform())) {
	                String[] excludePlatforms = res.getExcludePlatform().split(",");
	                for (String platform : excludePlatforms) {
	                    String trimmedPlatform = platform != null ? platform.trim() : "";
	                    if (!trimmedPlatform.isEmpty()) {
	                        predicates.add(cb.notLike(cb.lower(root.get("videoplatform")), "%" + trimmedPlatform.toLowerCase() + "%"));
	                    }
	                }
	            }
	            
	            if (StringUtil.isString(res.getVideotag())) {
	                predicates.add(cb.like(root.get("videotag"), "%" + res.getVideotag() + "%"));
	            }
	            if (StringUtil.isString(res.getVideoauthor())) {
	            	String[] authors = res.getVideoauthor().split(",");
	            	List<Predicate> authorPredicates = new ArrayList<>();
	            	for (String author : authors) {
	            		String trimmed = author == null ? "" : author.trim();
	            		if (!trimmed.isEmpty()) {
	            			authorPredicates.add(cb.like(root.get("videoauthor"), "%" + trimmed + "%"));
	            		}
	            	}
	            	if (!authorPredicates.isEmpty()) {
	            		predicates.add(cb.or(authorPredicates.toArray(new Predicate[0])));
	            	}
	            }
	            if (StringUtil.isString(res.getPublishStart())) {
	            	predicates.add(cb.greaterThanOrEqualTo(root.get("publishtime"), res.getPublishStart().trim() + " 00:00:00"));
	            }
	            if (StringUtil.isString(res.getPublishEnd())) {
	            	predicates.add(cb.lessThanOrEqualTo(root.get("publishtime"), res.getPublishEnd().trim() + " 23:59:59"));
	            }
	            if ("1".equals(res.getFavorite())) {
					predicates.add(cb.equal(root.get("favorite"), "1"));
	            }
	        }

			if (!randomMode) {
				String sortField = resolveVideoSortField(res == null ? null : res.getSortField());
				String sortOrder = resolveSortOrder(res == null ? null : res.getSortOrder(), Global.videoListSortOrder);
				if ("id".equals(sortField)) {
					if ("asc".equalsIgnoreCase(sortOrder)) {
						query.orderBy(cb.asc(root.get("id")));
					} else {
						query.orderBy(cb.desc(root.get("id")));
					}
				} else {
					if ("asc".equalsIgnoreCase(sortOrder)) {
						query.orderBy(cb.asc(root.get(sortField)), cb.desc(root.get("id")));
					} else {
						query.orderBy(cb.desc(root.get(sortField)), cb.desc(root.get("id")));
					}
				}
			}
			return cb.and(predicates.toArray(new Predicate[0]));
		};
	}

	private String resolveVideoSortField(String requestedField) {
		String candidate = StringUtil.isString(requestedField) ? requestedField : Global.videoListSortField;
		if ("createtime".equals(candidate) || "publishtime".equals(candidate) || "videoauthor".equals(candidate)) {
			return candidate;
		}
		return "id";
	}

	private String resolveSortOrder(String requestedOrder, String defaultOrder) {
		String candidate = StringUtil.isString(requestedOrder) ? requestedOrder : defaultOrder;
		return "asc".equalsIgnoreCase(candidate) ? "asc" : "desc";
	}

	private void stabilizeRandomSourceOrder(List<VideoDataEntity> items) {
		if (items == null) {
			return;
		}
		items.sort((left, right) -> {
			Integer leftId = left == null ? null : left.getId();
			Integer rightId = right == null ? null : right.getId();
			if (leftId == null && rightId == null) {
				return 0;
			}
			if (leftId == null) {
				return 1;
			}
			if (rightId == null) {
				return -1;
			}
			return rightId.compareTo(leftId);
		});
	}

	private void enrichVideoItems(List<VideoDataEntity> items) {
		if (items == null) {
			return;
		}
		java.util.Set<Integer> queuedIds = hlsTranscodeService.queuedIdsSnapshot();
		Integer runningId = hlsTranscodeService.runningVideoIdSnapshot();
		for (VideoDataEntity item : items) {
			if (item == null) {
				continue;
			}
			String playUrl = item.getVideounrealaddr();
			boolean hasHls = Global.hlsEnable && hlsTranscodeService.hasHls(item);
			if (hasHls) {
				String hls = hlsTranscodeService.buildHlsPlayUrl(item);
				if (hls != null && !hls.trim().isEmpty()) {
					playUrl = hls;
				}
			}
			item.setPlayurl(playUrl);
			if (!Global.hlsEnable) {
				item.setHlsstatus("关闭");
			} else if (hasHls) {
				item.setHlsstatus("已完成");
			} else if (item.getId() != null && runningId != null && item.getId().intValue() == runningId.intValue()) {
				item.setHlsstatus("转码中");
			} else if (item.getId() != null && queuedIds.contains(item.getId())) {
				item.setHlsstatus("排队中");
			} else {
				item.setHlsstatus("未完成");
			}
		}
	}

	/**
	 * 删除
	 * @param downloaderEntity
	 * @return
	 */
	public AjaxEntity deleteVideoData(VideoDataEntity data) {
		// 删除也要删除资源
		Optional<VideoDataEntity> findById = videoDataDao.findById(data.getId());
		if (findById.isPresent()) {
			VideoDataEntity videoDataEntity = findById.get();
			if (!"0".equals(data.getBlockwork())) {
				blockedWorkService.blockWork(videoDataEntity.getVideoplatform(), videoDataEntity.getVideoid(), "video",
						videoDataEntity.getVideoname(), videoDataEntity.getVideoauthor(), videoDataEntity.getAuthoruid(),
						videoDataEntity.getSourceurl() != null ? videoDataEntity.getSourceurl() : videoDataEntity.getOriginaladdress(), "manual-delete");
			}
			File file = new File(videoDataEntity.getVideoaddr());
			if(file.isFile()) {
				CommandUtil.deleteDirectory(file.getParentFile().getPath());
			}
			//这里保留 因为有可能用户可能时以前旧版的数据 如果不写这个就会导致无法删除
			if(file.isDirectory()) {
				CommandUtil.deleteDirectory(file.getPath());
			}
			videoDataDao.deleteById(data.getId());
			
		}
		return new AjaxEntity(Global.ajax_success, "操作成功", null);
	}

	/**
	 * 更新
	 * @param data
	 * @return
	 */
	public AjaxEntity updateVideoData(VideoDataEntity data) {
		Optional<VideoDataEntity> findById = videoDataDao.findById(data.getId());
		if (findById.isPresent()) {
			VideoDataEntity videoDataEntity = findById.get();
			videoDataEntity.setVideoprivacy(data.getVideoprivacy());
			videoDataEntity.setVideotag(data.getVideotag());
			videoDataDao.save(videoDataEntity);
		}
		return new AjaxEntity(Global.ajax_success, "操作成功", null);
	}

	public AjaxEntity updateVideoFavorite(Integer id, String favorite) {
		if (id == null) {
			return new AjaxEntity(Global.ajax_uri_error, "视频id不能为空", null);
		}
		Optional<VideoDataEntity> findById = videoDataDao.findById(id);
		if (findById.isPresent()) {
			VideoDataEntity videoDataEntity = findById.get();
			videoDataEntity.setFavorite("1".equals(favorite) ? "1" : "0");
			videoDataDao.save(videoDataEntity);
			return new AjaxEntity(Global.ajax_success, "操作成功", videoDataEntity);
		}
		return new AjaxEntity(Global.ajax_uri_error, "视频不存在", null);
	}

	public AjaxEntity redownloadVideoData(Integer id) {
		return douyinWorkMaintenanceService.redownloadVideo(id);
	}

	public ResponseEntity<StreamingResponseBody> playVideo(HttpHeaders headers, String video) throws IOException {
		if (video != null && !video.isEmpty()) {
			Optional<VideoDataEntity> findById = videoDataDao.findById(Integer.valueOf(video));
			if (findById.isPresent()) {
				VideoDataEntity videoDataEntity = findById.get();
				File videoFile = new File(videoDataEntity.getVideoaddr());
				long fileLength = videoFile.length();
				String mimeType = Files.probeContentType(videoFile.toPath());
				List<HttpRange> httpRanges = headers.getRange();

				long start = 0;
				long end = fileLength - 1;
				boolean isPartial = false;

				if (!httpRanges.isEmpty()) {
					// 只处理第一个 range
					HttpRange range = httpRanges.get(0);
					start = range.getRangeStart(fileLength);
					end = range.getRangeEnd(fileLength);
					isPartial = true;
				}

				long rangeLength = end - start + 1;
				long finalStart = start;
				long finalEnd = end;

				StreamingResponseBody responseBody = outputStream -> {
					try (RandomAccessFile raf = new RandomAccessFile(videoFile, "r")) {
						raf.seek(finalStart);
						try (InputStream inputStream = new BufferedInputStream(new FileInputStream(raf.getFD()))) {
							byte[] buffer = new byte[8192];
							long remaining = rangeLength;
							int bytesRead;
							while (remaining > 0) {
								bytesRead = inputStream.read(buffer, 0, (int) Math.min(buffer.length, remaining));
								if (bytesRead == -1) {
									break;
								}
								try {
									outputStream.write(buffer, 0, bytesRead);
								} catch (IOException e) {
									// 客户端断开连接，停止写入，记录日志
									logger.warn("客户端断开连接，停止视频流传输: {}", e.toString());
									break;
								}
								remaining -= bytesRead;
							}
							outputStream.flush(); // 确保数据发送完毕
						}
					} catch (IOException e) {
						// 其他IO异常，记录错误日志
						logger.error("视频流传输异常", e);
					}
				};

				HttpHeaders responseHeaders = new HttpHeaders();
				responseHeaders.set(HttpHeaders.CONTENT_TYPE, mimeType);
				responseHeaders.set(HttpHeaders.ACCEPT_RANGES, "bytes");

				if (isPartial) {
					responseHeaders.set(HttpHeaders.CONTENT_LENGTH, String.valueOf(rangeLength));
					responseHeaders.set(HttpHeaders.CONTENT_RANGE,
							String.format("bytes %d-%d/%d", finalStart, finalEnd, fileLength));
					return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
							.headers(responseHeaders)
							.body(responseBody);
				} else {
					responseHeaders.set(HttpHeaders.CONTENT_LENGTH, String.valueOf(fileLength));
					return ResponseEntity.ok()
							.headers(responseHeaders)
							.body(responseBody);
				}
			}
		}

		return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
	}

	public Map<String, Long> countByVideoplatformGroupBy() {
		List<Object[]> videoPlatformStats = videoDataDao.countByVideoplatformGroupBy();
		Map<String, Long> videoPlatformMap = new HashMap<>();
		for (Object[] stat : videoPlatformStats) {
			String platform = (String) stat[0];
			Long count = (Long) stat[1];
			videoPlatformMap.put(platform != null ? platform : "未知", count);
		}
		return videoPlatformMap;
	}

	/**
	 * 统计今日新增视频数量
	 * 
	 * @return 今日新增视频数量
	 */
	public Long countTodayAdded() {
		Calendar calendar = Calendar.getInstance();
		calendar.set(Calendar.HOUR_OF_DAY, 0);
		calendar.set(Calendar.MINUTE, 0);
		calendar.set(Calendar.SECOND, 0);
		calendar.set(Calendar.MILLISECOND, 0);
		Date startDate = calendar.getTime();
		calendar.add(Calendar.DAY_OF_MONTH, 1);
		Date endDate = calendar.getTime();
		return videoDataDao.countTodayAdded(startDate, endDate);
	}


	/**
	 * 刷新弹幕
	 * @param data
	 * @return
	 */
	public AjaxEntity refreshDanmu(VideoDataEntity data) {
	    Optional<VideoDataEntity> findById = videoDataDao.findById(data.getId());
	    if (!findById.isPresent()) {
	        return new AjaxEntity(Global.ajax_uri_error, "视频资源不存在,刷新失败", null);
	    }
	    VideoDataEntity videoDataEntity = findById.get();
	    if (!(videoDataEntity.getVideoplatform().equals(Global.platform.bilibili.name()) || videoDataEntity.getVideoplatform().equals("哔哩"))) {
	        return new AjaxEntity(Global.ajax_uri_error, "当前平台暂时不支持刷新弹幕,目前仅支持BiliBili", null);
	    }
	    String videoinfo = videoDataEntity.getVideoinfo();
	    if (videoinfo == null || videoinfo.isEmpty()) {
	        return new AjaxEntity(Global.ajax_uri_error, "当前视频未旧版数据,暂时不支持刷新弹幕", null);
	    }
	    String videoaddr = videoDataEntity.getVideoaddr();
	    JSONObject video = JSONObject.parseObject(videoinfo);
	    String filepathname = videoaddr.substring(0, videoaddr.lastIndexOf(".")) + ".ass";
	    BiliUtil.biliDanmaku("1", videoDataEntity.getVideoid(), video.getString("aid"), Integer.valueOf(video.getString("duration")), filepathname, videoDataEntity.getVideoname());
	    
	    return new AjaxEntity(Global.ajax_success, "刷新成功", null);
	}

	public VideoDataEntity findRandomByVideoplatform(String platform) {
		return videoDataDao.findRandomByVideoplatform(platform);
	}
	
	public VideoDataEntity findById(String videoid) {
		 Optional<VideoDataEntity> findById = videoDataDao.findById(Integer.valueOf(videoid));
		 if(findById.isPresent()) {
			 return findById.get();
		 }
		 return null;
	}
	
	public List<VideoDataEntity> findRecentlyAdded() {
		return videoDataDao.findRecentlyAdded();
	}

	public List<String> findDistinctAuthors() {
		return videoDataDao.findDistinctVideoauthors();
	}

}
