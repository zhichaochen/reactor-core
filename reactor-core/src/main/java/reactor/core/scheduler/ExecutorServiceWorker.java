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

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.Scannable;

/**
 * @author Stephane Maldini
 *
 * 管理线程池中的多个线程。
 *
 * 相比XXXSchedule来说：重点在于对线程池中的每个线程的管理，比如tasks.dispose。
 * XXXSchedule：重点在线程池的管理。
 */
final class ExecutorServiceWorker implements Scheduler.Worker, Disposable, Scannable {

	//线程池：
	final ScheduledExecutorService exec;

	//里面存放着多个线程。比如：ListCompositeDisposable
	final Composite tasks;


	ExecutorServiceWorker(ScheduledExecutorService exec) {
		this.exec = exec;
		this.tasks = Disposables.composite();
	}

	@Override
	public Disposable schedule(Runnable task) {
		return Schedulers.workerSchedule(exec, tasks, task, 0L, TimeUnit.MILLISECONDS);
	}

	/**
	 * 创建并开启线程。
	 *
	 * @param task the task to schedule
	 * @param delay the delay amount, non-positive values indicate non-delayed scheduling
	 * @param unit the unit of measure of the delay amount
	 * @return
	 */
	@Override
	public Disposable schedule(Runnable task, long delay, TimeUnit unit) {
		return Schedulers.workerSchedule(exec, tasks, task, delay, unit);
	}

	/**
	 * 开启周期性线程
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
		return Schedulers.workerSchedulePeriodically(exec,
				tasks,
				task,
				initialDelay,
				period,
				unit);
	}

	@Override
	public void dispose() {
		tasks.dispose();
	}

	@Override
	public boolean isDisposed() {
		return tasks.isDisposed();
	}

	@Override
	public Object scanUnsafe(Attr key) {
		if (key == Attr.BUFFERED) return tasks.size();
		if (key == Attr.TERMINATED || key == Attr.CANCELLED) return isDisposed();
		if (key == Attr.NAME) return "ExecutorServiceWorker"; //could be parallel, single or fromExecutorService

		return Schedulers.scanExecutor(exec, key);
	}
}
