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
package uk.co.real_logic.fix_gateway.session;

import io.aeron.logbuffer.ControlledFragmentHandler.Action;
import org.junit.Test;

import static io.aeron.Publication.BACK_PRESSURED;
import static io.aeron.logbuffer.ControlledFragmentHandler.Action.ABORT;
import static io.aeron.logbuffer.ControlledFragmentHandler.Action.CONTINUE;
import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.*;
import static uk.co.real_logic.fix_gateway.CommonConfiguration.DEFAULT_SESSION_BUFFER_SIZE;
import static uk.co.real_logic.fix_gateway.fields.RejectReason.SENDINGTIME_ACCURACY_PROBLEM;
import static uk.co.real_logic.fix_gateway.messages.SessionState.*;
import static uk.co.real_logic.fix_gateway.session.Session.UNKNOWN;

public class AcceptorSessionTest extends AbstractSessionTest
{
    private AcceptorSession session = newAcceptorSession();

    private AcceptorSession newAcceptorSession()
    {
        return new AcceptorSession(
            HEARTBEAT_INTERVAL, CONNECTION_ID, fakeClock, mockProxy, mockPublication, null,
            SENDING_TIME_WINDOW, mockReceivedMsgSeqNo,
            mockSentMsgSeqNo, LIBRARY_ID, DEFAULT_SESSION_BUFFER_SIZE, 1, CONNECTED);
    }

    @Test
    public void shouldInitiallyBeConnected()
    {
        assertEquals(CONNECTED, session.state());
    }

    @Test
    public void shouldBeActivatedBySuccessfulLogin()
    {
        onLogon(1);

        verifySessionSetup();
        verifyLogon();
        verifyNoFurtherMessages();
        assertState(ACTIVE);
    }

    @Test
    public void shouldRequestResendIfHighSeqNoLogon()
    {
        onLogon(3);

        verifySessionSetup();
        verifyLogon();
        verify(mockProxy).resendRequest(2, 1, 0);
        verifyNoFurtherMessages();
        assertState(AWAITING_RESEND);
    }

    @Test
    public void shouldLogoutIfFirstMessageNotALogon()
    {
        onMessage(1);

        verifyDisconnect(1);
        verifyNoFurtherMessages();
    }

    // See http://www.fixtradingcommunity.org/pg/discussions/topicpost/164720/fix-4x-sessionlevel-protocol-tests
    // 1d_InvalidLogonBadSendingTime.def
    @Test
    public void shouldDisconnectIfInvalidSendingTimeAtLogon()
    {
        logonWithInvalidSendingTime(CONTINUE);

        verifySendingTimeAccuracyProblem(1);
    }

    @Test
    public void shouldDisconnectIfInvalidSendingTimeAtLogonWhenBackPressured()
    {
        when(mockProxy.rejectWhilstNotLoggedOn(anyInt(), any())).thenReturn(BACK_PRESSURED, POSITION);

        logonWithInvalidSendingTime(ABORT);

        logonWithInvalidSendingTime(CONTINUE);

        verifySendingTimeAccuracyProblem(2);
    }

    private void verifySendingTimeAccuracyProblem(final int times)
    {
        verify(mockProxy, times(times)).rejectWhilstNotLoggedOn(1, SENDINGTIME_ACCURACY_PROBLEM);
    }

    private void logonWithInvalidSendingTime(final Action expectedAction)
    {
        fakeClock.advanceMilliSeconds(2 * SENDING_TIME_WINDOW);

        final Action action = session().onLogon(
            HEARTBEAT_INTERVAL, 1, SESSION_ID, SESSION_KEY, 1, UNKNOWN, null, null, false);

        assertEquals(expectedAction, action);
    }

    @Test
    public void shouldValidateSendingTimeNotTooLate()
    {
        onLogon(1);

        messageWithWeirdTime(sendingTime() + TWO_MINUTES);

        verifySendingTimeProblem();
        verifyLogout();
        verifyDisconnect(1);
    }

    @Test
    public void shouldValidateSendingTimeNotTooEarly()
    {
        onLogon(1);

        messageWithWeirdTime(sendingTime() - TWO_MINUTES);

        verifySendingTimeProblem();
        verifyLogout();
        verifyDisconnect(1);
    }

    protected void readyForLogon()
    {
        // Deliberately blank
    }

    protected Session session()
    {
        return session;
    }

    private void verifyLogon()
    {
        verify(mockProxy).logon(HEARTBEAT_INTERVAL, 1, null, null);
    }

    private void verifySessionSetup()
    {
        verify(mockProxy).setupSession(SESSION_ID, SESSION_KEY);
    }
}
