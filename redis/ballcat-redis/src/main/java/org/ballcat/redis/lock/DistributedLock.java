/*
 * Copyright 2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.ballcat.redis.lock;

import org.ballcat.redis.lock.function.ExceptionHandler;
import org.ballcat.redis.lock.function.ThrowingExecutor;
import org.springframework.util.Assert;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * 分布式锁操作类
 *
 * @author huyuanzhi 2021/11/16
 */
public final class DistributedLock<T> implements Action<T>, StateHandler<T> {

	T result;

	String key;

	Long timeout;

	TimeUnit timeUnit;

	int retryCount;

	ThrowingExecutor<T> executeAction;

	UnaryOperator<T> successAction;

	Supplier<T> lockFailAction;

	ExceptionHandler exceptionHandler = DistributedLock::throwException;

	public static <T> Action<T> instance() {
		return new DistributedLock<>();
	}

	@Override
	public StateHandler<T> action(String lockKey, long timeout, TimeUnit timeUnit, ThrowingExecutor<T> action) {
		Assert.isTrue(this.executeAction == null, "execute action has been already set");
		Assert.notNull(action, "execute action cant be null");
		Assert.hasText(lockKey, "lock key cant be blank");
		this.executeAction = action;
		this.key = lockKey;
		this.timeout = timeout;
		this.timeUnit = timeUnit;
		return this;
	}

	@Override
	public StateHandler<T> onSuccess(UnaryOperator<T> action) {
		Assert.isTrue(this.successAction == null, "success action has been already set");
		Assert.notNull(action, "success action cant be null");
		this.successAction = action;
		return this;
	}

	@Override
	public StateHandler<T> onLockFail(Supplier<T> action) {
		Assert.isTrue(this.lockFailAction == null, "lock fail action has been already set");
		Assert.notNull(action, "lock fail action cant be null");
		this.lockFailAction = action;
		return this;
	}

	@Override
	public StateHandler<T> onException(ExceptionHandler exceptionHandler) {
		Assert.notNull(exceptionHandler, "exception handler cant be null");
		this.exceptionHandler = exceptionHandler;
		return this;
	}

	@Override
	public StateHandler<T> retryCount(int retryCount) {
		this.retryCount = retryCount;
		return this;
	}

	@Override
	public T lock() {
		String requestId = UUID.randomUUID().toString();
		boolean exResolved = false;
		if (Boolean.TRUE.equals(CacheLock.lock(this.key, requestId, this.timeout, this.timeUnit))) {
			T value = null;
			try {
				value = executeAction.execute();
				this.result = value;
			}
			catch (Throwable e) {
				this.exceptionHandler.handle(e);
				exResolved = true;
			}
			finally {
				CacheLock.releaseLock(this.key, requestId);
			}
			if (!exResolved && this.successAction != null) {
				this.result = this.successAction.apply(value);
			}
		}
		if (lockFailAction != null) {
			this.result = lockFailAction.get();
		}
		int fib = 3;
		while (this.result == null && lockFailAction != null && !exResolved && retryCount-- != 0) {
			// 使用斐波那契数列进行睡眠时间的增长
			try {
				Thread.sleep(calculateFibonacci(fib++) * 10);
			} catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
			this.result = lockFailAction.get();
		}
		return this.result;
	}

	@SuppressWarnings("unchecked")
	private static <E extends Throwable> void throwException(Throwable t) throws E {
		throw (E) t;
	}

	/**
	 * 计算斐波那契值
	 * @param fib
	 * @return
	 */
	public int calculateFibonacci(int fib) {
		if (fib <= 0 || fib == 1 || fib == 2) {
			return 1;
		}
		return calculateFibonacci(fib - 1) + calculateFibonacci(fib - 2);
	}

}
