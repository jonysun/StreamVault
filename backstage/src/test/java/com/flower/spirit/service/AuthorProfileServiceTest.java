package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.annotation.Transactional;

import com.alibaba.fastjson.JSONObject;
import com.flower.spirit.dao.AuthorNameHistoryDao;
import com.flower.spirit.dao.AuthorProfileDao;
import com.flower.spirit.dao.GraphicContentDao;
import com.flower.spirit.dao.VideoDataDao;
import com.flower.spirit.dto.AdminAuthorProfileSummary;
import com.flower.spirit.entity.AuthorNameHistoryEntity;
import com.flower.spirit.entity.AuthorProfileEntity;
import com.flower.spirit.entity.VideoDataEntity;
import com.flower.spirit.service.transaction.AuthorWriteTransaction;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

@ExtendWith(MockitoExtension.class)
class AuthorProfileServiceTest {

	@Mock
	private AuthorProfileDao authorProfileDao;

	@Mock
	private AuthorNameHistoryDao authorNameHistoryDao;

	@Mock
	private VideoDataDao videoDataDao;

	@Mock
	private GraphicContentDao graphicContentDao;

	@Mock
	private SqliteWriteRetrier sqliteWriteRetrier;

	@Mock
	private AuthorWriteTransaction authorWriteTransaction;

	@Mock
	private AuthorEnrichmentQueueService authorEnrichmentQueueService;

	@InjectMocks
	private AuthorProfileService service;

	@BeforeEach
	void executeAuthorWrites() {
		org.mockito.Mockito.lenient().when(sqliteWriteRetrier.execute(any())).thenAnswer(invocation ->
				((Supplier<?>) invocation.getArgument(0)).get());
		org.mockito.Mockito.lenient().when(authorWriteTransaction.execute(any())).thenAnswer(invocation ->
				((Supplier<?>) invocation.getArgument(0)).get());
	}

	@Test
	void everyPublicAuthorUpsertEntryPointIsSynchronizedAndUsesExternalTransactionBoundary() {
		assertThat(List.of(AuthorProfileService.class.getDeclaredMethods()).stream()
				.filter(method -> method.getName().equals("upsertAuthor")
						|| method.getName().equals("upsertCanonicalAuthor")))
				.isNotEmpty()
				.allSatisfy(method -> {
					assertThat(method.isAnnotationPresent(Transactional.class)).isFalse();
					assertThat(Modifier.isSynchronized(method.getModifiers())).isTrue();
				});
		assertThat(AuthorWriteTransaction.class.getDeclaredMethods()).anySatisfy(method -> {
			assertThat(method.getName()).isEqualTo("execute");
			Transactional transactional = method.getAnnotation(Transactional.class);
			assertThat(transactional).isNotNull();
			assertThat(transactional.propagation())
					.isEqualTo(org.springframework.transaction.annotation.Propagation.REQUIRES_NEW);
		});
	}

	@Test
	void preferDouyinAuthorUidUsesSecUidWhenAvailable() {
		String result = AuthorProfileService.preferDouyinAuthorUid(" MS4wLjABAAAAstable ", "84583932458");

		assertThat(result).isEqualTo("MS4wLjABAAAAstable");
	}

	@Test
	void preferDouyinAuthorUidDoesNotPromoteNumericUidWhenSecUidMissing() {
		String result = AuthorProfileService.preferDouyinAuthorUid("", "84583932458");

		assertThat(result).isNull();
	}

	@Test
	void upsertAuthorIgnoresDouyinNumericUid() {
		service.upsertAuthor("抖音", "84583932458", "unique-id", "display", null, null);

		verify(authorProfileDao, never()).save(any(AuthorProfileEntity.class));
	}

	@Test
	void upsertAuthorUpdatesCurrentDisplayNameAndNameHistory() {
		AuthorProfileEntity profile = new AuthorProfileEntity();
		profile.setId(7);
		profile.setPlatform("douyin");
		profile.setAuthoruid("MS4wLjABAAAAstable");
		profile.setDisplayname("old name");
		when(authorProfileDao.findAllByPlatformkeyAndAuthoruidOrderByUpdatetimeDescIdDesc("douyin", "MS4wLjABAAAAstable"))
				.thenReturn(List.of(profile));
		when(authorProfileDao.save(any(AuthorProfileEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
		when(authorNameHistoryDao.findByAuthorprofileidAndDisplayname(eq(7), eq("new name")))
				.thenReturn(Optional.empty());

		service.upsertAuthor("douyin", "MS4wLjABAAAAstable", "handle", "new name", null, null, "profile bio");

		assertThat(profile.getDisplayname()).isEqualTo("new name");
		assertThat(profile.getUsername()).isEqualTo("handle");
		assertThat(profile.getSignature()).isEqualTo("profile bio");
		ArgumentCaptor<AuthorNameHistoryEntity> historyCaptor = ArgumentCaptor.forClass(AuthorNameHistoryEntity.class);
		verify(authorNameHistoryDao).save(historyCaptor.capture());
		assertThat(historyCaptor.getValue().getAuthorprofileid()).isEqualTo(7);
		assertThat(historyCaptor.getValue().getDisplayname()).isEqualTo("new name");
		assertThat(historyCaptor.getValue().getFirstseentime()).isNotNull();
		assertThat(historyCaptor.getValue().getLastseentime()).isNotNull();
		ArgumentCaptor<AuthorObservation> observationCaptor = ArgumentCaptor.forClass(AuthorObservation.class);
		verify(authorEnrichmentQueueService).enqueueAfterCommitIfIncomplete(observationCaptor.capture());
		assertThat(observationCaptor.getValue().platformKey()).isEqualTo("douyin");
		assertThat(observationCaptor.getValue().authorUid()).isEqualTo("MS4wLjABAAAAstable");
		assertThat(observationCaptor.getValue().signature()).isEqualTo("profile bio");
	}

	@Test
	void canonicalUpsertReusesLegacyProfileAndAddsPlatformKey() {
		AuthorProfileEntity profile = new AuthorProfileEntity();
		profile.setId(9);
		profile.setPlatform("YouTube");
		profile.setAuthoruid("channel-1");
		when(authorProfileDao.findAllByPlatformkeyAndAuthoruidOrderByUpdatetimeDescIdDesc("youtube", "channel-1"))
				.thenReturn(List.of());
		when(authorProfileDao.findAllByPlatformAndAuthoruidOrderByUpdatetimeDescIdDesc("youtube", "channel-1"))
				.thenReturn(List.of());
		when(authorProfileDao.findAllByPlatformAndAuthoruidOrderByUpdatetimeDescIdDesc("YouTube", "channel-1"))
				.thenReturn(List.of(profile));
		when(authorProfileDao.save(any(AuthorProfileEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
		when(authorNameHistoryDao.findByAuthorprofileidAndDisplayname(9, "Creator"))
				.thenReturn(Optional.empty());

		service.upsertCanonicalAuthor("youtube", "YouTube", "channel-1", "creator", "Creator",
				"https://cdn.example/avatar.jpg", "https://youtube.com/@creator", "Creator signature");

		assertThat(profile.getPlatformkey()).isEqualTo("youtube");
		assertThat(profile.getPlatform()).isEqualTo("YouTube");
		assertThat(profile.getDisplayname()).isEqualTo("Creator");
		assertThat(profile.getHomepage()).isEqualTo("https://youtube.com/@creator");
		assertThat(profile.getSignature()).isEqualTo("Creator signature");
		verify(authorProfileDao).save(profile);
	}

	@Test
	@SuppressWarnings({ "rawtypes", "unchecked" })
	void profileSummaryRejectsNumericDouyinUidAndHomepageButKeepsUsernameFallback() {
		when(videoDataDao.count(any(Specification.class))).thenReturn(0L);
		when(graphicContentDao.count(any(Specification.class))).thenReturn(0L);

		AdminAuthorProfileSummary summary = (AdminAuthorProfileSummary) service
				.findProfileSummary("抖音", "84583932458", "public_handle", "display")
				.getRecord();

		assertThat(summary.getAuthoruid()).isNull();
		assertThat(summary.getUsername()).isEqualTo("public_handle");
		assertThat(summary.getHomepage()).isNull();
		verify(authorProfileDao, never()).findByPlatformAndAuthoruid("抖音", "84583932458");
		verify(authorProfileDao, never()).findAll(any(Specification.class), any(Pageable.class));
	}

	@Test
	@SuppressWarnings({ "rawtypes", "unchecked" })
	void profileSummaryBuildsHomepageFromCanonicalDouyinUid() {
		AuthorProfileEntity profile = new AuthorProfileEntity();
		profile.setPlatform("抖音");
		profile.setAuthoruid("MS4wLjABAAAAstable");
		profile.setDisplayname("display");
		when(authorProfileDao.findAllByPlatformkeyAndAuthoruidOrderByUpdatetimeDescIdDesc("douyin", "MS4wLjABAAAAstable"))
				.thenReturn(List.of(profile));
		when(videoDataDao.count(any(Specification.class))).thenReturn(0L);
		when(graphicContentDao.count(any(Specification.class))).thenReturn(0L);

		AdminAuthorProfileSummary summary = (AdminAuthorProfileSummary) service
				.findProfileSummary("抖音", "MS4wLjABAAAAstable", null, "display")
				.getRecord();

		assertThat(summary.getAuthoruid()).isEqualTo("MS4wLjABAAAAstable");
		assertThat(summary.getHomepage()).isEqualTo("https://www.douyin.com/user/MS4wLjABAAAAstable");
	}

	@Test
	@SuppressWarnings({ "rawtypes", "unchecked" })
	void profileSummaryDoesNotUseRejectedNumericUidAsAnUnboundedProfileLookup() {
		when(videoDataDao.count(any(Specification.class))).thenReturn(0L);
		when(graphicContentDao.count(any(Specification.class))).thenReturn(0L);

		AdminAuthorProfileSummary summary = (AdminAuthorProfileSummary) service
				.findProfileSummary("douyin", "84583932458", null, null)
				.getRecord();

		assertThat(summary.getAuthoruid()).isNull();
		assertThat(summary.getTotalCount()).isZero();
		verify(authorProfileDao, never()).findAll(any(Specification.class), any(Pageable.class));
	}

	@Test
	void mergeLegacyProfileMovesMetadataAndHistoryBeforeDeletingNumericProfile() throws Exception {
		AuthorProfileEntity legacy = new AuthorProfileEntity();
		legacy.setId(11);
		legacy.setPlatform("douyin");
		legacy.setAuthoruid("84583932458");
		legacy.setDisplayname("old display");
		legacy.setSignature("old signature");
		legacy.setCreatetime(new Date(1_000L));
		legacy.setUpdatetime(new Date(2_000L));
		AuthorProfileEntity canonical = new AuthorProfileEntity();
		canonical.setId(12);
		canonical.setPlatform("douyin");
		canonical.setAuthoruid("MS4wLjABAAAAstable");
		canonical.setDisplayname("current display");
		AuthorNameHistoryEntity oldHistory = new AuthorNameHistoryEntity();
		oldHistory.setDisplayname("older display");
		oldHistory.setFirstseentime(new Date(500L));
		oldHistory.setLastseentime(new Date(800L));
		when(authorProfileDao.findAllByPlatformAndAuthoruidOrderByUpdatetimeDescIdDesc("douyin", "84583932458"))
				.thenReturn(List.of(legacy));
		when(authorProfileDao.findAllByPlatformkeyAndAuthoruidOrderByUpdatetimeDescIdDesc("douyin", "84583932458"))
				.thenReturn(List.of());
		when(authorProfileDao.findAllByPlatformkeyAndAuthoruidOrderByUpdatetimeDescIdDesc("douyin", "MS4wLjABAAAAstable"))
				.thenReturn(List.of(canonical));
		when(authorNameHistoryDao.findByAuthorProfileIdOrderByLastSeen(11)).thenReturn(List.of(oldHistory));
		when(authorNameHistoryDao.findByAuthorprofileidAndDisplayname(12, "old display"))
				.thenReturn(Optional.empty());
		when(authorNameHistoryDao.findByAuthorprofileidAndDisplayname(12, "older display"))
				.thenReturn(Optional.empty());

		boolean merged = invokeMergeLegacyProfile(service, "douyin", "84583932458", "MS4wLjABAAAAstable");

		assertThat(merged).isTrue();
		assertThat(canonical.getDisplayname()).isEqualTo("current display");
		assertThat(canonical.getSignature()).isEqualTo("old signature");
		assertThat(canonical.getHomepage()).isEqualTo("https://www.douyin.com/user/MS4wLjABAAAAstable");
		ArgumentCaptor<AuthorNameHistoryEntity> historyCaptor = ArgumentCaptor.forClass(AuthorNameHistoryEntity.class);
		verify(authorNameHistoryDao, org.mockito.Mockito.times(2)).save(historyCaptor.capture());
		assertThat(historyCaptor.getAllValues()).extracting(AuthorNameHistoryEntity::getDisplayname)
				.containsExactlyInAnyOrder("old display", "older display");
		verify(authorNameHistoryDao).deleteByAuthorprofileid(11);
		verify(authorProfileDao).delete(legacy);
	}

	@Test
	@SuppressWarnings({ "rawtypes", "unchecked" })
	void profileSummaryUsesCanonicalPlatformForVideoAndGraphicQueries() {
		String uid = "MS4wLjABAAAAstable";
		AuthorProfileEntity profile = new AuthorProfileEntity();
		profile.setPlatform("抖音");
		profile.setPlatformkey("douyin");
		profile.setAuthoruid(uid);
		when(authorProfileDao.findAllByPlatformkeyAndAuthoruidOrderByUpdatetimeDescIdDesc("douyin", uid))
				.thenReturn(List.of(profile));
		when(videoDataDao.count(any(Specification.class))).thenReturn(1050L);
		when(graphicContentDao.count(any(Specification.class))).thenReturn(8L);

		AdminAuthorProfileSummary summary = (AdminAuthorProfileSummary) service
				.findProfileSummary("douyin", "抖音", uid, null, null)
				.getRecord();

		assertThat(summary.getPlatformkey()).isEqualTo("douyin");
		assertThat(summary.getVideoCount()).isEqualTo(1050);
		assertThat(summary.getGraphicCount()).isEqualTo(8);
		assertThat(summary.getTotalCount()).isEqualTo(1058);

		ArgumentCaptor<Specification> videoSpec = ArgumentCaptor.forClass(Specification.class);
		ArgumentCaptor<Specification> graphicSpec = ArgumentCaptor.forClass(Specification.class);
		verify(videoDataDao).count(videoSpec.capture());
		verify(graphicContentDao).count(graphicSpec.capture());
		assertCanonicalDouyinPlatformPredicate(videoSpec.getValue(), "videoplatform", uid);
		assertCanonicalDouyinPlatformPredicate(graphicSpec.getValue(), "platform", uid);
	}

	@Test
	void reconcileVideoUsesStoredJsonWithoutUpstreamLookup() {
		VideoDataEntity video = new VideoDataEntity();
		video.setId(31);
		video.setVideoplatform("抖音");
		video.setJsonData("{\"aweme_detail\":{\"author\":{\"sec_uid\":\"MS4wLjABAAAAlocal\","
				+ "\"unique_id\":\"public-name\",\"nickname\":\"Current Name\","
				+ "\"avatar_thumb\":{\"url_list\":[\"https://img.example/avatar.jpg\"]}}}}");
		when(authorProfileDao.findAllByPlatformkeyAndAuthoruidOrderByUpdatetimeDescIdDesc("douyin", "MS4wLjABAAAAlocal"))
				.thenReturn(List.of());
		when(authorProfileDao.findAllByPlatformAndAuthoruidOrderByUpdatetimeDescIdDesc("抖音", "MS4wLjABAAAAlocal"))
				.thenReturn(List.of());
		when(authorProfileDao.findByAuthoruid("MS4wLjABAAAAlocal")).thenReturn(List.of());
		when(authorProfileDao.save(any(AuthorProfileEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

		AuthorProfileService.WorkAuthorReconcileResult result = service.reconcileDouyinVideo(video, new HashMap<>());

		assertThat(result.localResolved()).isTrue();
		assertThat(result.apiResolved()).isFalse();
		assertThat(video.getAuthoruid()).isEqualTo("MS4wLjABAAAAlocal");
		assertThat(video.getSecuid()).isEqualTo("MS4wLjABAAAAlocal");
		assertThat(video.getAuthorusername()).isEqualTo("public-name");
		assertThat(video.getVideoauthor()).isEqualTo("Current Name");
		verify(videoDataDao).save(video);
	}

	@Test
	void reconcileVideoSupportsProductionDataAuthorShape() {
		VideoDataEntity video = new VideoDataEntity();
		video.setId(32);
		video.setVideoplatform("抖音");
		video.setJsonData("{\"data\":{\"author\":{\"sec_uid\":\"MS4wLjABAAAAdata\","
				+ "\"unique_id\":\"data-user\",\"nickname\":\"Data Author\","
				+ "\"avatar_thumb\":\"https://img.example/data.jpg\"}}}");
		when(authorProfileDao.findAllByPlatformkeyAndAuthoruidOrderByUpdatetimeDescIdDesc("douyin", "MS4wLjABAAAAdata"))
				.thenReturn(List.of());
		when(authorProfileDao.findAllByPlatformAndAuthoruidOrderByUpdatetimeDescIdDesc("抖音", "MS4wLjABAAAAdata"))
				.thenReturn(List.of());
		when(authorProfileDao.findByAuthoruid("MS4wLjABAAAAdata")).thenReturn(List.of());
		when(authorProfileDao.save(any(AuthorProfileEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

		AuthorProfileService.WorkAuthorReconcileResult result = service.reconcileDouyinVideo(video, new HashMap<>());

		assertThat(result.localResolved()).isTrue();
		assertThat(video.getAuthoruid()).isEqualTo("MS4wLjABAAAAdata");
		assertThat(video.getAuthorusername()).isEqualTo("data-user");
	}

	@Test
	void extractProfileUserReturnsNullForNonUserPayload() throws Exception {
		JSONObject payload = JSONObject.parseObject("{\"status_code\":1,\"message\":\"error\"}");

		JSONObject user = invokeExtractProfileUser(service, payload);

		assertThat(user).isNull();
	}

	@Test
	void extractProfileUserAcceptsDataUserPayload() throws Exception {
		JSONObject payload = JSONObject.parseObject("{\"data\":{\"user\":{\"sec_uid\":\"sec\",\"unique_id\":\"name\"}}}");

		JSONObject user = invokeExtractProfileUser(service, payload);

		assertThat(user.getString("sec_uid")).isEqualTo("sec");
		assertThat(user.getString("unique_id")).isEqualTo("name");
	}

	@Test
	void applyExternalProfileUpdatesProfileAndCanonicalWorksInBulk() {
		AuthorProfileEntity profile = new AuthorProfileEntity();
		profile.setId(41);
		profile.setPlatform("抖音");
		profile.setPlatformkey("douyin");
		profile.setAuthoruid("MS4wLjABAAAArefresh");
		profile.setDisplayname("旧名称");
		profile.setUsername("old-user");
		when(authorProfileDao.findById(41)).thenReturn(Optional.of(profile));
		when(authorProfileDao.save(any(AuthorProfileEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
		when(authorNameHistoryDao.findByAuthorprofileidAndDisplayname(41, "新名称"))
				.thenReturn(Optional.empty());
		when(videoDataDao.updateDouyinAuthorMetadata(eq("MS4wLjABAAAArefresh"), eq("新名称"),
				eq("new-user"), eq("https://img.example/new.jpg"),
				eq("https://www.douyin.com/user/MS4wLjABAAAArefresh"), any(List.class))).thenReturn(12);
		when(graphicContentDao.updateDouyinAuthorMetadata(eq("MS4wLjABAAAArefresh"), eq("新名称"),
				eq("new-user"), eq("https://img.example/new.jpg"),
				eq("https://www.douyin.com/user/MS4wLjABAAAArefresh"), any(List.class))).thenReturn(3);
		JSONObject profileUser = JSONObject.parseObject("{\"sec_uid\":\"MS4wLjABAAAArefresh\","
				+ "\"unique_id\":\"new-user\",\"nickname\":\"新名称\","
				+ "\"signature\":\"新的签名\",\"avatar_thumb\":\"https://img.example/new.jpg\"}");

		AuthorProfileService.AuthorProfileRefreshResult result = service.applyExternalDouyinProfile(41, profileUser);

		assertThat(profile.getDisplayname()).isEqualTo("新名称");
		assertThat(profile.getUsername()).isEqualTo("new-user");
		assertThat(profile.getSignature()).isEqualTo("新的签名");
		assertThat(result.videosUpdated()).isEqualTo(12);
		assertThat(result.graphicsUpdated()).isEqualTo(3);
		assertThat(result.authorFieldsUpdated()).isEqualTo(5);
		verify(videoDataDao).updateDouyinAuthorMetadata(eq("MS4wLjABAAAArefresh"), eq("新名称"),
				eq("new-user"), eq("https://img.example/new.jpg"), any(), any(List.class));
		verify(graphicContentDao).updateDouyinAuthorMetadata(eq("MS4wLjABAAAArefresh"), eq("新名称"),
				eq("new-user"), eq("https://img.example/new.jpg"), any(), any(List.class));
	}

	@Test
	void applyExternalProfileRejectsMismatchedUidBeforeWriting() {
		AuthorProfileEntity profile = new AuthorProfileEntity();
		profile.setId(42);
		profile.setPlatform("抖音");
		profile.setAuthoruid("MS4wLjABAAAAexpected");
		when(authorProfileDao.findById(42)).thenReturn(Optional.of(profile));
		JSONObject profileUser = JSONObject.parseObject("{\"sec_uid\":\"MS4wLjABAAAAother\","
				+ "\"nickname\":\"其他作者\"}");

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.applyExternalDouyinProfile(42, profileUser))
				.hasMessageContaining("其他作者");

		verify(authorProfileDao, never()).save(any(AuthorProfileEntity.class));
		verify(videoDataDao, never()).updateDouyinAuthorMetadata(any(), any(), any(), any(), any(), any());
		verify(graphicContentDao, never()).updateDouyinAuthorMetadata(any(), any(), any(), any(), any(), any());
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private void assertCanonicalDouyinPlatformPredicate(Specification specification, String legacyField, String uid) {
		Root root = mock(Root.class);
		CriteriaQuery query = mock(CriteriaQuery.class);
		CriteriaBuilder cb = mock(CriteriaBuilder.class);
		Path<String> platformKey = mock(Path.class);
		Path<String> legacyPlatform = mock(Path.class);
		Path<String> authorUid = mock(Path.class);
		Path<String> secUid = mock(Path.class);
		Expression<String> lowerPlatformKey = mock(Expression.class);
		Expression<String> lowerLegacyPlatform = mock(Expression.class);
		Expression<String> trimmedPlatformKey = mock(Expression.class);
		Predicate canonicalPlatform = mock(Predicate.class);
		Predicate nullPlatformKey = mock(Predicate.class);
		Predicate emptyPlatformKey = mock(Predicate.class);
		Predicate blankPlatformKey = mock(Predicate.class);
		Predicate legacyAlias = mock(Predicate.class);
		Predicate legacyFallback = mock(Predicate.class);
		Predicate canonicalOrLegacy = mock(Predicate.class);

		when(root.get("platformkey")).thenReturn(platformKey);
		when(root.get(legacyField)).thenReturn(legacyPlatform);
		when(root.get("authoruid")).thenReturn(authorUid);
		when(root.get("secuid")).thenReturn(secUid);
		when(cb.lower(platformKey)).thenReturn(lowerPlatformKey);
		when(cb.lower(legacyPlatform)).thenReturn(lowerLegacyPlatform);
		when(cb.equal(lowerPlatformKey, "douyin")).thenReturn(canonicalPlatform);
		when(cb.isNull(platformKey)).thenReturn(nullPlatformKey);
		when(cb.trim(platformKey)).thenReturn(trimmedPlatformKey);
		when(cb.equal(trimmedPlatformKey, "")).thenReturn(emptyPlatformKey);
		when(cb.or(nullPlatformKey, emptyPlatformKey)).thenReturn(blankPlatformKey);
		when(lowerLegacyPlatform.in(any(Collection.class))).thenReturn(legacyAlias);
		when(cb.and(blankPlatformKey, legacyAlias)).thenReturn(legacyFallback);
		when(cb.or(canonicalPlatform, legacyFallback)).thenReturn(canonicalOrLegacy);

		specification.toPredicate(root, query, cb);

		ArgumentCaptor<Collection> aliases = ArgumentCaptor.forClass(Collection.class);
		verify(lowerLegacyPlatform).in(aliases.capture());
		assertThat(aliases.getValue()).contains("douyin", "抖音");
		verify(cb).or(canonicalPlatform, legacyFallback);
		verify(cb).equal(authorUid, uid);
		verify(cb).equal(secUid, uid);
	}

	private JSONObject invokeExtractProfileUser(AuthorProfileService service, JSONObject payload) throws Exception {
		Method method = AuthorProfileService.class.getDeclaredMethod("extractProfileUser", JSONObject.class);
		method.setAccessible(true);
		return (JSONObject) method.invoke(service, payload);
	}

	private boolean invokeMergeLegacyProfile(AuthorProfileService service, String platform, String legacyUid,
			String canonicalUid) throws Exception {
		Method method = AuthorProfileService.class.getDeclaredMethod("mergeLegacyProfile", String.class, String.class,
				String.class);
		method.setAccessible(true);
		return (Boolean) method.invoke(service, platform, legacyUid, canonicalUid);
	}
}
