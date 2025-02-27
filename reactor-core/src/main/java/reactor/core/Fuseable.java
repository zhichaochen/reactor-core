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
package reactor.core;

import java.util.Collection;
import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.Callable;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.util.annotation.Nullable;

/**
 * A micro API for stream fusion, in particular marks producers that support a {@link QueueSubscription}.
 *
 * 流融合
 */
public interface Fuseable {

	/** Indicates the QueueSubscription can't support the requested mode. */
	//表示QueueSubscription 是无法支持的请求模式
	int NONE = 0;
	/** Indicates the QueueSubscription can perform sync-fusion. */
	//表示QueueSubscription 可以执行同步融合
	int SYNC = 1;
	/** Indicates the QueueSubscription can perform only async-fusion. */
	//表示QueueSubscription 可以执行异步融合
	int ASYNC = 2;
	/** Indicates the QueueSubscription should decide what fusion it performs (input only). */
	//QueueSubscription 应决定它执行的融合（仅输入）
	int ANY = 3;
	/**
	 * Indicates that the queue will be drained from another thread
	 * thus any queue-exit computation may be invalid at that point.
	 * <p>
	 * For example, an {@code asyncSource.map().publishOn().subscribe()} sequence where {@code asyncSource}
	 * is async-fuseable: publishOn may fuse the whole sequence into a single Queue. That in turn
	 * could invoke the mapper function from its {@code poll()} method from another thread,
	 * whereas the unfused sequence would have invoked the mapper on the previous thread.
	 * If such mapper invocation is costly, it would escape its thread boundary this way.
	 *
	 * 指示队列将从另一个线程中排出，因此，任何队列退出计算在那一点可能是无效的。
	 * thread_barrier :线程障碍
	 */
	int THREAD_BARRIER = 4;

	/**
	 * A subscriber variant that can immediately tell if it consumed
	 * the value or not, directly allowing a new value to be sent if
	 * it didn't. This avoids the usual request(1) round-trip for dropped
	 * values.
	 *
	 * @param <T> the value type
	 *
	 * 一种订阅者变量，可以立即判断它是否消费了该值
	 * 直接允许发送新值（如果它没有）
	 *
	 * 这避免了用request(1) 往返删掉的值
	 */
	interface ConditionalSubscriber<T> extends CoreSubscriber<T> {
		/**
		 * Try consuming the value and return true if successful.
		 * @param t the value to consume, not null
		 * @return true if consumed, false if dropped and a new value can be immediately sent
		 *
		 * 尝试去消费value
		 */
		boolean tryOnNext(T t);
	}

	/**
	 * Support contract for queue-fusion based optimizations on subscriptions.
	 *
	 * <ul>
	 *  <li>
	 *  Synchronous sources which have fixed size and can
	 *  emit their items in a pull fashion, thus avoiding the request-accounting
	 *  overhead in many cases.
	 *  </li>
	 *  <li>
	 *  Asynchronous sources which can act as a queue and subscription at
	 *  the same time, saving on allocating another queue most of the time.
	 * </li>
	 * </ul>
	 *
	 * <p>
	 *
	 * @param <T> the value type emitted
	 *
	 * 支持订阅上基于队列融合的优化的协定。
	 * OptimizableOperator：可优化的算子，实现该接口，表示可以
	 *
	 * 我的理解：表示可以通过 队列融合的方式 进行优化。
	 * 上游生产者，发送数据到queue中，下游消费者开启多线程消费。
	 */
	interface QueueSubscription<T> extends Queue<T>, Subscription {
		
		String NOT_SUPPORTED_MESSAGE = "Although QueueSubscription extends Queue it is purely internal" +
				" and only guarantees support for poll/clear/size/isEmpty." +
				" Instances shouldn't be used/exposed as Queue outside of Reactor operators.";

		/**
		 * Request a specific fusion mode from this QueueSubscription.
		 * <p>
		 * One should request either SYNC, ASYNC or ANY modes (never NONE)
		 * and the implementor should return NONE, SYNC or ASYNC (never ANY).
		 * <p>
		 * For example, if a source supports only ASYNC fusion but
		 * the intermediate operator supports only SYNC fuseable sources,
		 * the operator may request SYNC fusion and the source can reject it via
		 * NONE, thus the operator can return NONE as well to downstream and the
		 * fusion doesn't happen.
		 *
		 * @param requestedMode the mode requested by the intermediate operator
		 * @return the actual fusion mode activated
		 */
		int requestFusion(int requestedMode);

		
		@Override
		@Nullable
		default T peek() {
			throw new UnsupportedOperationException(NOT_SUPPORTED_MESSAGE);
		}

		@Override
		default boolean add(@Nullable T t) {
			throw new UnsupportedOperationException(NOT_SUPPORTED_MESSAGE);
		}

		@Override
		default boolean offer(@Nullable T t) {
			throw new UnsupportedOperationException(NOT_SUPPORTED_MESSAGE);
		}

		@Override
		default T remove() {
			throw new UnsupportedOperationException(NOT_SUPPORTED_MESSAGE);
		}

		@Override
		default T element() {
			throw new UnsupportedOperationException(NOT_SUPPORTED_MESSAGE);
		}

		@Override
		default boolean contains(@Nullable Object o) {
			throw new UnsupportedOperationException(NOT_SUPPORTED_MESSAGE);
		}

		@Override
		default Iterator<T> iterator() {
			throw new UnsupportedOperationException(NOT_SUPPORTED_MESSAGE);
		}

		@Override
		default Object[] toArray() {
			throw new UnsupportedOperationException(NOT_SUPPORTED_MESSAGE);
		}

		@Override
		default <T1> T1[] toArray(T1[] a) {
			throw new UnsupportedOperationException(NOT_SUPPORTED_MESSAGE);
		}

		@Override
		default boolean remove(@Nullable Object o) {
			throw new UnsupportedOperationException(NOT_SUPPORTED_MESSAGE);
		}

		@Override
		default boolean containsAll(Collection<?> c) {
			throw new UnsupportedOperationException(NOT_SUPPORTED_MESSAGE);
		}

		@Override
		default boolean addAll(Collection<? extends T> c) {
			throw new UnsupportedOperationException(NOT_SUPPORTED_MESSAGE);
		}

		@Override
		default boolean removeAll(Collection<?> c) {
			throw new UnsupportedOperationException(NOT_SUPPORTED_MESSAGE);
		}

		@Override
		default boolean retainAll(Collection<?> c) {
			throw new UnsupportedOperationException(NOT_SUPPORTED_MESSAGE);
		}
	}

	/**
	 * Base class for synchronous sources which have fixed size and can
	 * emit their items in a pull fashion, thus avoiding the request-accounting
	 * overhead in many cases.
	 *
	 * @param <T> the content value type
	 */
	interface SynchronousSubscription<T> extends QueueSubscription<T> {

		@Override
		default int requestFusion(int requestedMode) {
			if ((requestedMode & Fuseable.SYNC) != 0) {
				return Fuseable.SYNC;
			}
			return NONE;
		}

	}

	/**
	 * Marker interface indicating that the target can return a value or null,
	 * otherwise fail immediately and thus a viable target for assembly-time
	 * optimizations.
	 *
	 * @param <T> the value type returned
	 *
	 * 指示目标可以返回值或者null，否则立即失效，这对assembly-time 是一个可行的优化。
	 *
	 *  我的总结：（直接调用call()方法返回结果，不再执行后面的步骤了。）
	 *    实现该接口的类，可以直接返回值或者null，无需在链式结构中继续执行下去了。
	 *    比如FluxError,当发生错误的时候，在subscribe的时候，会调用call方法，抛出异常，结束调用。
	 *
	 *  参见Flux.from的处理。
	 */
	interface ScalarCallable<T> extends Callable<T> { }
}