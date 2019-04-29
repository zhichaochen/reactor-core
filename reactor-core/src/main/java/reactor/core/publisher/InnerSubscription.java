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

package reactor.core.publisher;

import org.reactivestreams.Subscription;

/**
 * A {@link InnerSubscription} is a {@link InnerProducer} {@link Subscription} that will
 * acknowledge it is done {@link Subscription#cancel() cancelling} to its {@link reactor.core.CoreSubscriber}.
 *
 * @author Simon Baslé
 */
public interface InnerSubscription<T> extends InnerProducer<T> {

	/**
	 * {@inheritDoc}
	 * <p>
	 * An {@link InnerSubscription} MUST call {@link reactor.core.CoreSubscriber#onCancelled()}
	 * on its actual when it is done cleaning up as part of cancellation.
	 */
	@Override
	void cancel();

}
