package com.flower.spirit.utils;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;

import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

import com.flower.spirit.database.DatabaseWriteContentionException;

public final class SqliteErrors {

	private SqliteErrors() {
	}

	public static boolean isBusy(Throwable error) {
		Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		for (Throwable current = error; current != null && visited.add(current); current = current.getCause()) {
			if (current instanceof DatabaseWriteContentionException) {
				return true;
			}
			if (current instanceof SQLiteException sqliteException
					&& isBusyCode(sqliteException.getResultCode())) {
				return true;
			}
			if (current instanceof SQLException sqlException
					&& isBusyCode(sqlException.getErrorCode())) {
				return true;
			}
			String message = current.getMessage();
			if (message != null) {
				String normalized = message.toUpperCase(Locale.ROOT);
				if (normalized.contains("SQLITE_BUSY")
						|| normalized.contains("DATABASE IS LOCKED")
						|| normalized.contains("DATABASE TABLE IS LOCKED")
						|| normalized.contains("ANOTHER DATABASE CONNECTION HAS ALREADY WRITTEN")) {
					return true;
				}
			}
		}
		return false;
	}

	private static boolean isBusyCode(SQLiteErrorCode code) {
		return code != null && isBusyCode(code.code);
	}

	private static boolean isBusyCode(int code) {
		return (code & 0xff) == SQLiteErrorCode.SQLITE_BUSY.code;
	}
}
