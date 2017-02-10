/*
 * Copyright 2015-2016 Real Logic Ltd.
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
package uk.co.real_logic.fix_gateway.server;

import io.aeron.logbuffer.ControlledFragmentHandler.Action;
import org.agrona.DirectBuffer;
import uk.co.real_logic.fix_gateway.builder.Printer;
import uk.co.real_logic.fix_gateway.decoder.PrinterImpl;
import uk.co.real_logic.fix_gateway.library.SessionHandler;
import uk.co.real_logic.fix_gateway.messages.DisconnectReason;
import uk.co.real_logic.fix_gateway.session.Session;
import uk.co.real_logic.fix_gateway.util.AsciiBuffer;
import uk.co.real_logic.fix_gateway.util.MutableAsciiBuffer;

import static io.aeron.logbuffer.ControlledFragmentHandler.Action.CONTINUE;

public class SampleSessionHandler implements SessionHandler
{

    private final AsciiBuffer string = new MutableAsciiBuffer();
    private final Printer printer = new PrinterImpl();

    public SampleSessionHandler(final Session session)
    {
    }

    public Action onMessage(
        final DirectBuffer buffer,
        final int offset,
        final int length,
        final int libraryId,
        final long sessionId,
        final int sequenceIndex,
        final int messageType,
        final long timestampInNs,
        final long position)
    {
        string.wrap(buffer);
        System.out.printf("%d -> %s\n", sessionId, printer.toString(string, offset, length, messageType));

        return CONTINUE;
    }

    public void onTimeout(final int libraryId, final long sessionId)
    {
    }

    public void onSlowStatus(final int libraryId, final long sessionId, final boolean hasBecomeSlow)
    {
    }

    public Action onDisconnect(final int libraryId, final long sessionId, final DisconnectReason reason)
    {
        System.out.printf("%d Disconnected: %s\n", sessionId, reason);
        return CONTINUE;
    }
}
