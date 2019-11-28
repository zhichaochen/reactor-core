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

import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import reactor.core.Disposable;
import reactor.util.annotation.Nullable;

/**
 * A runnable task for {@link Scheduler} Workers that are time-capable (implementing a
 * relevant schedule(delay) and schedulePeriodically(period) methods).
 *
 * Unlike the one in {@link DelegateServiceScheduler}, this runnable doesn't expose the
 * ability to cancel inner task when interrupted.
 *
 * @author Simon Baslé
 * @author David Karnok
 *
 * 真正执行任务的地方。类似于jdk中的FutureTask，这里算是对FutureTask的重写。
 *  用法如下：
 *      ExecutorService executorService=Executors.newCachedThreadPool();
 *      FutureTask<Object> futureTask = new FutureTask<>(Callable callable);
 *      executorService.submit(futureTask);
 * 创建了一个线程。
 * 创建线程的三种方试。实现Runnable 或者 Callable接口，继承Thread
 */
final class WorkerTask implements Runnable, Disposable, Callable<Void> {

	//比如FluxPublishOn
	final Runnable task;

	/**
	 * marker that the Worker was disposed and the parent got notified
	 * 标记Worker是可以被丢弃的，并通知他的parent
     *
     * 我的：记录线程集合是否被丢弃，调用Composite#dispose，应该会更新该状态。
     * 下面的几个状态也是这几个意思。
	 */
	static final Composite DISPOSED = new EmptyCompositeDisposable();
	/**
	 * marker that the Worker has completed, for the PARENT field
	 *
	 * 标记这个Worker的PARENT属性已经完成。
	 */
	static final Composite DONE = new EmptyCompositeDisposable();


	/**
	 * marker that the Worker has completed, for the FUTURE field
	 *
	 * 标记这个Worker的FUTURE属性已经完成。
	 */
	static final Future<Void> FINISHED        = new FutureTask<>(() -> null);
	/**
	 * marker that the Worker was cancelled from the same thread (ie. within call()/run()),
	 * which means setFuture might race: we avoid interrupting the Future in this case.
	 *
	 * 标记Worker已从同一线程（即在call（）/run（）内）删除
	 * 这意味着setFuture可能会竞争：在这种情况下，我们避免中断Future
	 */
	static final Future<Void> SYNC_CANCELLED  = new FutureTask<>(() -> null);
	/**
	 * marker that the Worker was cancelled from another thread, making it safe to
	 * interrupt the Future task.
	 *
	 * see https://github.com/reactor/reactor-core/issues/1107
	 *
	 * 标记 Worker已经从另一个线程中删除，确保安全中断Future的任务
	 */
	static final Future<Void> ASYNC_CANCELLED = new FutureTask<>(() -> null);

    /**
     * 当前任务future。
     *      FINISHED：任务完成、
     *      SYNC_CANCELLED：同步取消任务
     *      ASYNC_CANCELLED：异步取消任务。
     */
	volatile Future<?> future;
	static final AtomicReferenceFieldUpdater<WorkerTask, Future> FUTURE =
			AtomicReferenceFieldUpdater.newUpdater(WorkerTask.class, Future.class, "future");

	//记录上游算子的线程（上个线程）（上游可能开启了多个线程去处理）
	volatile Composite parent;
	static final AtomicReferenceFieldUpdater<WorkerTask, Composite> PARENT =
			AtomicReferenceFieldUpdater.newUpdater(WorkerTask.class, Composite.class, "parent");

	//记录当前算子的线程
	volatile Thread thread;
	static final AtomicReferenceFieldUpdater<WorkerTask, Thread> THREAD =
			AtomicReferenceFieldUpdater.newUpdater(WorkerTask.class, Thread.class, "thread");

    /**
     * 此时，[并没有开启线程]，在submit(Callable)之后，才会开启线程。
     *
     * 参见Schedulers#workerSchedule
     * 参考JDk中的：FutureTask用法
     *
     * @param task ：创建的线程
     * @param parent ：上游算子的线程
     */
	WorkerTask(Runnable task, Composite parent) {
		this.task = task;
        /**
         * 此时还在上游算子的线程中，故而在此时设置上游算子的线程。
         * 开启新的线程之前，记录上游算子的线程在PARENT中。
         */
		PARENT.lazySet(this, parent);
	}

    /**
     * 此时已经开启线程，submit(Callable),已经执行。
     * 新开启的线程，已经开始执行任务了。
     */
	@Override
	@Nullable
	public Void call() {
	    //开启新的线程之后，记录当前的线程在THREAD中。
		THREAD.lazySet(this, Thread.currentThread());
		try {
			try {
			    //执行任务。
				task.run();
			}
			catch (Throwable ex) {
				Schedulers.handleError(ex);
			}
		}
		finally {
            /**
             * 此时，只是调用任务的动作完成。而任务本身并没有结束。
             * 调用完成之后，当前线程设置为null
             */
			THREAD.lazySet(this, null);
			//上游算子的多个线程。
			Composite o = parent;
            /**
             * note: the o != null check must happen after the compareAndSet for it to always mark task as DONE
             * 注意：o != null 检查，必须在compareAndSet之后发生，以便它始终将任务标记为已完成
             *
             * 设置PARENT中的为完成状态。
             */
			if (o != DISPOSED && PARENT.compareAndSet(this, o, DONE) && o != null) {
			    //任务完成之后，移除该任务。
				o.remove(this);
			}

			Future f;
			/**
             *轮询等待结果。
             */
			for (;;) {
				f = future;
				/**
                 * 如果线程被取消，或者完成，那么跳出，表示调用结束。
                 * FINISHED 对象 等同于 f 对象的时候，返回true。
                 *
                 * 在下面方法setFuture中可以设置任务的执行状态。
                 */
				if (f == SYNC_CANCELLED || f == ASYNC_CANCELLED || FUTURE.compareAndSet(this, f, FINISHED)) {
					break;
				}
			}
		}
		return null;
	}

	@Override
	public void run() {
		call();
	}

    /**
     * 设置当前任务的执行状态，是被打断了呢？还是完成了。
     *
     * 为何SYNC_CANCELLED设置为true
     *      因为在同一个线程中，是不可能中断一个正在运行的线程的。
     *      只会设置它的中断状态为false。
     *
     *      一个线程进入了另一个线程中，说明当前线程被阻塞了。
     * @param f
     */
	void setFuture(Future<?> f) {
		for (;;) {
			Future o = future;
			if (o == FINISHED) {
				return;
			}
			if (o == SYNC_CANCELLED) {
				f.cancel(false);
				return;
			}
			if (o == ASYNC_CANCELLED) {
				f.cancel(true);
				return;
			}
			if (FUTURE.compareAndSet(this, o, f)) {
				return;
			}
		}
	}

	/**
	 * 判断某个线程是否被丢弃
	 */
	@Override
	public boolean isDisposed() {
		Composite o = PARENT.get(this);
		return o == DISPOSED || o == DONE;
	}

    /**
     * 丢弃当前任务
     * 1、丢弃掉当前任务
     * 2、丢弃掉上游算子的任务
     *
     * 为什么呢？
     *      publishOn和subscriberOn的加入，让各个算子的任务可能由不同的线程执行。
     *      不同的线程执行一个链式程序的不同的部分（算子）
     *
     *      链式程序是一个整体的执行流程，下游算子依赖上游算子。
     *      当下游算子的任务被丢弃，上游算子的任务理应被丢弃。
     */
	@Override
	public void dispose() {
        /**
         * 处理当前任务。
         */
		for (;;) {
			Future f = future;
			//如果任务已完成或已取消，则退出。
			if (f == FINISHED || f == SYNC_CANCELLED || f == ASYNC_CANCELLED) {
				break;
			}

			/**
             * 如果还没有完成，
             * 轮询判断状态是否被设置为取消。
             * 一旦被setFuture设置为取消，则取消该任务。
             *
             * 本质上来说：轮询监视任务状态。
             */
			boolean async = thread != Thread.currentThread();
			if (FUTURE.compareAndSet(this, f, async ? ASYNC_CANCELLED : SYNC_CANCELLED)) {
				if (f != null) {
					f.cancel(async);
				}
				break;
			}
		}
        /**
         * 终止上个算子的任务门。
         *
         * 一个链式程序，上游的计算的结果，本身是为了下游给下游用。
         * 下游的算子都停止执行了。那么上游的也无需执行了。
         */
		for (;;) {
			Composite o = parent;
			if (o == DONE || o == DISPOSED || o == null) {
				return;
			}
			if (PARENT.compareAndSet(this, o, DISPOSED)) {
				o.remove(this);
				return;
			}
		}
	}

}
