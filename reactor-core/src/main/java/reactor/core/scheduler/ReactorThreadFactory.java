/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.core.scheduler;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import org.jetbrains.annotations.NotNull;
import reactor.util.annotation.Nullable;

/**
 * The standard Reactor {@link ThreadFactory Thread factories} to be used by {@link Scheduler},
 * creating {@link Thread} with a prefix (which can be retrieved with the {@link #get()} method).
 *
 * @author Simon Baslé
 *
 * Reactor线程工厂
 */
class ReactorThreadFactory implements ThreadFactory,
                                      Supplier<String>,
                                      Thread.UncaughtExceptionHandler {
	//线程工厂名，表示那个线程工厂生产的线程
	final private String                        name;
	//线程计数器
	final private AtomicLong                    counterReference;
	//是否守护线程
	final private boolean                       daemon;
	//拒绝策略
	final private boolean                       rejectBlocking;

	@Nullable
	final private BiConsumer<Thread, Throwable> uncaughtExceptionHandler;

	ReactorThreadFactory(String name,
			AtomicLong counterReference,
			boolean daemon,
			boolean rejectBlocking,
			@Nullable BiConsumer<Thread, Throwable> uncaughtExceptionHandler) {
		this.name = name;
		this.counterReference = counterReference;
		this.daemon = daemon;
		this.rejectBlocking = rejectBlocking;
		this.uncaughtExceptionHandler = uncaughtExceptionHandler;
	}

	@Override
	public final Thread newThread(@NotNull Runnable runnable) {
		//线程名：线程工厂name-num
		String newThreadName = name + "-" + counterReference.incrementAndGet();
		Thread t = rejectBlocking
				? new NonBlockingThread(runnable, newThreadName)
				: new Thread(runnable, newThreadName);
		//设置守护线程
		if (daemon) {
			t.setDaemon(true);
		}
		//设置异常处理器
		if (uncaughtExceptionHandler != null) {
			t.setUncaughtExceptionHandler(this);
		}
		return t;
	}

	/**
	 * 未捕获异常
	 * @param t
	 * @param e
	 */
	@Override
	public void uncaughtException(Thread t, Throwable e) {
		if (uncaughtExceptionHandler == null) {
			return;
		}

		uncaughtExceptionHandler.accept(t,e);
	}

	/**
	 * Get the prefix used for new {@link Thread Threads} created by this {@link ThreadFactory}.
	 * The factory can also be seen as a {@link Supplier Supplier&lt;String&gt;}.
	 *
	 * @return the thread name prefix
	 */
	@Override
	public final String get() {
		return name;
	}

	static final class NonBlockingThread extends Thread implements NonBlocking {

		public NonBlockingThread(Runnable target, String name) {
			super(target, name);
		}
	}
}
