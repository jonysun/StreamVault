package com.flower.spirit.service.transaction;

import java.util.function.Supplier;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthorWriteTransaction {

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public <T> T execute(Supplier<T> authorWrite) {
		return authorWrite.get();
	}
}
