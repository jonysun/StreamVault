package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.flower.spirit.database.DatabaseWriteExecutor;
import com.flower.spirit.entity.ProcessHistoryEntity;
import com.flower.spirit.platform.PlatformResolver;
import com.flower.spirit.service.transaction.DirectDownloadQueueTransaction;

class DirectDownloadQueueServiceTest {

	private final DirectDownloadQueueTransaction transaction = mock(DirectDownloadQueueTransaction.class);
	private final ProcessHistoryService history = mock(ProcessHistoryService.class);
	private DirectDownloadQueueService service;

	@BeforeEach
	void setUp() {
		DatabaseWriteExecutor writes = mock(DatabaseWriteExecutor.class);
		when(writes.execute(anyString(), org.mockito.ArgumentMatchers.<Supplier<Object>>any()))
				.thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get());
		when(history.beginPlatformProcess(anyString(), anyString(), eq("QUEUED")))
				.thenReturn(new ProcessHistoryEntity(42, null, null, "QUEUED"));
		service = new DirectDownloadQueueService(transaction, writes, history, new PlatformResolver(), 3);
	}

	@ParameterizedTest
	@ValueSource(strings = {"https://v.douyin.com/example/", "https://youtu.be/work-1"})
	void failedEnqueueMarksHistoryFailedAndPropagatesTheError(String url) {
		IllegalStateException failure = new IllegalStateException("Queue insert rolled back");
		when(transaction.enqueue(anyString(), anyString(), anyString(), any(), any(), any(), any(),
				any(), any(), anyInt())).thenThrow(failure);

		assertThatThrownBy(() -> service.enqueue(url, null, null, null, null)).isSameAs(failure);

		verify(history).failPlatformProcess(42, "ENQUEUE_FAILED", "Queue insert rolled back");
		verify(history, never()).completeProcess(any(), any());
	}

	@Test
	void failedHistoryUpdateDoesNotHideTheEnqueueError() {
		IllegalStateException failure = new IllegalStateException("Queue insert rolled back");
		IllegalStateException historyFailure = new IllegalStateException("History unavailable");
		when(transaction.enqueue(anyString(), anyString(), anyString(), any(), any(), any(), any(),
				any(), any(), anyInt())).thenThrow(failure);
		doThrow(historyFailure).when(history).failPlatformProcess(any(), anyString(), anyString());

		assertThatThrownBy(() -> service.enqueue("https://youtu.be/work-1", null, null, null, null))
				.isSameAs(failure).hasSuppressedException(historyFailure);
	}

	@Test
	void duplicateSubmissionKeepsTheExistingHistoryAndCompletesTheNewOne() {
		DirectDownloadEnqueueResult existing = new DirectDownloadEnqueueResult(10, 7, false,
				DirectDownloadSource.SINGLE_LINK);
		when(transaction.enqueue(anyString(), anyString(), anyString(), any(), any(), any(), any(),
				any(), any(), anyInt())).thenReturn(existing);

		assertThat(service.enqueue("https://youtu.be/work-1", null, null, null, null)).isEqualTo(existing);

		verify(history).completeProcess(eq(42), anyString());
		verify(history, never()).failPlatformProcess(any(), any(), any());
	}
}
