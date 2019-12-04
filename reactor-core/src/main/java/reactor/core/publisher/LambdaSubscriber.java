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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Consumer;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.Disposable;
import reactor.core.Exceptions;
import reactor.core.scheduler.Schedulers;
import reactor.util.annotation.Nullable;
import reactor.util.context.Context;
import reactor.util.function.Tuple2;


/**
 * An unbounded Java Lambda adapter to {@link Subscriber}
 *
 * Subscriber的一个无界的adapter
 * Subscription ：表示publisher 和 subscribe 一对一的生命周期
 *
 * 总体来书：调用相关节点的方法，然后处理subscription对象状态。
 * @param <T> the value type
 */
final class LambdaSubscriber<T>
		implements InnerConsumer<T>, Disposable {

	final Consumer<? super T>            consumer;
	final Consumer<? super Throwable>    errorConsumer;
	final Runnable                       completeConsumer;
	final Consumer<? super Subscription> subscriptionConsumer;
	final Context                        initialContext;

	volatile Subscription subscription;
	static final AtomicReferenceFieldUpdater<LambdaSubscriber, Subscription> S =
			AtomicReferenceFieldUpdater.newUpdater(LambdaSubscriber.class,
					Subscription.class,
					"subscription");

	/**
	 * Create a {@link Subscriber} reacting onNext, onError and onComplete. If no
	 * {@code subscriptionConsumer} is provided, the subscriber will automatically request
	 * Long.MAX_VALUE in onSubscribe, as well as an initial {@link Context} that will be
	 * visible by operators upstream in the chain.
	 *
	 * @param consumer A {@link Consumer} with argument onNext data
	 * @param errorConsumer A {@link Consumer} called onError
	 * @param completeConsumer A {@link Runnable} called onComplete with the actual
	 * context if any
	 * @param subscriptionConsumer A {@link Consumer} called with the {@link Subscription}
	 * to perform initial request, or null to request max
	 * @param initialContext A {@link Context} for this subscriber, or null to use the default
	 * of an {@link Context#empty() empty Context}.
	 */
	LambdaSubscriber(
			@Nullable Consumer<? super T> consumer,
			@Nullable Consumer<? super Throwable> errorConsumer,
			@Nullable Runnable completeConsumer,
			@Nullable Consumer<? super Subscription> subscriptionConsumer,
			@Nullable Context initialContext) {
		this.consumer = consumer;
		this.errorConsumer = errorConsumer;
		this.completeConsumer = completeConsumer;
		this.subscriptionConsumer = subscriptionConsumer;
		this.initialContext = initialContext == null ? Context.empty() : initialContext;
	}

	/**
	 * Create a {@link Subscriber} reacting onNext, onError and onComplete. If no
	 * {@code subscriptionConsumer} is provided, the subscriber will automatically request
	 * Long.MAX_VALUE in onSubscribe, as well as an initial {@link Context} that will be
	 * visible by operators upstream in the chain.
	 *
	 * @param consumer A {@link Consumer} with argument onNext data
	 * @param errorConsumer A {@link Consumer} called onError
	 * @param completeConsumer A {@link Runnable} called onComplete with the actual
	 * context if any
	 * @param subscriptionConsumer A {@link Consumer} called with the {@link Subscription}
	 * to perform initial request, or null to request max
	 */ //left mainly for the benefit of tests
	LambdaSubscriber(
			@Nullable Consumer<? super T> consumer,
			@Nullable Consumer<? super Throwable> errorConsumer,
			@Nullable Runnable completeConsumer,
			@Nullable Consumer<? super Subscription> subscriptionConsumer) {
		this(consumer, errorConsumer, completeConsumer, subscriptionConsumer, null);
	}

	/**
	 * 线程的上下文，相当于threadlocal
	 */
	@Override
	public Context currentContext() {
		return this.initialContext;
	}

	/**
	 * 订阅之后做的操作。
	 */
	@Override
	public final void onSubscribe(Subscription s) {
		if (Operators.validate(subscription, s)) {
			this.subscription = s;
			if (subscriptionConsumer != null) {
				try {
					subscriptionConsumer.accept(s);
				}
				catch (Throwable t) {
					Exceptions.throwIfFatal(t);
					s.cancel();
					onError(t);
				}
			}
			else {
				//请求上游元素，默认请求全部
				s.request(Long.MAX_VALUE);
			}
		}
	}

	/**
	 * 处理完毕
	 *
	 */
	@Override
	public final void onComplete() {
		/**
		 * 1、返回当前Subscription订阅对象。
		 * 2、修改s为CancelledSubscription，表示该订阅对象已经删除
		 */
		Subscription s = S.getAndSet(this, Operators.cancelledSubscription());
		if (s == Operators.cancelledSubscription()) {
			//如果已经是删除的状态了,则直接返回
			return;
		}
		//
		if (completeConsumer != null) {
			try {
				//执行完成后的处理逻辑。
				completeConsumer.run();
			}
			catch (Throwable t) {
				Exceptions.throwIfFatal(t);
				//执行报错的处理逻辑
				onError(t);
			}
		}
	}

	@Override
	public final void onError(Throwable t) {
		Subscription s = S.getAndSet(this, Operators.cancelledSubscription());
		if (s == Operators.cancelledSubscription()) {
			Operators.onErrorDropped(t, this.initialContext);
			return;
		}
		if (errorConsumer != null) {
			errorConsumer.accept(t);
		}
		else {
			throw Exceptions.errorCallbackNotImplemented(t);
		}
	}

	@Override
	public final void onNext(T x) {
		try {
			if (consumer != null) {
				consumer.accept(x);
			}
		}
		catch (Throwable t) {
			Exceptions.throwIfFatal(t);
			this.subscription.cancel();
			onError(t);
		}
	}

	@Override
	@Nullable
	public Object scanUnsafe(Attr key) {
		if (key == Attr.PARENT) return subscription;
		if (key == Attr.PREFETCH) return Integer.MAX_VALUE;
		if (key == Attr.TERMINATED || key == Attr.CANCELLED) return isDisposed();

		return null;
	}


	@Override
	public boolean isDisposed() {
		return subscription == Operators.cancelledSubscription();
	}

	/**
	 * 丢弃订阅对象
	 */
	@Override
	public void dispose() {
		Subscription s = S.getAndSet(this, Operators.cancelledSubscription());
		if (s != null && s != Operators.cancelledSubscription()) {
			s.cancel();
		}
	}

	public static void main(String[] args) {
		//Hooks.onOperatorDebug();
		/*Flux.range(1, 10)
				.map(x -> x + 1)
				.take(3)
				.filter(x -> x != 1)
				.subscribe(System.out::println);*/


		/*Flux.just("tom")
				.map(s -> {
					System.out.println("(concat @qq.com) at [" + Thread.currentThread() + "]");
					return s.concat("@qq.com");
				})
				.publishOn(Schedulers.newSingle("thread-a"))
				.map(s -> {
					System.out.println("(concat foo) at [" + Thread.currentThread() + "]");
					return s.concat("foo");
				})
				.filter(s -> {
					System.out.println("(startsWith f) at [" + Thread.currentThread() + "]");
					return s.startsWith("t");
				})
				//Schedulers.newParallel ：创建线程池，初始化线程池工厂，初始化线程池调度器
				//创建FluxPublishOn
				.publishOn(Schedulers.newParallel("thread-b"))
				.map(s -> {
					System.out.println("(to length) at [" + Thread.currentThread() + "]");
					return s.length();
				})
				.subscribeOn(Schedulers.newSingle("source"))
				.subscribe();*/


		/*Flux<Integer> flux = Flux.range(1, 10)
				.log()
				.take(3);
		flux.subscribe(System.out::println);*/

		//绑定给定输入序列的动态序列，如flatMap，但保留顺序和串联发射，而不是合并(没有交织)
		/*Flux.just(5, 10,100)
				.concatMap(x -> Flux.just(x * 10, 100))
				.toStream()
				.forEach(System.out::println);
		//我的总结,对上游元素，concatMap中输入函数的操作。
		System.out.println("================");
		Flux.just(1, 2)
				.concat(Flux.just(5,6))
				.toStream()
				.forEach(System.out::println);
		System.out.println("================");
		Flux.concat(Mono.just(3), Mono.just(4), Flux.just(1, 2))
				.subscribe(System.out::println);*/

		/*Flux.just(1, 2, 3).reduce((x, y) -> x + y).subscribe(System.out::println);
		Flux.just(1, 2, 3).reduceWith(() -> 10 + 10, (x, y) -> x + y).subscribe(System.out::println);*/

		/*Flux.merge(Flux.just(0, 1, 2, 3), Flux.just(7, 5, 6), Flux.just(4, 7), Flux.just(4, 7))
				.toStream()
				.forEach(System.out::print);

		Flux<Integer> flux = Flux.mergeSequential(Flux.just(9, 8, 7), Flux.just(0, 1, 2, 3),
				Flux.just(6, 5, 4));
		System.out.println();
		flux.sort().subscribe(System.out::print);
		System.out.println();
		flux.subscribe(System.out::print);

		01237564747
		0123456789
		9870123654*/


		/*Flux.just(1, 2)
				.flatMap(x -> Flux.just(x * 10, 100))
				.toStream()
				.forEach(System.out::println);*/

		/*Flux<Flux<Integer>> window = Flux.range(1, 10)
				.window(3);
		//subscribe1
		window.subscribe(integerFlux -> {
			System.out.println("+++++分隔符+++++");
			integerFlux.subscribe(System.out::println);
		});
		System.out.println("---------- 分割线1 ----------");

		//subscribe2
		window.toStream().forEach(integerFlux -> {
			System.out.println("+++++分隔符+++++");
			integerFlux.subscribe(System.out::println);
		});
		System.out.println("---------- 分割线2 ----------");*/


		/*final AtomicInteger index = new AtomicInteger();
		Flux<Tuple2<String, String>> tuple2Flux = Flux.just("a", "b")
				.zipWith(Flux.just("c","d"));
		//subscribe1
		tuple2Flux.subscribe(System.out::println);
		System.out.println("————— 分割线1 —————");

		//subscribe2
		tuple2Flux.subscribe(tupleFlux -> {
			System.out.println("t1—————" + index + "—————>" + tupleFlux.getT1());
			System.out.println("t2—————" + index + "—————>" + tupleFlux.getT2());
			index.incrementAndGet();
		});
		System.out.println("————— 分割线2 —————");*/

		//Flux.range(1,10).checkpoint();
		//Flux.range(1,10).checkpoint("aaa");

		Flux<Integer> flux = Flux.range(1, 10)
				.log()
				.take(3);
	}

	public static void source1 (){
		System.out.println("(source1) at [" + Thread.currentThread() + "]");
	}
}
