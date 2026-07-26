package com.flower.spirit.database.postgresql;

import java.util.function.Supplier;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.flower.spirit.database.DatabaseWriteExecutor;

@Service
@ConditionalOnProperty(name = "streamvault.database.kind", havingValue = "postgresql")
public class DirectDatabaseWriteExecutor implements DatabaseWriteExecutor {

	@Override
	public <T> T execute(String operation, Supplier<T> action) {
		return action.get();
	}
}
