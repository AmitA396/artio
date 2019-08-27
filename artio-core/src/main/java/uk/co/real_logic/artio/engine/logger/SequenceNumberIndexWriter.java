/*
 * Copyright 2015-2018 Real Logic Ltd, Adaptive Financial Consulting Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.artio.engine.logger;

    import io.aeron.logbuffer.Header;
    import io.aeron.protocol.DataHeaderFlyweight;
    import org.agrona.CloseHelper;
    import org.agrona.DirectBuffer;
    import org.agrona.ErrorHandler;
    import org.agrona.collections.Long2LongHashMap;
    import org.agrona.concurrent.AtomicBuffer;
    import org.agrona.concurrent.EpochClock;
    import uk.co.real_logic.artio.decoder.HeaderDecoder;
    import uk.co.real_logic.artio.engine.ChecksumFramer;
    import uk.co.real_logic.artio.engine.MappedFile;
    import uk.co.real_logic.artio.messages.*;
    import uk.co.real_logic.artio.session.InternalSession;
    import uk.co.real_logic.artio.session.Session;
    import uk.co.real_logic.artio.session.SilentSessionProxy;
    import uk.co.real_logic.artio.storage.messages.LastKnownSequenceNumberDecoder;
    import uk.co.real_logic.artio.storage.messages.LastKnownSequenceNumberEncoder;
    import uk.co.real_logic.artio.util.AsciiBuffer;
    import uk.co.real_logic.artio.util.MutableAsciiBuffer;

    import java.io.File;

    import static io.aeron.protocol.DataHeaderFlyweight.BEGIN_FLAG;
    import static uk.co.real_logic.artio.engine.SectorFramer.*;
    import static uk.co.real_logic.artio.engine.logger.SequenceNumberIndexDescriptor.*;
    import static uk.co.real_logic.artio.storage.messages.LastKnownSequenceNumberEncoder.SCHEMA_VERSION;

/**
 * Writes updates into an in-memory buffer. This buffer is then flushed down to disk. A passing place
 * file is used to ensure that there's a recoverable option if it fails.
 */
public class SequenceNumberIndexWriter implements Index
{
    private static final boolean RUNNING_ON_WINDOWS = System.getProperty("os.name").startsWith("Windows");

    private static final long MISSING_RECORD = -1L;
    private static final long UNINITIALISED = -1;
    static final int SEQUENCE_NUMBER_OFFSET = 8;

    private final MessageHeaderDecoder messageHeader = new MessageHeaderDecoder();
    private final FixMessageDecoder messageFrame = new FixMessageDecoder();
    private final ResetSequenceNumberDecoder resetSequenceNumber = new ResetSequenceNumberDecoder();
    private final HeaderDecoder fixHeader = new HeaderDecoder();

    private final AsciiBuffer asciiBuffer = new MutableAsciiBuffer();
    private final MessageHeaderDecoder fileHeaderDecoder = new MessageHeaderDecoder();
    private final MessageHeaderEncoder fileHeaderEncoder = new MessageHeaderEncoder();
    private final LastKnownSequenceNumberEncoder lastKnownEncoder = new LastKnownSequenceNumberEncoder();
    private final LastKnownSequenceNumberDecoder lastKnownDecoder = new LastKnownSequenceNumberDecoder();
    private final Long2LongHashMap recordOffsets = new Long2LongHashMap(MISSING_RECORD);

    private final ChecksumFramer checksumFramer;
    private final AtomicBuffer inMemoryBuffer;
    private final ErrorHandler errorHandler;
    private final File indexPath;
    private final File writablePath;
    private final File passingPlacePath;
    private final int fileCapacity;
    private final RecordingIdLookup recordingIdLookup;
    private final int streamId;
    private final int indexedPositionsOffset;
    private final IndexedPositionWriter positions;

    private MappedFile writableFile;
    private MappedFile indexFile;
    private long nextRollPosition = UNINITIALISED;

    private final EpochClock clock;
    private final long indexFileStateFlushTimeoutInMs;
    private long lastUpdatedFileTimeInMs;
    private boolean hasSavedRecordSinceFileUpdate = false;

    public SequenceNumberIndexWriter(
        final AtomicBuffer inMemoryBuffer,
        final MappedFile indexFile,
        final ErrorHandler errorHandler,
        final int streamId,
        final RecordingIdLookup recordingIdLookup,
        final long indexFileStateFlushTimeoutInMs,
        final EpochClock clock)
    {
        this.inMemoryBuffer = inMemoryBuffer;
        this.indexFile = indexFile;
        this.errorHandler = errorHandler;
        this.streamId = streamId;
        this.fileCapacity = indexFile.buffer().capacity();
        this.recordingIdLookup = recordingIdLookup;
        this.indexFileStateFlushTimeoutInMs = indexFileStateFlushTimeoutInMs;
        this.clock = clock;

        final String indexFilePath = indexFile.file().getAbsolutePath();
        indexPath = indexFile.file();
        writablePath = writablePath(indexFilePath);
        passingPlacePath = passingPath(indexFilePath);
        writableFile = MappedFile.map(writablePath, fileCapacity);

        // TODO: Fsync parent directory
        indexedPositionsOffset = positionTableOffset(fileCapacity);
        checksumFramer = new ChecksumFramer(
            inMemoryBuffer, indexedPositionsOffset, errorHandler, 0, "SequenceNumberIndex");
        try
        {
            initialiseBuffer();
            positions = new IndexedPositionWriter(
                positionsBuffer(inMemoryBuffer, indexedPositionsOffset),
                errorHandler,
                indexedPositionsOffset,
                "SequenceNumberIndex");
        }
        catch (final Exception e)
        {
            CloseHelper.close(writableFile);
            indexFile.close();
            throw e;
        }
    }

    public void onFragment(
        final DirectBuffer buffer,
        final int srcOffset,
        final int length,
        final Header header)
    {
        final int streamId = header.streamId();
        final long endPosition = header.position();
        final int aeronSessionId = header.sessionId();

        if (streamId != this.streamId)
        {
            return;
        }

        if ((header.flags() & BEGIN_FLAG) != BEGIN_FLAG)
        {
            return;
        }

        int offset = srcOffset;
        messageHeader.wrap(buffer, offset);

        offset += messageHeader.encodedLength();
        final int actingBlockLength = messageHeader.blockLength();
        final int version = messageHeader.version();

        switch (messageHeader.templateId())
        {
            case FixMessageEncoder.TEMPLATE_ID:
            {
                messageFrame.wrap(buffer, offset, actingBlockLength, version);

                if (messageFrame.status() != MessageStatus.OK)
                {
                    return;
                }

                offset += actingBlockLength + 2;

                asciiBuffer.wrap(buffer);
                fixHeader.decode(asciiBuffer, offset, messageFrame.bodyLength());

                final int msgSeqNum = fixHeader.msgSeqNum();
                final long sessionId = messageFrame.session();

                saveRecord(msgSeqNum, sessionId);
                break;
            }

            case ResetSessionIdsDecoder.TEMPLATE_ID:
            {
                resetSequenceNumbers();
                break;
            }

            case ResetSequenceNumberDecoder.TEMPLATE_ID:
            {
                resetSequenceNumber.wrap(buffer, offset, actingBlockLength, version);
                saveRecord(0, resetSequenceNumber.session());
            }
        }

        checkTermRoll(buffer, srcOffset, endPosition, length);

        final long recordingId = recordingIdLookup.getRecordingId(aeronSessionId);
        positions.indexedUpTo(aeronSessionId, recordingId, endPosition);
    }

    @Override
    public int doWork()
    {
        if (hasSavedRecordSinceFileUpdate)
        {
            final long requiredUpdateTimeInMs = lastUpdatedFileTimeInMs + indexFileStateFlushTimeoutInMs;
            if (requiredUpdateTimeInMs < clock.time())
            {
                updateFile();
                return 1;
            }
        }

        return 0;
    }

    void resetSequenceNumbers()
    {
        inMemoryBuffer.setMemory(0, indexedPositionsOffset, (byte)0);
        initialiseBlankBuffer();
    }

    private void checkTermRoll(final DirectBuffer buffer, final int offset, final long endPosition, final int length)
    {
        final long termBufferLength = buffer.capacity();
        if (nextRollPosition == UNINITIALISED)
        {
            final long startPosition = endPosition - (length + DataHeaderFlyweight.HEADER_LENGTH);
            nextRollPosition = startPosition + termBufferLength - offset;
        }
        else if (endPosition > nextRollPosition)
        {
            nextRollPosition += termBufferLength;
            updateFile();
        }
    }

    private void updateFile()
    {
        checksumFramer.updateChecksums();
        positions.updateChecksums();
        saveFile();
        flipFiles();
        hasSavedRecordSinceFileUpdate = false;
        lastUpdatedFileTimeInMs = clock.time();
    }

    private void saveFile()
    {
        writableFile.buffer().putBytes(0, inMemoryBuffer, 0, fileCapacity);
        writableFile.force();
    }

    private void flipFiles()
    {
        if (RUNNING_ON_WINDOWS)
        {
            writableFile.close();
            indexFile.close();
        }

        final boolean flipsFiles = rename(indexPath, passingPlacePath) &&
            rename(writablePath, indexPath) &&
            rename(passingPlacePath, writablePath);

        if (RUNNING_ON_WINDOWS)
        {
            // remapping flips the files here due to the rename
            writableFile.map();
            indexFile.map();
        }
        else if (flipsFiles)
        {
            final MappedFile file = this.writableFile;
            writableFile = indexFile;
            indexFile = file;
        }
    }

    private boolean rename(final File src, final File dest)
    {
        if (src.renameTo(dest))
        {
            return true;
        }

        errorHandler.onError(new IllegalStateException("unable to rename " + src + " to " + dest));
        return false;
    }

    public File passingPlace()
    {
        return passingPlacePath;
    }

    public boolean isOpen()
    {
        return writableFile.isOpen();
    }

    public void close()
    {
        try
        {
            if (isOpen() && hasSavedRecordSinceFileUpdate)
            {
                updateFile();
            }
        }
        finally
        {
            indexFile.close();
            writableFile.close();
        }
    }

    public void readLastPosition(final IndexedPositionConsumer consumer)
    {
        // Inefficient, but only run once on startup, so not a big deal.
        new IndexedPositionReader(positions.buffer()).readLastPosition(consumer);
    }

    private void saveRecord(final int newSequenceNumber, final long sessionId)
    {
        int position = (int)recordOffsets.get(sessionId);
        if (position == MISSING_RECORD)
        {
            position = SequenceNumberIndexDescriptor.HEADER_SIZE;
            while (true)
            {
                position = checksumFramer.claim(position, RECORD_SIZE);
                if (position == OUT_OF_SPACE)
                {
                    errorHandler.onError(new IllegalStateException(
                        "Sequence Number Index out of space, can't claim slot for " + sessionId));
                    return;
                }

                lastKnownDecoder.wrap(inMemoryBuffer, position, RECORD_SIZE, SCHEMA_VERSION);
                if (lastKnownDecoder.sequenceNumber() == 0)
                {
                    createNewRecord(newSequenceNumber, sessionId, position);
                    hasSavedRecordSinceFileUpdate = true;
                    return;
                }
                else if (lastKnownDecoder.sessionId() == sessionId)
                {
                    updateSequenceNumber(position, newSequenceNumber);
                    hasSavedRecordSinceFileUpdate = true;
                    return;
                }

                position += RECORD_SIZE;
            }
        }
        else
        {
            updateSequenceNumber(position, newSequenceNumber);
            hasSavedRecordSinceFileUpdate = true;
        }
    }

    private void createNewRecord(
        final int sequenceNumber,
        final long sessionId,
        final int position)
    {
        recordOffsets.put(sessionId, position);
        lastKnownEncoder
            .wrap(inMemoryBuffer, position)
            .sessionId(sessionId);
        updateSequenceNumber(position, sequenceNumber);
    }

    private void initialiseBuffer()
    {
        validateBufferSizes();
        final AtomicBuffer fileBuffer = indexFile.buffer();
        if (fileHasBeenInitialized(fileBuffer))
        {
            readFile(fileBuffer);
        }
        else if (passingPlacePath.exists())
        {
            if (passingPlacePath.renameTo(indexPath))
            {
                // TODO: fsync parent directory
                indexFile.remap();
                initialiseBuffer();
            }
            else
            {
                errorHandler.onError(new IllegalStateException(String.format(
                    "Unable to recover index file from %s to %s due to rename failure",
                    passingPlacePath,
                    indexPath)));
            }
        }
        else
        {
            initialiseBlankBuffer();
        }
    }

    private void initialiseBlankBuffer()
    {
        LoggerUtil.initialiseBuffer(
            inMemoryBuffer,
            fileHeaderEncoder,
            fileHeaderDecoder,
            lastKnownEncoder.sbeSchemaId(),
            lastKnownEncoder.sbeTemplateId(),
            lastKnownEncoder.sbeSchemaVersion(),
            lastKnownEncoder.sbeBlockLength(),
            errorHandler);
    }

    private boolean fileHasBeenInitialized(final AtomicBuffer fileBuffer)
    {
        return fileBuffer.getShort(0) != 0 || fileBuffer.getInt(FIRST_CHECKSUM_LOCATION) != 0;
    }

    private void validateBufferSizes()
    {
        final int inMemoryCapacity = inMemoryBuffer.capacity();

        if (fileCapacity != inMemoryCapacity)
        {
            throw new IllegalStateException(String.format(
                "In memory buffer and disk file don't have the same size, disk: %d, memory: %d",
                fileCapacity,
                inMemoryCapacity
            ));
        }

        if (fileCapacity < SECTOR_SIZE)
        {
            throw new IllegalStateException(String.format(
                "Cannot create sequence number of size < 1 sector: %d",
                fileCapacity));
        }
    }

    private void readFile(final AtomicBuffer fileBuffer)
    {
        loadBuffer(fileBuffer);
        checksumFramer.validateCheckSums();
    }

    private void loadBuffer(final AtomicBuffer fileBuffer)
    {
        inMemoryBuffer.putBytes(0, fileBuffer, 0, fileCapacity);
    }

    private void updateSequenceNumber(
        final int recordOffset,
        final int value)
    {
        inMemoryBuffer.putIntOrdered(recordOffset + SEQUENCE_NUMBER_OFFSET, value);
    }

    class SessionValidator
    {
        final SilentSessionProxy silentSessionProxy = new SilentSessionProxy();
        final InternalSession session;

        long recordOffset = 0;

        SessionValidator()
        {
            session = new InternalSession();
        }
    }
}
