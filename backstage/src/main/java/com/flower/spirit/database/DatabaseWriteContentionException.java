package com.flower.spirit.database;

import org.springframework.dao.TransientDataAccessResourceException;

public class DatabaseWriteContentionException extends TransientDataAccessResourceException {

	private static final long serialVersionUID = 1L;

	public DatabaseWriteContentionException(String message) {
		super(message);
	}

	public DatabaseWriteContentionException(String message, Throwable cause) {
		super(message, cause);
	}
}
