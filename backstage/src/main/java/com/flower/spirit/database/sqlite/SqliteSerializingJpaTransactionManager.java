package com.flower.spirit.database.sqlite;

import java.util.ArrayDeque;
import java.util.Deque;

import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;

import jakarta.persistence.EntityManagerFactory;

public class SqliteSerializingJpaTransactionManager extends JpaTransactionManager {

	private static final long serialVersionUID = 1L;

	private final SqliteWriteCoordinator coordinator;
	private final ThreadLocal<Deque<TransactionPermit>> permits = ThreadLocal.withInitial(ArrayDeque::new);

	public SqliteSerializingJpaTransactionManager(EntityManagerFactory entityManagerFactory,
			SqliteWriteCoordinator coordinator) {
		super(entityManagerFactory);
		this.coordinator = coordinator;
	}

	@Override
	protected void doBegin(Object transaction, TransactionDefinition definition) {
		SqliteWriteCoordinator.Permit permit = definition.isReadOnly()
				? null : coordinator.acquire(definition.getName());
		try {
			super.doBegin(transaction, definition);
			permits.get().push(new TransactionPermit(permit));
		} catch (RuntimeException | Error error) {
			if (permit != null) {
				permit.close();
			}
			throw error;
		}
	}

	@Override
	protected void doCleanupAfterCompletion(Object transaction) {
		Deque<TransactionPermit> stack = permits.get();
		TransactionPermit transactionPermit = stack.isEmpty() ? TransactionPermit.READ_ONLY : stack.pop();
		try {
			super.doCleanupAfterCompletion(transaction);
		} finally {
			transactionPermit.close();
			if (stack.isEmpty()) {
				permits.remove();
			}
		}
	}

	private record TransactionPermit(SqliteWriteCoordinator.Permit permit) {

		private static final TransactionPermit READ_ONLY = new TransactionPermit(null);

		private void close() {
			if (permit != null) {
				permit.close();
			}
		}
	}
}
