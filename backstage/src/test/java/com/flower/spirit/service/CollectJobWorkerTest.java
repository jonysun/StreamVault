package com.flower.spirit.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.flower.spirit.database.DatabaseWriteExecutor;
import com.flower.spirit.service.transaction.CollectQueueTransaction;

class CollectJobWorkerTest {

	@Test
	void workerPassesTriggerTypeAndCompletesImmediatelyAfterFetchPlanning() {
		CollectQueueTransaction transaction = mock(CollectQueueTransaction.class);
		CollectRunService runService = mock(CollectRunService.class);
		CollectDataService dataService = mock(CollectDataService.class);
		DatabaseWriteExecutor writes = mock(DatabaseWriteExecutor.class);
		CollectJobClaim claim = new CollectJobClaim(11L, 90L, 7, CollectTriggerType.AUDIT, 1, 3);
		when(writes.execute(anyString(), org.mockito.ArgumentMatchers.<Supplier<Object>>any()))
				.thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get());
		when(transaction.claimNext(anyString(), any())).thenReturn(claim);
		when(dataService.isCollectTaskEnabled(7)).thenReturn(true);
		CollectJobWorker worker = new CollectJobWorker(transaction, runService, dataService, writes);

		try {
			worker.processOne();
			InOrder order = inOrder(runService, dataService);
			order.verify(runService).start(90L);
			order.verify(dataService).executeQueuedCollectTask(7, 90L, CollectTriggerType.AUDIT);
			order.verify(runService).complete(90L, 11L);
		} finally {
			worker.shutdown();
		}
	}
}
