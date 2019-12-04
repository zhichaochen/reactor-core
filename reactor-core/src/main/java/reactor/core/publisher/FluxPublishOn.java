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

package reactor.core.publisher;

import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.function.Supplier;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.Exceptions;
import reactor.core.Fuseable;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Scheduler.Worker;
import reactor.util.annotation.Nullable;

/**
 * Emits events on a different thread specified by a scheduler callback.
 *
 * @param <T> the value type
 *
 * @see <a href="https://github.com/reactor/reactive-streams-commons">Reactive-Streams-Commons</a>
 *
 * 在调度程序回调指定的其他线程上发出事件。
 *
 * 我的理解，上游订阅者onNext发送元素存入Queue，下游的订阅者开启多线程来消费。
 */
final class FluxPublishOn<T> extends InternalFluxOperator<T, T> implements Fuseable {

	final Scheduler scheduler;

	final boolean delayError;

	final Supplier<? extends Queue<T>> queueSupplier;

	final int prefetch;

	final int lowTide;

	FluxPublishOn(Flux<? extends T> source,
			Scheduler scheduler,
			boolean delayError,
			int prefetch,
			int lowTide,
			Supplier<? extends Queue<T>> queueSupplier) {
		super(source);
		if (prefetch <= 0) {
			throw new IllegalArgumentException("prefetch > 0 required but it was " + prefetch);
		}
		this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
		this.delayError = delayError;
		this.prefetch = prefetch;
		this.lowTide = lowTide;
		this.queueSupplier = Objects.requireNonNull(queueSupplier, "queueSupplier");
	}

	@Override
	public Object scanUnsafe(Attr key) {
		if (key == Attr.RUN_ON) return scheduler;

		return super.scanUnsafe(key);
	}

	@Override
	public int getPrefetch() {
		return prefetch;
	}

	/**
	 * 下游算子订阅上游算子
	 *
	 * @param actual
	 * @return
	 */
	@Override
	@SuppressWarnings("unchecked")
	public CoreSubscriber<? super T> subscribeOrReturn(CoreSubscriber<? super T> actual) {
		Worker worker;

		try {
			//创建ExecutorServiceWorker
			worker = Objects.requireNonNull(scheduler.createWorker(),
					"The scheduler returned a null worker");
		}
		catch (Throwable e) {
			Operators.error(actual, Operators.onOperatorError(e, actual.currentContext()));
			return null;
		}

		if (actual instanceof ConditionalSubscriber) {
			ConditionalSubscriber<? super T> cs = (ConditionalSubscriber<? super T>) actual;
			source.subscribe(new PublishOnConditionalSubscriber<>(cs,
					scheduler,
					worker,
					delayError,
					prefetch,
					lowTide,
					queueSupplier));
			return null;
		}
		return new PublishOnSubscriber<>(actual,
				scheduler,
				worker,
				delayError,
				prefetch,
				lowTide,
				queueSupplier);
	}

	/**
	 * PublishOn对应的Subscriber
	 * @param <T>
	 */
	static final class PublishOnSubscriber<T>
			implements QueueSubscription<T>, Runnable, InnerOperator<T, T> {

		final CoreSubscriber<? super T> actual;

		final Scheduler scheduler;

		final Worker worker;

		final boolean delayError;

		final int prefetch;

		final int limit;
		//队列提供者
		final Supplier<? extends Queue<T>> queueSupplier;

		Subscription s;
		//队列
		Queue<T> queue;

		volatile boolean cancelled;

		volatile boolean done;

		Throwable error;

		/**
		 * wip(work in progress):
		 * 特别注意：WIP记录的是【正在工作的线程数量】
		 */
		volatile int wip;
		@SuppressWarnings("rawtypes")
		static final AtomicIntegerFieldUpdater<PublishOnSubscriber> WIP =
				AtomicIntegerFieldUpdater.newUpdater(PublishOnSubscriber.class, "wip");

		/**
		 * 表示请求的元素的个数
		 */
		volatile long requested;
		@SuppressWarnings("rawtypes")
		static final AtomicLongFieldUpdater<PublishOnSubscriber> REQUESTED =
				AtomicLongFieldUpdater.newUpdater(PublishOnSubscriber.class, "requested");

		//来源模式：同步还是异步。
		int sourceMode;

		/**
		 * 记录生产了多少元素，生产一个，记录一个。
		 * 调用onNext之后+1，表示元素生产完成
		 */
		long produced;

		boolean outputFused;

		PublishOnSubscriber(CoreSubscriber<? super T> actual,
				Scheduler scheduler,
				Worker worker,
				boolean delayError,
				int prefetch,
				int lowTide,
				Supplier<? extends Queue<T>> queueSupplier) {
			this.actual = actual;
			this.worker = worker;
			this.scheduler = scheduler;
			this.delayError = delayError;
			this.prefetch = prefetch;
			this.queueSupplier = queueSupplier;
			this.limit = Operators.unboundedOrLimit(prefetch, lowTide);
		}

		/**
		 * 订阅之后做些什么事情？
		 * @param s
		 */
		@Override
		public void onSubscribe(Subscription s) {
			if (Operators.validate(this.s, s)) {
				this.s = s;

				if (s instanceof QueueSubscription) {
					@SuppressWarnings("unchecked") QueueSubscription<T> f =
							(QueueSubscription<T>) s;

					//判断上下游生产者是否在同一个线程中。
					int m = f.requestFusion(Fuseable.ANY | Fuseable.THREAD_BARRIER);

					if (m == Fuseable.SYNC) {
						sourceMode = Fuseable.SYNC;
						queue = f;
						done = true;

						actual.onSubscribe(this);
						return;
					}
					if (m == Fuseable.ASYNC) {
						sourceMode = Fuseable.ASYNC;
						queue = f;

						actual.onSubscribe(this);

						s.request(Operators.unboundedOrPrefetch(prefetch));

						return;
					}
				}

				queue = queueSupplier.get();

				actual.onSubscribe(this);

				s.request(Operators.unboundedOrPrefetch(prefetch));
			}
		}

		/**
		 * 同步的话，
		 * @param t
		 */
		@Override
		public void onNext(T t) {
			//如果是异步，直接调用trySchedule
			if (sourceMode == ASYNC) {
				trySchedule(this, null, null /* t always null */);
				return;
			}

			//如果是完成状态，丢弃该元素。
			if (done) {
				Operators.onNextDropped(t, actual.currentContext());
				return;
			}

			/**
			 * 上游发送过来的元素放入Queue中。
			 * 如果返回false：处理异常信息。
			 */
			if (!queue.offer(t)) {
				error = Operators.onOperatorError(s,
						Exceptions.failWithOverflow(Exceptions.BACKPRESSURE_ERROR_QUEUE_FULL),
						t, actual.currentContext());
				done = true;
			}
			trySchedule(this, null, t);
		}

		@Override
		public void onError(Throwable t) {
			if (done) {
				Operators.onErrorDropped(t, actual.currentContext());
				return;
			}
			error = t;
			done = true;
			trySchedule(null, t, null);
		}

		@Override
		public void onComplete() {
			if (done) {
				return;
			}
			done = true;
			//WIP also guards, no competing onNext
			trySchedule(null, null, null);
		}

		/**
		 * request 方法是整个流程的触发者
		 * trySchedule ： 是工作窃取的核心。
		 * @param n
		 */
		@Override
		public void request(long n) {
			if (Operators.validate(n)) {
				Operators.addCap(REQUESTED, this, n);
				//WIP also guards during request and onError is possible
				trySchedule(this, null, null);
			}
		}

		/**
		 * 取消
		 */
		@Override
		public void cancel() {
			if (cancelled) {
				return;
			}
			//设置为取消
			cancelled = true;
			//关闭这次订阅事件
			s.cancel();
			//关闭多个线程
			worker.dispose();

			if (WIP.getAndIncrement(this) == 0) {
				Operators.onDiscardQueueWithClear(queue, actual.currentContext(), null);
			}
		}

		/**
		 * 创建并开启线程，线程会执行该类中的run方法。
		 *
		 * 由此可见，上游数据放入Queue队列
		 *
		 *
		 * @param subscription
		 * @param suppressed
		 * @param dataSignal
		 */
		void trySchedule(
				@Nullable Subscription subscription,
				@Nullable Throwable suppressed,
				@Nullable Object dataSignal) {

			/**
			 * WIP记录的活跃的线程数
			 * 能获取到，说明有正在工作的线程，无需创建线程。
			 */
			if (WIP.getAndIncrement(this) != 0) {
				return;
			}

			/**
			 * 没有正在工作的线程，创建新的线程。
			 */
			try {
				worker.schedule(this);
			}
			catch (RejectedExecutionException ree) {
				Operators.onDiscardQueueWithClear(queue, actual.currentContext(), null);
				actual.onError(Operators.onRejectedExecution(ree, subscription, suppressed, dataSignal,
						actual.currentContext()));
			}
		}

		/**
		 * 同步运行（表示同一个线程中。）
		 */
		void runSync() {
			//用于表示某个
			int missed = 1;

			final Subscriber<? super T> a = actual;
			final Queue<T> q = queue;

			long e = produced;

			for (; ; ) {

				long r = requested;
				/**
				 * e ：目前上游生产的元素的个数
				 * r ：下游这次请求的元素的个数
				 */
				while (e != r) {
					T v;

					try {
						//拿一个元素
						v = q.poll();
					}
					catch (Throwable ex) {
						doError(a, Operators.onOperatorError(s, ex,
								actual.currentContext()));
						return;
					}

					if (cancelled) {
						Operators.onDiscardQueueWithClear(q, actual.currentContext(), null);
						return;
					}

					//元素没有了，表示已经完成
					if (v == null) {
						doComplete(a);
						return;
					}
					//把该元素发送到下游去执行。
					a.onNext(v);

					e++;
				}

				if (cancelled) {
					Operators.onDiscardQueueWithClear(q, actual.currentContext(), null);
					return;
				}

				if (q.isEmpty()) {
					doComplete(a);
					return;
				}

				/**
				 * 能走到这里，说明queue不为空。
				 * 但是呢，这个线程结束了
				 */
				int w = wip;

				/**
				 * 说明一个线程正在工作,同步的话也就一个线程
				 * 将工作中的线程 -1 ，表示没有线程在工作。
				 * 因为对于同步来说，此时工作已经完成。
				 */
				if (missed == w) {
					produced = e;
					missed = WIP.addAndGet(this, -missed);
					if (missed == 0) {
						break;
					}
				}
				else {
					missed = w;
				}
			}
		}

		void runAsync() {
			int missed = 1;

			final Subscriber<? super T> a = actual;
			final Queue<T> q = queue;

			long e = produced;

			for (; ; ) {

				long r = requested;

				while (e != r) {
					boolean d = done;
					T v;

					try {
						v = q.poll();
					}
					catch (Throwable ex) {
						Exceptions.throwIfFatal(ex);
						s.cancel();
						Operators.onDiscardQueueWithClear(q, actual.currentContext(), null);

						doError(a, Operators.onOperatorError(ex, actual.currentContext()));
						return;
					}

					boolean empty = v == null;

					if (checkTerminated(d, empty, a)) {
						return;
					}

					if (empty) {
						break;
					}

					a.onNext(v);

					e++;

					if (e == limit) {
						if (r != Long.MAX_VALUE) {
							r = REQUESTED.addAndGet(this, -e);
						}
						s.request(e);
						e = 0L;
					}
				}

				if (e == r && checkTerminated(done, q.isEmpty(), a)) {
					return;
				}

				int w = wip;
				if (missed == w) {
					produced = e;
					missed = WIP.addAndGet(this, -missed);
					if (missed == 0) {
						break;
					}
				}
				else {
					missed = w;
				}
			}
		}

		/**
		 * 运行回流
		 */
		void runBackfused() {
			int missed = 1;

			for (; ; ) {

				if (cancelled) {
					Operators.onDiscardQueueWithClear(queue, actual.currentContext(), null);

					return;
				}

				boolean d = done;

				actual.onNext(null);

				if (d) {
					Throwable e = error;
					if (e != null) {
						doError(actual, e);
					}
					else {
						doComplete(actual);
					}
					return;
				}

				missed = WIP.addAndGet(this, -missed);
				if (missed == 0) {
					break;
				}
			}
		}

		void doComplete(Subscriber<?> a) {
			a.onComplete();
			worker.dispose();
		}

		void doError(Subscriber<?> a, Throwable e) {
			try {
				a.onError(e);
			}
			finally {
				worker.dispose();
			}
		}

		/**
		 * 线程任务
		 *
		 * 同步：
		 * 异步：
		 * 回流：
		 *
		 * 同步和异步处理的主要区别
		 * 		同步：调用下一个算子的onNext()，然后lambdaSubscriber进行request。
		 * 		异步：调用onNext()之后，【直接request上游数据】。
		 *
		 * 这样也可以理解的：
		 * 		同步嘛，你必须一步一步的走。
		 * 		异步的话，一个执行完之后赶紧去请求。否则其他的线程消费不到数据咋办。
		 */
		@Override
		public void run() {
			if (outputFused) {
				runBackfused();
			}
			else if (sourceMode == Fuseable.SYNC) {
				runSync();
			}
			else {
				runAsync();
			}
		}

		boolean checkTerminated(boolean d, boolean empty, Subscriber<?> a) {
			if (cancelled) {
				Operators.onDiscardQueueWithClear(queue, actual.currentContext(), null);
				return true;
			}
			if (d) {
				if (delayError) {
					if (empty) {
						Throwable e = error;
						if (e != null) {
							doError(a, e);
						}
						else {
							doComplete(a);
						}
						return true;
					}
				}
				else {
					Throwable e = error;
					if (e != null) {
						Operators.onDiscardQueueWithClear(queue, actual.currentContext(), null);
						doError(a, e);
						return true;
					}
					else if (empty) {
						doComplete(a);
						return true;
					}
				}
			}

			return false;
		}

		@Override
		@Nullable
		public Object scanUnsafe(Attr key) {
			if (key == Attr.REQUESTED_FROM_DOWNSTREAM ) return requested;
			if (key == Attr.PARENT ) return s;
			if (key == Attr.CANCELLED) return cancelled;
			if (key == Attr.TERMINATED) return done;
			if (key == Attr.BUFFERED) return queue != null ? queue.size() : 0;
			if (key == Attr.ERROR) return error;
			if (key == Attr.DELAY_ERROR) return delayError;
			if (key == Attr.PREFETCH) return prefetch;
			if (key == Attr.RUN_ON) return worker;

			return InnerOperator.super.scanUnsafe(key);
		}

		@Override
		public CoreSubscriber<? super T> actual() {
			return actual;
		}

		@Override
		public void clear() {
			Operators.onDiscardQueueWithClear(queue, actual.currentContext(), null);
		}

		@Override
		public boolean isEmpty() {
			return queue.isEmpty();
		}

		@Override
		@Nullable
		public T poll() {
			T v = queue.poll();
			if (v != null && sourceMode != SYNC) {
				long p = produced + 1;
				if (p == limit) {
					produced = 0;
					s.request(p);
				}
				else {
					produced = p;
				}
			}
			return v;
		}

		@Override
		public int requestFusion(int requestedMode) {
			if ((requestedMode & ASYNC) != 0) {
				outputFused = true;
				return ASYNC;
			}
			return NONE;
		}

		@Override
		public int size() {
			return queue.size();
		}
	}

	static final class PublishOnConditionalSubscriber<T>
			implements QueueSubscription<T>, Runnable, InnerOperator<T, T> {

		final ConditionalSubscriber<? super T> actual;

		final Worker worker;

		final Scheduler scheduler;

		final boolean delayError;

		final int prefetch;

		final int limit;

		final Supplier<? extends Queue<T>> queueSupplier;

		Subscription s;

		Queue<T> queue;

		volatile boolean cancelled;

		volatile boolean done;

		Throwable error;

		volatile int wip;
		@SuppressWarnings("rawtypes")
		static final AtomicIntegerFieldUpdater<PublishOnConditionalSubscriber> WIP =
				AtomicIntegerFieldUpdater.newUpdater(PublishOnConditionalSubscriber.class,
						"wip");

		volatile long requested;
		@SuppressWarnings("rawtypes")
		static final AtomicLongFieldUpdater<PublishOnConditionalSubscriber> REQUESTED =
				AtomicLongFieldUpdater.newUpdater(PublishOnConditionalSubscriber.class,
						"requested");

		int sourceMode;

		long produced;

		long consumed;

		boolean outputFused;

		PublishOnConditionalSubscriber(ConditionalSubscriber<? super T> actual,
				Scheduler scheduler,
				Worker worker,
				boolean delayError,
				int prefetch,
				int lowTide,
				Supplier<? extends Queue<T>> queueSupplier) {
			this.actual = actual;
			this.worker = worker;
			this.scheduler = scheduler;
			this.delayError = delayError;
			this.prefetch = prefetch;
			this.queueSupplier = queueSupplier;
			this.limit = Operators.unboundedOrLimit(prefetch, lowTide);
		}

		@Override
		public void onSubscribe(Subscription s) {
			if (Operators.validate(this.s, s)) {
				this.s = s;

				if (s instanceof QueueSubscription) {
					@SuppressWarnings("unchecked") QueueSubscription<T> f =
							(QueueSubscription<T>) s;

					int m = f.requestFusion(Fuseable.ANY | Fuseable.THREAD_BARRIER);

					if (m == Fuseable.SYNC) {
						sourceMode = Fuseable.SYNC;
						queue = f;
						done = true;

						actual.onSubscribe(this);
						return;
					}
					if (m == Fuseable.ASYNC) {
						sourceMode = Fuseable.ASYNC;
						queue = f;

						actual.onSubscribe(this);

						s.request(Operators.unboundedOrPrefetch(prefetch));

						return;
					}
				}

				queue = queueSupplier.get();

				actual.onSubscribe(this);

				s.request(Operators.unboundedOrPrefetch(prefetch));
			}
		}

		@Override
		public void onNext(T t) {
			if (sourceMode == ASYNC) {
				trySchedule(this, null, null);
				return;
			}

			if (done) {
				Operators.onNextDropped(t, actual.currentContext());
				return;
			}
			if (!queue.offer(t)) {
				error = Operators.onOperatorError(s, Exceptions.failWithOverflow(Exceptions.BACKPRESSURE_ERROR_QUEUE_FULL), t,
						actual.currentContext());
				done = true;
			}
			trySchedule(this, null, t);
		}

		@Override
		public void onError(Throwable t) {
			if(done){
				Operators.onErrorDropped(t, actual.currentContext());
				return;
			}
			error = t;
			done = true;
			trySchedule(null, t, null);
		}

		@Override
		public void onComplete() {
			if(done){
				return;
			}
			done = true;
			trySchedule(null, null, null);
		}

		@Override
		public void request(long n) {
			if (Operators.validate(n)) {
				Operators.addCap(REQUESTED, this, n);
				trySchedule(this, null, null);
			}
		}

		@Override
		public void cancel() {
			if (cancelled) {
				return;
			}

			cancelled = true;
			s.cancel();
			worker.dispose();

			if (WIP.getAndIncrement(this) == 0) {
				Operators.onDiscardQueueWithClear(queue, actual.currentContext(), null);
			}
		}

		void trySchedule(
				@Nullable Subscription subscription,
				@Nullable Throwable suppressed,
				@Nullable Object dataSignal) {
			if (WIP.getAndIncrement(this) != 0) {
				return;
			}

			try {
				worker.schedule(this);
			}
			catch (RejectedExecutionException ree) {
				Operators.onDiscardQueueWithClear(queue, actual.currentContext(), null);
				actual.onError(Operators.onRejectedExecution(ree, subscription, suppressed, dataSignal,
						actual.currentContext()));
			}
		}

		void runSync() {
			int missed = 1;

			final ConditionalSubscriber<? super T> a = actual;
			final Queue<T> q = queue;

			long e = produced;

			for (; ; ) {

				long r = requested;

				while (e != r) {
					T v;
					try {
						v = q.poll();
					}
					catch (Throwable ex) {
						doError(a, Operators.onOperatorError(s, ex,
								actual.currentContext()));
						return;
					}

					if (cancelled) {
						Operators.onDiscardQueueWithClear(q, actual.currentContext(), null);
						return;
					}
					if (v == null) {
						doComplete(a);
						return;
					}

					if (a.tryOnNext(v)) {
						e++;
					}
				}

				if (cancelled) {
					Operators.onDiscardQueueWithClear(q, actual.currentContext(), null);
					return;
				}

				if (q.isEmpty()) {
					doComplete(a);
					return;
				}

				int w = wip;
				if (missed == w) {
					produced = e;
					missed = WIP.addAndGet(this, -missed);
					if (missed == 0) {
						break;
					}
				}
				else {
					missed = w;
				}
			}
		}

		void runAsync() {
			int missed = 1;

			final ConditionalSubscriber<? super T> a = actual;
			final Queue<T> q = queue;

			long emitted = produced;
			long polled = consumed;

			for (; ; ) {

				long r = requested;

				while (emitted != r) {
					boolean d = done;
					T v;
					try {
						v = q.poll();
					}
					catch (Throwable ex) {
						Exceptions.throwIfFatal(ex);
						s.cancel();
						Operators.onDiscardQueueWithClear(q, actual.currentContext(), null);

						doError(a, Operators.onOperatorError(ex, actual.currentContext()));
						return;
					}
					boolean empty = v == null;

					if (checkTerminated(d, empty, a)) {
						return;
					}

					if (empty) {
						break;
					}

					if (a.tryOnNext(v)) {
						emitted++;
					}

					polled++;

					if (polled == limit) {
						s.request(polled);
						polled = 0L;
					}
				}

				if (emitted == r && checkTerminated(done, q.isEmpty(), a)) {
					return;
				}

				int w = wip;
				if (missed == w) {
					produced = emitted;
					consumed = polled;
					missed = WIP.addAndGet(this, -missed);
					if (missed == 0) {
						break;
					}
				}
				else {
					missed = w;
				}
			}

		}

		void runBackfused() {
			int missed = 1;

			for (; ; ) {

				if (cancelled) {
					Operators.onDiscardQueueWithClear(queue, actual.currentContext(), null);
					return;
				}

				boolean d = done;

				actual.onNext(null);

				if (d) {
					Throwable e = error;
					if (e != null) {
						doError(actual, e);
					}
					else {
						doComplete(actual);
					}
					return;
				}

				missed = WIP.addAndGet(this, -missed);
				if (missed == 0) {
					break;
				}
			}
		}

		@Override
		public void run() {
			if (outputFused) {
				runBackfused();
			}
			else if (sourceMode == Fuseable.SYNC) {
				runSync();
			}
			else {
				runAsync();
			}
		}

		@Override
		public CoreSubscriber<? super T> actual() {
			return actual;
		}

		@Override
		@Nullable
		public Object scanUnsafe(Attr key) {
			if (key == Attr.REQUESTED_FROM_DOWNSTREAM) return requested;
			if (key == Attr.PARENT) return s;
			if (key == Attr.CANCELLED) return cancelled;
			if (key == Attr.TERMINATED) return done;
			if (key == Attr.BUFFERED) return queue != null ? queue.size() : 0;
			if (key == Attr.ERROR) return error;
			if (key == Attr.DELAY_ERROR) return delayError;
			if (key == Attr.PREFETCH) return prefetch;
			if (key == Attr.RUN_ON) return worker;

			return InnerOperator.super.scanUnsafe(key);
		}

		void doComplete(Subscriber<?> a) {
			a.onComplete();
			worker.dispose();
		}

		void doError(Subscriber<?> a, Throwable e) {
			try {
				a.onError(e);
			}
			finally {
				worker.dispose();
			}
		}

		boolean checkTerminated(boolean d, boolean empty, Subscriber<?> a) {
			if (cancelled) {
				Operators.onDiscardQueueWithClear(queue, actual.currentContext(), null);
				return true;
			}
			if (d) {
				if (delayError) {
					if (empty) {
						Throwable e = error;
						if (e != null) {
							doError(a, e);
						}
						else {
							doComplete(a);
						}
						return true;
					}
				}
				else {
					Throwable e = error;
					if (e != null) {
						Operators.onDiscardQueueWithClear(queue, actual.currentContext(), null);
						doError(a, e);
						return true;
					}
					else if (empty) {
						doComplete(a);
						return true;
					}
				}
			}

			return false;
		}

		@Override
		public void clear() {
			Operators.onDiscardQueueWithClear(queue, actual.currentContext(), null);
		}

		@Override
		public boolean isEmpty() {
			return queue.isEmpty();
		}

		@Override
		@Nullable
		public T poll() {
			T v = queue.poll();
			if (v != null && sourceMode != SYNC) {
				long p = consumed + 1;
				if (p == limit) {
					consumed = 0;
					s.request(p);
				}
				else {
					consumed = p;
				}
			}
			return v;
		}

		@Override
		public int requestFusion(int requestedMode) {
			if ((requestedMode & ASYNC) != 0) {
				outputFused = true;
				return ASYNC;
			}
			return NONE;
		}

		@Override
		public int size() {
			return queue.size();
		}
	}
}
