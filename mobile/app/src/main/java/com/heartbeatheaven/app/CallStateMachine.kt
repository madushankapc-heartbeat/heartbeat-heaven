package com.heartbeatheaven.app

/**
 * Single source of truth for call lifecycle transitions.
 *
 * The UI/WebRTC layer must not invent terminal states. Every transition goes
 * through this model so duplicate realtime events and reconnects are harmless.
 */
internal enum class CallState {
    IDLE,
    OUTGOING,
    RINGING,
    CONNECTING,
    CONNECTED,
    ENDING,
    ENDED,
    FAILED
}

internal sealed class CallEvent {
    data object StartOutgoing : CallEvent()
    data object Incoming : CallEvent()
    data object Accept : CallEvent()
    data object Reject : CallEvent()
    data object PeerConnected : CallEvent()
    data object PeerEnded : CallEvent()
    data object LocalEnded : CallEvent()
    data object TransportLost : CallEvent()
    data object TransportRestored : CallEvent()
    data object Error : CallEvent()
}

internal class CallStateMachine(
    initial: CallState = CallState.IDLE,
    private val onStateChanged: (CallState) -> Unit = {}
) {
    var state: CallState = initial
        private set

    fun dispatch(event: CallEvent): CallState {
        val next = when (state) {
            CallState.IDLE -> when (event) {
                CallEvent.StartOutgoing -> CallState.OUTGOING
                CallEvent.Incoming -> CallState.RINGING
                else -> state
            }

            CallState.OUTGOING -> when (event) {
                CallEvent.PeerConnected -> CallState.CONNECTED
                CallEvent.LocalEnded, CallEvent.Reject -> CallState.ENDING
                CallEvent.Error -> CallState.FAILED
                else -> state
            }

            CallState.RINGING -> when (event) {
                CallEvent.Accept -> CallState.CONNECTING
                CallEvent.Reject, CallEvent.LocalEnded, CallEvent.PeerEnded -> CallState.ENDING
                CallEvent.Error -> CallState.FAILED
                else -> state
            }

            CallState.CONNECTING -> when (event) {
                CallEvent.PeerConnected -> CallState.CONNECTED
                CallEvent.LocalEnded, CallEvent.PeerEnded -> CallState.ENDING
                CallEvent.Error -> CallState.FAILED
                else -> state
            }

            CallState.CONNECTED -> when (event) {
                CallEvent.LocalEnded, CallEvent.PeerEnded -> CallState.ENDING
                CallEvent.TransportLost -> CallState.CONNECTING
                CallEvent.Error -> CallState.FAILED
                else -> state
            }

            CallState.ENDING -> when (event) {
                CallEvent.LocalEnded, CallEvent.PeerEnded, CallEvent.Reject -> CallState.ENDED
                CallEvent.Error -> CallState.FAILED
                else -> state
            }

            CallState.ENDED, CallState.FAILED -> when (event) {
                CallEvent.StartOutgoing -> CallState.OUTGOING
                CallEvent.Incoming -> CallState.RINGING
                else -> state
            }
        }

        if (next != state) {
            state = next
            onStateChanged(next)
        }
        return state
    }
}
