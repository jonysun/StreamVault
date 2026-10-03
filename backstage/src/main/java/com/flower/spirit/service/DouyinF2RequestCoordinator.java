package com.flower.spirit.service;

import java.util.concurrent.locks.ReentrantLock;

import org.springframework.stereotype.Component;

/** Serializes F2 HTTP metadata requests within one application container. */
@Component
public class DouyinF2RequestCoordinator {

	private final ReentrantLock lock = new ReentrantLock(true);

	public Permit acquire() throws InterruptedException {
		lock.lockInterruptibly();
		return new Permit();
	}

	public Permit noopPermit() {
		return new Permit(false);
	}

	public final class Permit implements AutoCloseable {
		private boolean closed;
		private final boolean releasesLock;

		private Permit() { this(true); }
		private Permit(boolean releasesLock) { this.releasesLock = releasesLock; }


		@Override
		public void close() {
			if (!closed) {
				closed = true;
				if (releasesLock) lock.unlock();
			}
		}
	}
}
