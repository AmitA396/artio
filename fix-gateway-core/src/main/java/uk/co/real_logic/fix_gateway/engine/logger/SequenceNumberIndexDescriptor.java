/*
 * Copyright 2015=2016 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.fix_gateway.engine.logger;

import uk.co.real_logic.agrona.concurrent.AtomicBuffer;
import uk.co.real_logic.agrona.concurrent.UnsafeBuffer;
import uk.co.real_logic.fix_gateway.messages.LastKnownSequenceNumberDecoder;
import uk.co.real_logic.fix_gateway.messages.MessageHeaderDecoder;

/**
 * Stores a cache of the last sent sequence number.
 * <p>
 * Each instance is not thread-safe, however, they can share a common
 * off-heap in a single-writer threadsafe manner.
 *
 * Layout:
 *
 * Message Header
 * Known Stream Position
 * Series of LastKnownSequenceNumber records
 */
public final class SequenceNumberIndexDescriptor
{
    static final int HEADER_SIZE = MessageHeaderDecoder.ENCODED_LENGTH;
    static final int RECORD_SIZE = LastKnownSequenceNumberDecoder.BLOCK_LENGTH;

    static final double SEQUENCE_NUMBER_RATIO = 0.9;
    static final double POSITIONS_RATIO = 1 - SEQUENCE_NUMBER_RATIO;

    static AtomicBuffer positionsBuffer(final AtomicBuffer buffer, final int positionsOffset)
    {
        return new UnsafeBuffer(buffer, positionsOffset, buffer.capacity() - positionsOffset);
    }

    public static int sequenceNumberCapacity(final int fileCapacity)
    {
        return (int) (fileCapacity * SEQUENCE_NUMBER_RATIO);
    }
}
