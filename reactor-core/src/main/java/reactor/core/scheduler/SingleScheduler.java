/*
 * Copyright (c) 2011-2017 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.core.scheduler;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Supplier;

import reactor.core.Disposable;
import reactor.core.Scannable;

/**
 * Scheduler that works with a single-threaded ScheduledExecutorService and is suited for
 * same-thread work (like an event dispatch thread). This scheduler is time-capable (can
 * schedule with delay / periodically).
 *
 * Scheduler：调度器。
 * 重点：对线程池的管理，创建丢弃等。
 */
final class SingleScheduler implements Scheduler, Supplier<ScheduledExecutorService>,
                                       Scannable {

	//计数器：
	static final AtomicLong COUNTER       = new AtomicLong();

	//自实现线程工厂
	final ThreadFactory factory;

	/**
	 * 周期执行线程池
	 *
	 * 为啥会更新线程池对象的操作呢？
	 * 		因为executor可能会被关闭，被关闭后，将executor对象，更新为TERMINATED对象。
	 */
	volatile ScheduledExecutorService executor;
	static final AtomicReferenceFieldUpdater<SingleScheduler, ScheduledExecutorService> EXECUTORS =
			AtomicReferenceFieldUpdater.newUpdater(SingleScheduler.class,
					ScheduledExecutorService.class,
					"executor");

	/**
	 * terminated：终止
	 *
	 * 在这里仅仅表示线程池关闭的一种状态。用于判断上面的executor是否被终止。
	 *
	 */
	static final ScheduledExecutorService TERMINATED;

	static {
		//程序启动的时候创建线程池并关闭，因为仅仅表示一个线程池关闭的状态
		TERMINATED = Executors.newSingleThreadScheduledExecutor();
		TERMINATED.shutdownNow();
	}

	/**
	 * 初始化线程池。
	 *
	 * @param factory
	 */
	SingleScheduler(ThreadFactory factory) {
		this.factory = factory;
		init();
	}

	/**
	 * Instantiates the default {@link ScheduledExecutorService} for the SingleScheduler
	 * ({@code Executors.newScheduledThreadPoolExecutor} with core and max pool size of 1).
	 *
	 * 实例化周期线程池。
	 */
	@Override
	public ScheduledExecutorService get() {
		ScheduledThreadPoolExecutor e = (ScheduledThreadPoolExecutor) Executors.newScheduledThreadPool(1, this.factory);
		e.setRemoveOnCancelPolicy(true);
		e.setMaximumPoolSize(1);
		return e;
	}

	/**
	 * 1、实例化线程池，如上get()方法
	 * 2、装饰一下线程池。
	 */
	private void init() {
		//这里仅仅装饰一下线程池。
		EXECUTORS.lazySet(this, Schedulers.decorateExecutorService(this, this.get()));
	}

	/**
	 * 判断是否被丢弃该线程池
	 *
	 * 只需判读executor == TERMINATED，因为线程池丢弃之后，会将executor对象替换为TERMINATED对象。
	 * @return
	 */
	@Override
	public boolean isDisposed() {
		return executor == TERMINATED;
	}

	/**
	 * 轮训更新线程池的状态。
	 *
	 * 1、线程池正常
	 * 		直接就退出轮训。
	 * 2、线程池被终止（TERMINATED状态）：
	 * 		开启一个新的线程池，然后使用compareAndSet，将executor变成一个正常的可用的线程池。
	 */
	@Override
	public void start() {
		//TODO SingleTimedScheduler didn't implement start, check if any particular reason?
		//SingleTimedScheduler未实现启动，请检查是否有任何特殊原因？

		//线程池b：表示一个关闭的线程池
		ScheduledExecutorService b = null;
		for (; ; ) {
			//线程池
			ScheduledExecutorService a = executor;

			//线程池没有被终止，直接就退出轮训
			if (a != TERMINATED) {
				if (b != null) {
					b.shutdownNow();
				}
				return;
			}

			/**
			 * 线程池被终止，创建一个新的线程池
			 */
			if (b == null) {
				b = Schedulers.decorateExecutorService(this, this.get());
			}

			/**
			 * 用正常的线程池，替换掉executor属性为正常的线程池。
			 */
			if (EXECUTORS.compareAndSet(this, a, b)) {
				return;
			}
		}
	}

	/**
	 * 丢弃线程池
	 */
	@Override
	public void dispose() {
		ScheduledExecutorService a = executor;
		if (a != TERMINATED) {
			/**
			 * 1、将executor当前线程池，变成TERMINATED已关闭线程池
			 * 2、关闭executor当前线程池。
			 */
			a = EXECUTORS.getAndSet(this, TERMINATED);
			if (a != TERMINATED) {
				a.shutdownNow();
			}
		}
	}

	/**
	 * 创建线程并开启线程。
	 */
	@Override
	public Disposable schedule(Runnable task) {
		return Schedulers.directSchedule(executor, task, null, 0L, TimeUnit.MILLISECONDS);
	}

	@Override
	public Disposable schedule(Runnable task, long delay, TimeUnit unit) {
		return Schedulers.directSchedule(executor, task, null, delay, unit);
	}

	/**
	 * 创建并开启周期性的线程
	 *
	 * @param task the task to schedule
	 * @param initialDelay the initial delay amount, non-positive values indicate non-delayed scheduling
	 * @param period the period at which the task should be re-executed
	 * @param unit the unit of measure of the delay amount
	 * @return
	 */
	@Override
	public Disposable schedulePeriodically(Runnable task,
			long initialDelay,
			long period,
			TimeUnit unit) {
		return Schedulers.directSchedulePeriodically(executor,
				task,
				initialDelay,
				period,
				unit);
	}

	@Override
	public String toString() {
		StringBuilder ts = new StringBuilder(Schedulers.SINGLE)
				.append('(');
		if (factory instanceof ReactorThreadFactory) {
			ts.append('\"').append(((ReactorThreadFactory) factory).get()).append('\"');
		}
		return ts.append(')').toString();
	}

	/**
	 * 查看当前线程池的各个属性指标
	 */
	@Override
	public Object scanUnsafe(Attr key) {
		if (key == Attr.TERMINATED || key == Attr.CANCELLED) return isDisposed();
		if (key == Attr.NAME) return this.toString();
		if (key == Attr.CAPACITY || key == Attr.BUFFERED) return 1; //BUFFERED: number of workers doesn't vary

		return Schedulers.scanExecutor(executor, key);
	}

	/**
	 * ExecutorServiceWorker：用来管理线程池中的线程。
	 *
	 * @return
	 */
	@Override
	public Worker createWorker() {
		return new ExecutorServiceWorker(executor);
	}

}
