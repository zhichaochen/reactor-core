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
import java.util.function.BiFunction;
import java.util.function.Supplier;

import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.Fuseable;
import reactor.util.annotation.Nullable;

/**
 * Aggregates the source values with the help of an accumulator
 * function and emits the the final accumulated value.
 *
 * @param <T> the source value type
 * @param <R> the accumulated result type
 *
 * @see <a href="https://github.com/reactor/reactive-streams-commons">Reactive-Streams-Commons</a>
 * 给定一个初始值，在初始值的基础上进行聚合。
 * 例如：
 * Flux.just(1, 2, 3).reduceWith(() -> 4, (x, y) -> x + y).subscribe(System.out::println);
 * 结果：10
 */
final class MonoReduceSeed<T, R> extends MonoFromFluxOperator<T, R>
		implements Fuseable {

	final Supplier<R> initialSupplier;

	final BiFunction<R, ? super T, R> accumulator;

	MonoReduceSeed(Flux<? extends T> source,
			Supplier<R> initialSupplier,
			BiFunction<R, ? super T, R> accumulator) {
		super(source);
		this.initialSupplier = Objects.requireNonNull(initialSupplier, "initialSupplier");
		this.accumulator = Objects.requireNonNull(accumulator, "accumulator");
	}

	@Override
	public CoreSubscriber<? super T> subscribeOrReturn(CoreSubscriber<? super R> actual) {
		R initialValue;

		try {
			initialValue = Objects.requireNonNull(initialSupplier.get(),
					"The initial value supplied is null");
		}
		catch (Throwable e) {
			Operators.error(actual, Operators.onOperatorError(e, actual.currentContext()));
			return null;
		}

		return new ReduceSeedSubscriber<>(actual, accumulator, initialValue);
	}

	static final class ReduceSeedSubscriber<T, R> extends Operators.MonoSubscriber<T, R>  {

		final BiFunction<R, ? super T, R> accumulator;

		Subscription s;

		boolean done;

		ReduceSeedSubscriber(CoreSubscriber<? super R> actual,
				BiFunction<R, ? super T, R> accumulator,
				R value) {
			super(actual);
			this.accumulator = accumulator;
			this.value = value;
		}

		@Override
		@Nullable
		public Object scanUnsafe(Attr key) {
			if (key == Attr.TERMINATED) return done;
			if (key == Attr.PARENT) return s;

			return super.scanUnsafe(key);
		}

		@Override
		public void cancel() {
			super.cancel();
			s.cancel();
		}

		@Override
		public void setValue(R value) {
			// value already saved
		}

		@Override
		public void onSubscribe(Subscription s) {
			if (Operators.validate(this.s, s)) {
				this.s = s;

				actual.onSubscribe(this);

				s.request(Long.MAX_VALUE);
			}
		}

		@Override
		public void onNext(T t) {
			R v = this.value;
			R accumulated;

			if (v != null) { //value null when cancelled
				try {
					accumulated = Objects.requireNonNull(accumulator.apply(v, t),
							"The accumulator returned a null value");

				}
				catch (Throwable e) {
					onError(Operators.onOperatorError(this, e, t, actual.currentContext()));
					return;
				}
				value = accumulated;
			} else {
				Operators.onDiscard(t, actual.currentContext());
			}
		}

		@Override
		public void onError(Throwable t) {
			if (done) {
				Operators.onErrorDropped(t, actual.currentContext());
				return;
			}
			done = true;
			Operators.onDiscard(value, actual.currentContext());
			value = null;

			actual.onError(t);
		}

		@Override
		public void onComplete() {
			if (done) {
				return;
			}
			done = true;

			complete(value);
			//we DON'T null out the value, complete will do that once there's been a request
		}
	}
}
