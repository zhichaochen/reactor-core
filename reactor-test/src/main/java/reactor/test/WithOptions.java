/*
 * Copyright (c) 2011-2019 Pivotal Software Inc, All Rights Reserved.
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
package reactor.test;

import java.util.function.Function;
import java.util.function.Supplier;

import org.reactivestreams.Publisher;

import reactor.core.publisher.Flux;
import reactor.test.ValueFormatters.Extractor;
import reactor.test.ValueFormatters.ToStringConverter;
import reactor.test.scheduler.VirtualTimeScheduler;
import reactor.util.annotation.Nullable;
import reactor.util.context.Context;

/**
 * A {@link Publisher}-capturing version of {@link StepVerifierOptions} that can be used to set up options then
 * directly switch to a {@link StepVerifier.FirstStep} of expectations. This is useful combined with {@link Flux#as(Function)}
 * for fluent style of setting up options and asserting expectations.
 *
 * @author Simon Basle
 */
public class WithOptions<T> extends StepVerifierOptions {

	final Publisher<T> publisher;

	private WithOptions(Publisher<T> publisher) {
		this.publisher = publisher;
	}

	public StepVerifier.FirstStep<T> withTestScenario() {
		return StepVerifier.create(publisher, this);
	}

	// == reproduce the builder methods with WithOptions<T> return type ==

	@Override
	public WithOptions<T> checkUnderRequesting(boolean enabled) {
		super.checkUnderRequesting(enabled);
		return this;
	}

	@Override
	public WithOptions<T> initialRequest(long initialRequest) {
		super.initialRequest(initialRequest);
		return this;
	}

	@Override
	public WithOptions<T> valueFormatter(@Nullable ToStringConverter valueFormatter) {
		super.valueFormatter(valueFormatter);
		return this;
	}

	@Override
	public <R> WithOptions<T> extractor(Extractor<R> extractor) {
		super.extractor(extractor);
		return this;
	}

	@Override
	public WithOptions<T> virtualTimeSchedulerSupplier(Supplier<? extends VirtualTimeScheduler> vtsLookup) {
		super.virtualTimeSchedulerSupplier(vtsLookup);
		return this;
	}

	@Override
	public WithOptions<T> withInitialContext(Context context) {
		super.withInitialContext(context);
		return this;
	}

	@Override
	public WithOptions<T> scenarioName(@Nullable String scenarioName) {
		super.scenarioName(scenarioName);
		return this;
	}
}
