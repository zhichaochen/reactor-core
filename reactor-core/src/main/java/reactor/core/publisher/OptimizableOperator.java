/*
 * Copyright (c) 2019-Present Pivotal Software Inc, All Rights Reserved.
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

import reactor.core.CorePublisher;
import reactor.core.CoreSubscriber;
import reactor.util.annotation.Nullable;

/**
 * A common interface of operator in Reactor which adds contracts around subscription
 * optimizations:
 *
 * <ul>
 *     <li>looping instead of recursive subscribes, via {@link #subscribeOrReturn(CoreSubscriber)} and
 *     {@link #nextOptimizableSource()}</li>
 * </ul>
 * @param <IN> the {@link CoreSubscriber} data type
 * @since 3.3.0
 *
 * 可优化的算子，实现该接口的算子：
 * 		通过其source方法，获取某个算子的上游的算子
 * 		通过其nextOptimizableSource，获取算子的下游的算子。
 *
 *
 * 这是一个通用的接口，在此接口，它在订阅的周围添加契约
 * 循环而不是递归去订阅，通过 #subscribeOrReturn() 和 #nextOptimizableSource
 */
interface OptimizableOperator<IN, OUT> extends CorePublisher<IN> {

	/**
	 * Allow delegation of the subscription by returning a {@link CoreSubscriber}, or force
	 * subscription encapsulation by returning null. This can be used in conjunction with {@link #nextOptimizableSource()}
	 * to perform subscription in a loop instead of by recursion.
	 *
	 * @return next {@link CoreSubscriber} or "null" if the subscription was already done inside the method
	 *
	 * 通过返回CoreSubscriber或强制返回null进行订阅封装。
	 * 这可以与nextOptimizableSource一起使用，在循环中而不是通过递归来执行订阅。
	 *
	 * return next or null ，如果订阅已经在方法内完成，则返回“null
	 */
	@Nullable
	CoreSubscriber<? super OUT> subscribeOrReturn(CoreSubscriber<? super IN> actual);

	/**
	 * @return {@link CorePublisher} to call {@link CorePublisher#subscribe(CoreSubscriber)} on
	 * if {@link #nextOptimizableSource()} have returned null result
	 *
	 * CorePublisher 去调用 CorePublisher#subscribe 在 nextOptimizableSource() 方法已经返回空值。
	 */
	CorePublisher<? extends OUT> source();

	/**
	 * Allow delegation of the subscription by returning the next {@link OptimizableOperator} UP in the
	 * chain, to be subscribed to next. This method MUST return a non-null value if the {@link #subscribeOrReturn(CoreSubscriber)}
	 * method returned a non null value. In that case, this next operator can be used in conjunction with {@link #subscribeOrReturn(CoreSubscriber)}
	 * to perform subscription in a loop instead of by recursion.
	 *
	 * @return next {@link OptimizableOperator} if {@link #subscribeOrReturn(CoreSubscriber)} have returned non-null result
	 *
	 * 允许通过返回链中要订阅的下一个 OptimizableOperator来委派订阅
	 * 如果subscribeOrReturn(CoreSubscriber)方法返回一个非空值。
	 *
	 * 在这种情况下，这个next操作符可以与subscribeOrReturn(CoreSubscriber)一起在循环中执行订阅，而不是通过递归。
	 */
	@Nullable
	OptimizableOperator<?, ? extends OUT> nextOptimizableSource();
}
