package com.heartbeatheaven.app

/** Adaptive call policy used by the WebRTC layer.
 * Keeps audio alive under congestion by progressively reducing video demand.
 */
data class CallNetworkSample(val rttMs: Long, val packetsLost: Long, val packetsSent: Long, val availableBitrateBps: Long)
data class CallMediaProfile(val videoEnabled: Boolean, val maxWidth: Int, val maxHeight: Int, val maxFps: Int, val maxBitrateBps: Int)

class AdaptiveCallController {
    private var poorSamples = 0
    private var goodSamples = 0
    var profile: CallMediaProfile = CallMediaProfile(true, 640, 360, 24, 700_000)
        private set

    fun update(sample: CallNetworkSample): CallMediaProfile {
        val loss = if (sample.packetsSent <= 0) 0.0 else sample.packetsLost.toDouble() / sample.packetsSent.toDouble()
        val poor = sample.rttMs > 350 || loss > 0.08 || (sample.availableBitrateBps in 1 until 450_000)
        val good = sample.rttMs < 180 && loss < 0.02 && sample.availableBitrateBps > 1_200_000
        if (poor) { poorSamples++; goodSamples = 0 } else if (good) { goodSamples++; poorSamples = 0 } else { poorSamples = 0; goodSamples = 0 }

        if (poorSamples >= 2) {
            profile = when {
                profile.maxWidth >= 640 -> CallMediaProfile(true, 480, 270, 15, 350_000)
                profile.maxWidth >= 480 -> CallMediaProfile(true, 320, 180, 12, 220_000)
                else -> CallMediaProfile(false, 0, 0, 0, 0)
            }
            poorSamples = 0
        } else if (goodSamples >= 5) {
            profile = when {
                !profile.videoEnabled -> CallMediaProfile(true, 320, 180, 12, 220_000)
                profile.maxWidth < 480 -> CallMediaProfile(true, 480, 270, 15, 350_000)
                profile.maxWidth < 640 -> CallMediaProfile(true, 640, 360, 24, 700_000)
                else -> profile
            }
            goodSamples = 0
        }
        return profile
    }
}
