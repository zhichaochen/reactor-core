/*
 * Copyright (c) 2011-2019 Pivotal Software Inc, All Rights Reserved.
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

package reactor.util.concurrent;

import java.util.Collection;
import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Shaded from https://github.com/JCTools/JCTools
 * <p>
 * A Multi-Producer-Single-Consumer queue based on a {@literal org.jctools.queues.ConcurrentCircularArrayQueue}. This
 * implies that any thread may call the offer method, but only a single thread may call poll/peek for correctness to
 * maintained. <br> This implementation follows patterns documented on the package level for False Sharing
 * protection.<br> This implementation is using the <a href="http://sourceforge.net/projects/mc-fastflow/">Fast Flow</a>
 * method for polling from the queue (with minor change to correctly publish the index) and an extension of the Leslie
 * Lamport concurrent queue algorithm (originated by Martin Thompson) on the producer side.<br>
 *
 * <p> Adapted to not use (unfortunately) Unsafe access and remove unrequired extra API</p>
 *
 * @param <E>
 *
 * @author nitsanw
 *
 *
 */

final class MpscArrayQueue<E> extends MpscArrayQueueL3Pad<E> {

	final int mask;

	MpscArrayQueue(final int capacity) {
		super(capacity);
		int actualCapacity = Queues.ceilingNextPowerOfTwo(capacity);
		mask = actualCapacity - 1;
	}

	@Override
	public void clear() {
		while (poll() != null) {
			// if you stare into the void
		}
	}

	@Override
	public final boolean isEmpty() {
		return consumerIndex == producerIndex;
	}

	@Override
	public Iterator<E> iterator() {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean offer(final E e) {
		if (null == e) {
			throw new NullPointerException();
		}

		// use a cached view on consumer index (potentially updated in loop)
		final int mask = this.mask;
		long pLimit = this.producerLimit; // LoadLoad
		long pIndex;
		do {
			pIndex = producerIndex; // LoadLoad
			if (pIndex >= pLimit) {
				final long cIndex = consumerIndex; // LoadLoad
				pLimit = cIndex + mask + 1;

				if (pIndex >= pLimit) {
					return false; // FULL :(
				}
				else {
					// update producer limit to the next index that we must recheck the consumer index
					// this is racy, but the race is benign
					soProducerLimit(pLimit);
				}
			}
		}
		while (!casProducerIndex(pIndex, pIndex + 1));
		/*
		 * NOTE: the new producer index value is made visible BEFORE the element in the array. If we relied on
		 * the index visibility to poll() we would need to handle the case where the element is not visible.
		 */

		// Won CAS, move on to storing
		int offset =  (int)pIndex & mask;
		lazySet(offset, e); // StoreStore
		return true;
	}

	@Override
	public E peek() {
		final long cIndex = consumerIndex; // LoadLoad
		final int offset =  (int)cIndex & mask;
		E e = get(offset);
		if (null == e) {
			/*
			 * NOTE: Queue may not actually be empty in the case of a producer (P1) being interrupted after
			 * winning the CAS on offer but before storing the element in the queue. Other producers may go on
			 * to fill up the queue after this element.
			 */
			if (cIndex != producerIndex) {
				do {
					e = get(offset);
				}
				while (e == null);
			}
			else {
				return null;
			}
		}
		return e;
	}

	@Override
	public E poll() {
		final long cIndex = consumerIndex;
		final int offset =  (int)cIndex & mask;

		// If we can't see the next available element we can't poll
		E e = get(offset);// LoadLoad
		if (null == e) {
			/*
			 * NOTE: Queue may not actually be empty in the case of a producer (P1) being interrupted after
			 * winning the CAS on offer but before storing the element in the queue. Other producers may go on
			 * to fill up the queue after this element.
			 */
			if (cIndex != producerIndex) {
				do {
					e = get(offset);
				}
				while (e == null);
			}
			else {
				return null;
			}
		}

		lazySet(offset, null);
		soConsumerIndex(cIndex + 1); // StoreStore
		return e;
	}

	@Override
	public final int size() {
		/*
		 * It is possible for a thread to be interrupted or reschedule between the read of the producer and
		 * consumer indices, therefore protection is required to ensure size is within valid range. In the
		 * event of concurrent polls/offers to this method the size is OVER estimated as we read consumer
		 * index BEFORE the producer index.
		 */
		long after = consumerIndex;
		long size;
		while (true) {
			final long before = after;
			final long currentProducerIndex = producerIndex;
			after = consumerIndex;
			if (before == after) {
				size = (currentProducerIndex - after);
				break;
			}
		}
		// Long overflow is impossible (), so size is always positive. Integer overflow is possible for the unbounded
		// indexed queues.
		if (size > Integer.MAX_VALUE) {
			return Integer.MAX_VALUE;
		}
		else {
			return (int) size;
		}
	}

	@Override
	public boolean contains(Object o) {
		throw new UnsupportedOperationException();
	}

	@Override
	public Object[] toArray() {
		throw new UnsupportedOperationException();
	}

	@Override
	public <R> R[] toArray(R[] a) {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean remove(Object o) {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean containsAll(Collection<?> c) {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean addAll(Collection<? extends E> c) {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean removeAll(Collection<?> c) {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean retainAll(Collection<?> c) {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean add(E e) {
		throw new UnsupportedOperationException();
	}

	@Override
	public E remove() {
		throw new UnsupportedOperationException();
	}

	@Override
	public E element() {
		throw new UnsupportedOperationException();
	}
}

abstract class MpscArrayQueueL0Pad<E> extends AtomicReferenceArray<E> implements Queue<E> {

	long p01, p02, p03, p04, p05, p06, p07, p08, p09, p10, p11, p12, p13, p14, p15;

	MpscArrayQueueL0Pad(int length) {
		super(length);
	}
}

abstract class MpscArrayQueueProducerIndexField<E> extends MpscArrayQueueL0Pad<E> {

	static AtomicLongFieldUpdater<MpscArrayQueueProducerIndexField> PRODUCER_INDEX =
			AtomicLongFieldUpdater.newUpdater(MpscArrayQueueProducerIndexField.class, "producerIndex");
	volatile long producerIndex;

	MpscArrayQueueProducerIndexField(int length) {
		super(length);
	}

	final boolean casProducerIndex(long expect, long newValue) {
		return PRODUCER_INDEX.compareAndSet(this, expect, newValue);
	}
}

abstract class MpscArrayQueueMidPad<E> extends MpscArrayQueueProducerIndexField<E> {

	long p16, p17, p18, p19, p20, p21, p22, p23, p24, p25, p26, p27, p28, p29, p30;

	MpscArrayQueueMidPad(int length) {
		super(length);
	}
}

abstract class MpscArrayQueueProducerLimitField<E> extends MpscArrayQueueMidPad<E> {

	static AtomicLongFieldUpdater<MpscArrayQueueProducerLimitField> PRODUCER_LIMIT =
			AtomicLongFieldUpdater.newUpdater(MpscArrayQueueProducerLimitField.class, "producerLimit");
	// First unavailable index the producer may claim up to before rereading the consumer index
	volatile long producerLimit;

	MpscArrayQueueProducerLimitField(int capacity) {
		super(capacity);
		this.producerLimit = capacity;
	}

	final void soProducerLimit(long newValue) {
		PRODUCER_LIMIT.set(this, newValue);
	}
}

abstract class MpscArrayQueueL2Pad<E> extends MpscArrayQueueProducerLimitField<E> {

	long p31, p32, p33, p34, p35, p36, p37, p38, p39, p40, p41, p42, p43, p44, p45;

	MpscArrayQueueL2Pad(int capacity) {
		super(capacity);
	}
}

abstract class MpscArrayQueueConsumerIndexField<E> extends MpscArrayQueueL2Pad<E> {

	volatile long consumerIndex;

	MpscArrayQueueConsumerIndexField(int capacity) {
		super(capacity);
	}

	void soConsumerIndex(long newValue) {
		CONSUMER_INDEX.set(this, newValue);
	}

	static final AtomicLongFieldUpdater<MpscArrayQueueConsumerIndexField> CONSUMER_INDEX =
			AtomicLongFieldUpdater.newUpdater(MpscArrayQueueConsumerIndexField.class, "consumerIndex");
}

abstract class MpscArrayQueueL3Pad<E> extends MpscArrayQueueConsumerIndexField<E> {

	long p46, p47, p48, p49, p50, p51, p52, p53, p54, p55, p56, p57, p58, p59, p60;

	MpscArrayQueueL3Pad(int capacity) {
		super(capacity);
	}
}
