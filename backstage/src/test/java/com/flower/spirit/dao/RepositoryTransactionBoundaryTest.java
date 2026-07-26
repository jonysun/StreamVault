package com.flower.spirit.dao;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

class RepositoryTransactionBoundaryTest {

	private static final List<Class<?>> REPOSITORIES = List.of(
			AuthorNameHistoryDao.class,
			AuthorProfileDao.class,
			BiliConfigDao.class,
			BlockedWorkDao.class,
			CollectdDataDao.class,
			CollectdDataDetailDao.class,
			ConfigDao.class,
			CookiesConfigDao.class,
			DownloaderDao.class,
			FfmpegQueueDao.class,
			FfmpegQueueDataDao.class,
			GraphicContentDao.class,
			NotifyConfigDao.class,
			ProcessHistoryDao.class,
			TikTokConfigDao.class,
			UserDao.class,
			VideoDataDao.class,
			VideoMixDao.class,
			VideoMixSegmentDao.class);

	@Test
	void repositoriesDoNotWrapReadsInTypeLevelTransactions() {
		for (Class<?> repository : REPOSITORIES) {
			assertThat(repository.getDeclaredAnnotation(Transactional.class))
					.as("Spring transaction on %s", repository.getSimpleName())
					.isNull();
			assertThat(repository.getDeclaredAnnotation(jakarta.transaction.Transactional.class))
					.as("Jakarta transaction on %s", repository.getSimpleName())
					.isNull();
		}
	}

	@Test
	void customWriteQueriesDeclareSpringTransactions() throws Exception {
		assertSpringTransaction(AuthorNameHistoryDao.class, "deleteByAuthorprofileid", Integer.class);
		assertSpringTransaction(CollectdDataDetailDao.class, "deleteByDataid", Integer.class);
		assertSpringTransaction(VideoMixSegmentDao.class, "deleteByVideomixid", Integer.class);
		assertSpringTransaction(GraphicContentDao.class, "updateDouyinAuthorMetadata",
				String.class, String.class, String.class, String.class, String.class, List.class);
		assertSpringTransaction(VideoDataDao.class, "updateDouyinAuthorMetadata",
				String.class, String.class, String.class, String.class, String.class, List.class);
	}

	private void assertSpringTransaction(Class<?> repository, String methodName, Class<?>... parameterTypes)
			throws Exception {
		Method method = repository.getDeclaredMethod(methodName, parameterTypes);
		assertThat(method.getDeclaredAnnotation(Transactional.class))
				.as("Spring transaction on %s.%s", repository.getSimpleName(), methodName)
				.isNotNull();
	}
}
