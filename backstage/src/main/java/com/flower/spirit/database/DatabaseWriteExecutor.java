package com.flower.spirit.database;

import java.util.function.Supplier;

public interface DatabaseWriteExecutor {

	<T> T execute(String operation, Supplier<T> action);

	default <T> T execute(Supplier<T> action) {
		return execute("database-write", action);
	}
}
