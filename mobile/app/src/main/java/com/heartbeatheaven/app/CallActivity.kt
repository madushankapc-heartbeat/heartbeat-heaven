package com.heartbeatheaven.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Rational
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.graphics.drawable.GradientDrawable
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import org.webrtc.*
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule

class CallActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var status: TextView
    private lateinit var localView: SurfaceViewRenderer
    private lateinit var remoteView: SurfaceViewRenderer
    private lateinit var endButton: TextView
    private lateinit var videoButton: TextView
    private lateinit var speakerButton: TextView
    private lateinit var muteButton: TextView
    private lateinit var controlsPanel: LinearLayout
    private lateinit var statusPanel: TextView
    private var controlsHideJob: Job? = null
    private var controlsVisible = true
    private var callAnswered = false
    private var isMuted = false
    private var isSpeakerOn = false
    private var isVideoEnabled = false
    private var videoSender: RtpSender? = null
    private var lastRemoteOfferSdp: String? = null
    private var lastRemoteAnswerSdp: String? = null
    private var upgradeVideoPending = false
    private var peer: PeerConnection? = null
    private var factory: PeerConnectionFactory? = null
    private var audioDeviceModule: AudioDeviceModule? = null
    private var capturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var audioTrack: AudioTrack? = null
    private var localStream: MediaStream? = null
    private var call: CallSession? = null
    private var isCaller = false
    private var remoteIceCount = 0
    private var running = true
    private var cleanedUp = false
    private var toneGenerator: ToneGenerator? = null
    private var toneJob: Job? = null
    private var ringStartedAt = 0L
    private val adaptiveController = AdaptiveCallController()
    private var statsJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val auth by lazy { AuthApi(applicationContext) }
    private val callApi by lazy { CallApi(applicationContext, auth) }
    private val egl by lazy { EglBase.create() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!hasPermissions()) {
            ActivityCompat.requestPermissions(this, neededPermissions(), 7002)
            return
        }
        setupUi()
        initializeCall()
    }

    private fun neededPermissions(): Array<String> {
        val list = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (intent.getStringExtra("call_type") == "video") list += Manifest.permission.CAMERA
        return list.toTypedArray()
    }
    private fun hasPermissions() = neededPermissions().all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7002 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            setupUi()
            initializeCall()
        } else if (requestCode == 7003 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            enableVideoAndRenegotiate()
        } else finish()
    }

    private fun setupUi() {
        root = FrameLayout(this)
        root.setBackgroundColor(0xFF101114.toInt())

        remoteView = SurfaceViewRenderer(this).apply {
            init(egl.eglBaseContext, null)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            setEnableHardwareScaler(true)
        }
        root.addView(remoteView, FrameLayout.LayoutParams(-1, -1))

        localView = SurfaceViewRenderer(this).apply {
            init(egl.eglBaseContext, null)
            setZOrderMediaOverlay(true)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            setEnableHardwareScaler(true)
        }
        root.addView(localView, FrameLayout.LayoutParams(300, 400, Gravity.TOP or Gravity.END).apply {
            topMargin = 28
            rightMargin = 16
        })

        statusPanel = TextView(this).apply {
            text = "Connecting…"
            textSize = 17f
            setTextColor(-1)
            gravity = Gravity.CENTER
            setPadding(20, 10, 20, 10)
        }
        root.addView(statusPanel, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
            topMargin = 12
        })

        controlsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), dp(10))
            setBackgroundColor(0xE615171A.toInt())
        }

        fun control(icon: String, label: String, description: String, onClick: () -> Unit): TextView =
            TextView(this).apply {
                text = "$icon\n$label"
                contentDescription = description
                textSize = 12f
                setTextColor(0xFFFFFFFF.toInt())
                gravity = Gravity.CENTER
                setPadding(dp(2), dp(5), dp(2), dp(5))
                isClickable = true
                isFocusable = true
                setBackground(GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(0xFF25282D.toInt())
                })
                setOnClickListener { onClick() }
                layoutParams = LinearLayout.LayoutParams(0, dp(82)).apply {
                    weight = 1f
                    marginStart = dp(3)
                    marginEnd = dp(3)
                }
            }

        videoButton = control("🎥", "Video", "Enable or disable video", ::toggleVideoTrack)
        speakerButton = control("🔊", "Speaker", "Toggle speaker", ::toggleSpeaker)
        muteButton = control("🎙", "Mute", "Toggle microphone", ::toggleMute)
        val chatButton = control("💬", "Chat", "Open chat", ::minimizeToChat)

        controlsPanel.addView(videoButton)
        controlsPanel.addView(speakerButton)
        controlsPanel.addView(muteButton)
        controlsPanel.addView(chatButton)

        endButton = control("🔴", "End", "Answer or end call") {
            if (!callAnswered && !isCaller) answerIncoming()
            else finishCall(if (isCaller && !callAnswered) "cancelled" else "ended")
        }
        controlsPanel.addView(endButton)
        root.addView(controlsPanel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            bottomMargin = dp(18)
            leftMargin = dp(8)
            rightMargin = dp(8)
        })
        updateControlLabels()

        val video = intent.getStringExtra("call_type") == "video"
        if (video) {
            remoteView.setOnClickListener { toggleControls() }
        }

        setContentView(root)
    }

    private fun setIncomingControls() {
        callAnswered = false
        endButton.text = "📞\nAnswer"
        endButton.setTextColor(0xFFFFFFFF.toInt())
        endButton.setBackgroundColor(0xFF2E7D32.toInt())
        showControls(false)
    }

    private fun setActiveControls() {
        callAnswered = true
        endButton.text = "🔴\nEnd"
        endButton.setTextColor(0xFFFFFFFF.toInt())
        endButton.setBackgroundColor(0xFFE91E3B.toInt())
        showControls(true)
        if (intent.getStringExtra("call_type") == "video") scheduleHideControls()
    }

    private fun setOutgoingControls() {
        callAnswered = false
        endButton.text = "🔴\nEnd"
        endButton.setTextColor(0xFFFFFFFF.toInt())
        endButton.setBackgroundColor(0xFFE91E3B.toInt())
        showControls(true)
    }

    private fun showControls(scheduleHide: Boolean) {
        controlsVisible = true
        controlsPanel.visibility = android.view.View.VISIBLE
        if (scheduleHide) scheduleHideControls()
    }

    private fun toggleControls() {
        if (controlsVisible) {
            controlsHideJob?.cancel()
            controlsVisible = false
            controlsPanel.visibility = android.view.View.GONE
        } else {
            showControls(true)
        }
    }

    private fun scheduleHideControls() {
        controlsHideJob?.cancel()
        controlsHideJob = scope.launch {
            delay(4000)
            if (running && intent.getStringExtra("call_type") == "video") {
                controlsVisible = false
                controlsPanel.visibility = android.view.View.GONE
            }
        }
    }

    private fun toggleMute() {
        val track = audioTrack ?: peer?.senders?.firstOrNull { it.track() is AudioTrack }?.track() as? AudioTrack
        if (track == null) {
            statusPanel.text = "Microphone is not ready"
            return
        }
        isMuted = !isMuted
        track.setEnabled(!isMuted)
        updateControlLabels()
    }

    private fun toggleSpeaker() {
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        isSpeakerOn = !isSpeakerOn
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            val target = audio.availableCommunicationDevices.firstOrNull {
                if (isSpeakerOn) it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                else it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            }
            if (target != null) audio.setCommunicationDevice(target) else audio.clearCommunicationDevice()
        }
        audio.isSpeakerphoneOn = isSpeakerOn
        updateControlLabels()
    }

    private fun toggleVideoTrack() {
        val sender = videoSender ?: peer?.senders?.firstOrNull { it.track() is VideoTrack }
        if (sender?.track() is VideoTrack) {
            val track = sender.track() as VideoTrack
            isVideoEnabled = !isVideoEnabled
            track.setEnabled(isVideoEnabled)
            updateControlLabels()
            return
        }
        if (!isCaller) {
            statusPanel.text = "Video can be enabled by the caller"
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            upgradeVideoPending = true
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 7003)
            return
        }
        enableVideoAndRenegotiate()
    }

    private fun enableVideoAndRenegotiate() {
        if (videoSender != null || peer == null || factory == null || call == null) return
        upgradeVideoPending = false
        try {
            capturer = createCameraCapturer()
            videoSource = factory!!.createVideoSource(capturer!!.isScreencast)
            capturer!!.initialize(SurfaceTextureHelper.create("CallCameraUpgrade", egl.eglBaseContext), this, videoSource!!.capturerObserver)
            capturer!!.startCapture(640, 360, 24)
            val track = factory!!.createVideoTrack("video_" + call!!.id, videoSource)
            track.addSink(localView)
            localView.visibility = View.VISIBLE
            videoSender = peer!!.addTrack(track, listOf("stream_" + call!!.id))
            isVideoEnabled = true
            updateControlLabels()
            statusPanel.text = "Switching to video…"
            peer!!.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    peer!!.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onSetSuccess() {
                            scope.launch(Dispatchers.IO) { runCatching { callApi.publishOffer(call!!.id, sdp.description) } }
                        }
                        override fun onCreateFailure(e: String?) {}
                        override fun onSetFailure(e: String?) {}
                    }, sdp)
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(e: String?) { statusPanel.text = e ?: "Video offer failed" }
                override fun onSetFailure(e: String?) { statusPanel.text = e ?: "Video offer failed" }
            }, MediaConstraints())
        } catch (e: Throwable) {
            statusPanel.text = e.message ?: "Could not enable video"
        }
    }

    private fun updateControlLabels() {
        if (!::videoButton.isInitialized || !::speakerButton.isInitialized || !::muteButton.isInitialized || !::endButton.isInitialized) return
        videoButton.text = if (isVideoEnabled) "📹\nVideo On" else "🎥\nVideo"
        speakerButton.text = if (isSpeakerOn) "🔊\nSpeaker On" else "🔈\nSpeaker"
        muteButton.text = if (isMuted) "🔇\nUnmute" else "🎙\nMute"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun initializeCall() {
        val callId = intent.getStringExtra("call_id")
        val outgoingUser = intent.getStringExtra("callee_id")
        val type = intent.getStringExtra("call_type") ?: "voice"
        scope.launch {
            runCatching {
                if (!callId.isNullOrBlank()) {
                    call = withContext(Dispatchers.IO) { callApi.get(callId) } ?: error("Call not found")
                    isCaller = call!!.callerId == auth.currentSession()?.profile?.id
                } else if (!outgoingUser.isNullOrBlank()) {
                    call = withContext(Dispatchers.IO) { callApi.create(outgoingUser, type) }
                    isCaller = true
                } else error("Missing call information")
                setupPeer(type == "video")
                if (isCaller) {
                    setOutgoingControls()
                    createAndPublishOffer()
                } else {
                    setIncomingControls()
                    statusPanel.text = "Incoming call"
                    if (intent.getBooleanExtra("answer_now", false)) {
                        answerIncoming()
                    }
                }
                startSignalLoop()
            }.onFailure {
                statusPanel.text = it.message ?: "Could not start call"
                endButton.text = "Close"
            }
        }
    }

    private suspend fun setupPeer(video: Boolean) {
        // Put Android into communication mode BEFORE WebRTC creates its audio device.
        // This makes the microphone use the call-oriented audio path on devices where
        // the normal media/voice path is significantly quieter.
        configureCallAudio()

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(applicationContext).setEnableInternalTracer(false).createInitializationOptions()
        )
        audioDeviceModule = JavaAudioDeviceModule.builder(applicationContext)
            // Let WebRTC's audio processing handle AEC/NS instead of relying on
            // device-specific hardware effects that can attenuate speech too much.
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .createAudioDeviceModule()
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule!!)
            .createPeerConnectionFactory()

        // Explicitly enable WebRTC voice processing for call audio.
        // Hardware AEC/NS are disabled above because device-specific effects can
        // attenuate speech. These constraints keep WebRTC's own APM responsible
        // for echo cancellation, noise suppression and automatic mic gain.
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        }
        val audioSource = factory!!.createAudioSource(audioConstraints)
        audioTrack = factory!!.createAudioTrack("audio_" + call!!.id, audioSource)

        configureCallAudio()
        val fallbackIceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )
        val (relayServers, relayOnly) = runCatching { withContext(Dispatchers.IO) { callApi.fetchIceServers() } }
            .getOrElse { Pair(emptyList(), false) }
        if (relayOnly && relayServers.isNotEmpty()) {
            createPeerConnectionWithServers(relayServers, true, video)
        } else {
            createPeerConnectionWithServers(fallbackIceServers, false, video)
        }
    }

    private fun createPeerConnectionWithServers(
        iceServers: List<PeerConnection.IceServer>,
        relayOnly: Boolean,
        video: Boolean
    ) {
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            if (relayOnly) iceTransportsType = PeerConnection.IceTransportsType.RELAY
        }
        peer = factory!!.createPeerConnection(
            config,
            object : PeerConnection.Observer {
                override fun onIceCandidate(c: IceCandidate) {
                    val j = JSONObject().put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex).put("candidate", c.sdp)
                    scope.launch(Dispatchers.IO) {
                        runCatching {
                            if (isCaller) callApi.addCallerIce(call!!.id, j) else callApi.addCalleeIce(call!!.id, j)
                        }
                    }
                }
                override fun onIceCandidateError(event: IceCandidateErrorEvent) {}
                override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
                override fun onSignalingChange(s: PeerConnection.SignalingState) {}
                override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {
                    runOnUiThread { statusPanel.text = when (s) {
                        PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> "Connected"
                        PeerConnection.IceConnectionState.CHECKING -> "Connecting…"
                        PeerConnection.IceConnectionState.DISCONNECTED -> "Network reconnecting…"
                        PeerConnection.IceConnectionState.FAILED -> "Connection failed"
                        else -> statusPanel.text
                    }}
                }
                override fun onStandardizedIceConnectionChange(s: PeerConnection.IceConnectionState) {}
                override fun onConnectionChange(s: PeerConnection.PeerConnectionState) {
                    if (s == PeerConnection.PeerConnectionState.CONNECTED) runOnUiThread { stopCallTone(); statusPanel.text = "Connected" }
                }
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
                override fun onAddStream(stream: MediaStream) {
                    runOnUiThread { stream.videoTracks.firstOrNull()?.addSink(remoteView) }
                }
                override fun onRemoveStream(stream: MediaStream) {}
                override fun onRemoveTrack(receiver: RtpReceiver) {}
                override fun onTrack(transceiver: RtpTransceiver) {
                    val track = transceiver.receiver.track()
                    if (track is VideoTrack) {
                        runOnUiThread { track.addSink(remoteView); remoteView.visibility = View.VISIBLE }
                    }
                }
                override fun onDataChannel(c: DataChannel) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
                    receiver.track()?.let { track ->
                        if (track is VideoTrack) runOnUiThread { track.addSink(remoteView) }
                    }
                }
                override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) {}
            }
        ) ?: error("Could not create WebRTC connection")

        peer!!.addTrack(audioTrack!!, listOf("stream_" + call!!.id))
        if (video) {
            // Camera initialization must never be allowed to take down the call Activity.
            // Some devices/ROMs can reject a Camera2 capturer at runtime. Fall back to
            // Camera1 and keep the call alive so signaling can still complete.
            runCatching {
                capturer = createCameraCapturer()
                videoSource = factory!!.createVideoSource(capturer!!.isScreencast)
                capturer!!.initialize(
                    SurfaceTextureHelper.create("CallCamera", egl.eglBaseContext),
                    this,
                    videoSource!!.capturerObserver
                )
                capturer!!.startCapture(640, 360, 24)
                val videoTrack = factory!!.createVideoTrack("video_" + call!!.id, videoSource)
                videoTrack.addSink(localView)
                videoSender = peer!!.addTrack(videoTrack, listOf("stream_" + call!!.id))
                isVideoEnabled = true
                localView.visibility = View.VISIBLE
                updateControlLabels()
            }.onFailure {
                runCatching { capturer?.stopCapture() }
                capturer?.dispose()
                capturer = null
                videoSource?.dispose()
                videoSource = null
                videoSender = null
                isVideoEnabled = false
                localView.visibility = View.GONE
                statusPanel.text = "Camera unavailable — continuing call"
            }
        } else {
            localView.visibility = View.GONE
        }
        if (video) startAdaptiveStats()
    }

    private fun startAdaptiveStats() {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (isActive && running) {
                delay(2000)
                val connection = peer ?: continue
                connection.getStats(object : RTCStatsCollectorCallback {
                    override fun onStatsDelivered(report: RTCStatsReport) {
                        var rttMs = 0L
                        var availableBps = 0L
                        var lost = 0L
                        var received = 0L
                        report.statsMap.values.forEach { stat ->
                            val m = stat.members
                            when (stat.type) {
                                "candidate-pair" -> {
                                    val rtt = (m["currentRoundTripTime"] as? Number)?.toDouble()
                                    val bitrate = (m["availableOutgoingBitrate"] as? Number)?.toLong()
                                    if (rtt != null && rtt > 0) rttMs = (rtt * 1000.0).toLong()
                                    if (bitrate != null && bitrate > availableBps) availableBps = bitrate
                                }
                                "remote-inbound-rtp" -> {
                                    lost += (m["packetsLost"] as? Number)?.toLong() ?: 0L
                                    received += (m["packetsReceived"] as? Number)?.toLong() ?: 0L
                                }
                            }
                        }
                        val lossPercent = if (lost + received > 0) lost.toDouble() * 100.0 / (lost + received).toDouble() else 0.0
                        applyMediaProfile(adaptiveController.update(CallNetworkSample(rttMs, lossPercent, availableBps)))
                    }
                })
            }
        }
    }

    private fun applyMediaProfile(profile: CallMediaProfile) {
        val connection = peer ?: return
        connection.senders.filter { it.track() is VideoTrack }.forEach { sender ->
            val params = sender.parameters
            params.encodings.forEach { encoding ->
                encoding.maxBitrateBps = profile.maxBitrateBps.takeIf { profile.videoEnabled }
                encoding.maxFramerate = profile.maxFps.takeIf { profile.videoEnabled }
                encoding.scaleResolutionDownBy = if (profile.videoEnabled) {
                    when {
                        profile.maxWidth >= 640 -> 1.0
                        profile.maxWidth >= 480 -> 640.0 / 480.0
                        else -> 640.0 / 320.0
                    }
                } else 2.0
                encoding.active = profile.videoEnabled
            }
            sender.parameters = params
        }
        if (profile.videoEnabled) {
            capturer?.let {
                runCatching { it.stopCapture() }
                runCatching { it.startCapture(profile.maxWidth, profile.maxHeight, profile.maxFps) }
            }
        }
    }

    private fun configureCallAudio() {
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        isSpeakerOn = false
        if (android.os.Build.VERSION.SDK_INT >= 31) audio.clearCommunicationDevice()
        audio.isSpeakerphoneOn = false
        // Keep the default earpiece route while Android treats this as a
        // two-way communication stream.
        updateControlLabels()
    }

    private fun createCameraCapturer(): VideoCapturer {
        // Prefer Camera2, but fall back to Camera1 on devices where Camera2/WebRTC
        // cannot open the selected camera. This is deliberately defensive for calls.
        runCatching {
            val enumerator = Camera2Enumerator(this)
            val name = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
                ?: enumerator.deviceNames.firstOrNull()
                ?: error("No camera available")
            enumerator.createCapturer(name, null)
        }.getOrNull()?.let { return it }

        val enumerator = Camera1Enumerator(true)
        val name = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.firstOrNull()
            ?: error("No camera available")
        return enumerator.createCapturer(name, null) ?: error("Could not open camera")
    }

    private suspend fun createAndPublishOffer() {
        withContext(Dispatchers.Main) {
            peer!!.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    peer!!.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onSetSuccess() {
                            scope.launch(Dispatchers.IO) { callApi.publishOffer(call!!.id, sdp.description) }
                        }
                        override fun onCreateFailure(e: String?) {}
                        override fun onSetFailure(e: String?) {}
                    }, sdp)
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(e: String?) { runOnUiThread { statusPanel.text = e ?: "Offer failed" } }
                override fun onSetFailure(e: String?) {}
            }, MediaConstraints())
        }
        statusPanel.text = "Ringing…"
        startCallTone(ToneGenerator.TONE_SUP_RINGTONE)
    }

    private fun answerIncoming() {
        if (callAnswered || isCaller || !running) return
        CallNotificationManager.cancelIncoming(this)
        scope.launch {
            runCatching {
                val id = call?.id ?: error("Call not found")
                call = withContext(Dispatchers.IO) { callApi.updateStatus(id, "accepted") }
                setActiveControls()
                val offer = call?.offerSdp ?: waitForOffer()
                lastRemoteOfferSdp = offer
                val remote = SessionDescription(SessionDescription.Type.OFFER, offer)
                withContext(Dispatchers.Main) {
                    peer?.setRemoteDescription(object : SdpObserver {
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onSetSuccess() {
                            peer?.createAnswer(object : SdpObserver {
                                override fun onCreateSuccess(answer: SessionDescription) {
                                    peer?.setLocalDescription(object : SdpObserver {
                                        override fun onCreateSuccess(p0: SessionDescription?) {}
                                        override fun onSetSuccess() {
                                            scope.launch(Dispatchers.IO) {
                                                runCatching { callApi.publishAnswer(call!!.id, answer.description) }
                                            }
                                        }
                                        override fun onCreateFailure(e: String?) {}
                                        override fun onSetFailure(e: String?) {}
                                    }, answer)
                                }
                                override fun onSetSuccess() {}
                                override fun onCreateFailure(e: String?) {}
                                override fun onSetFailure(e: String?) {}
                            }, MediaConstraints())
                        }
                        override fun onCreateFailure(e: String?) { runOnUiThread { statusPanel.text = e ?: "Call answer failed" } }
                        override fun onSetFailure(e: String?) { runOnUiThread { statusPanel.text = e ?: "Call answer failed" } }
                    }, remote)
                }
            }.onFailure {
                runOnUiThread { statusPanel.text = it.message ?: "Could not answer call" }
            }
        }
    }

    private suspend fun waitForOffer(): String {
        repeat(30) {
            val c = callApi.get(call!!.id)
            if (!c?.offerSdp.isNullOrBlank()) { call = c; return c!!.offerSdp!! }
            delay(500)
        }
        error("Caller did not send an offer")
    }


    private fun startCallTone(tone: Int) {
        stopCallTone()
        toneGenerator = runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80) }.getOrNull()
        ringStartedAt = System.currentTimeMillis()
        toneJob = scope.launch(Dispatchers.Main.immediate) {
            while (isActive && running) {
                toneGenerator?.startTone(tone, 1200)
                delay(1800)
            }
        }
    }

    private fun stopCallTone() {
        toneJob?.cancel()
        toneJob = null
        runCatching { toneGenerator?.stopTone() }
        toneGenerator?.release()
        toneGenerator = null
    }

    private fun playBusyTone() {
        stopCallTone()
        toneGenerator = runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, 85) }.getOrNull()
        toneGenerator?.startTone(ToneGenerator.TONE_SUP_BUSY, 2200)
    }

    private fun startSignalLoop() {
        scope.launch(Dispatchers.IO) {
            while (isActive && running) {
                val c = callApi.get(call!!.id) ?: break
                call = c

                if (isCaller && c.status == "ringing" && c.answerSdp == null &&
                    System.currentTimeMillis() - ringStartedAt >= 20000L) {
                    playBusyTone()
                    runOnUiThread { statusPanel.text = "User unavailable" }
                    runCatching { callApi.updateStatus(call!!.id, "failed") }
                    delay(1200)
                    finishWithoutRemoteUpdate()
                    break
                }

                if (isCaller && c.answerSdp != null && c.answerSdp != lastRemoteAnswerSdp) {
                    withContext(Dispatchers.Main) {
                        peer?.setRemoteDescription(object : SdpObserver {
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onSetSuccess() {
                                stopCallTone()
                                lastRemoteAnswerSdp = c.answerSdp
                                setActiveControls()
                                statusPanel.text = if (isVideoEnabled) "Video connected" else "Connected"
                            }
                            override fun onCreateFailure(e: String?) { statusPanel.text = e ?: "Answer failed" }
                            override fun onSetFailure(e: String?) { statusPanel.text = e ?: "Answer failed" }
                        }, SessionDescription(SessionDescription.Type.ANSWER, c.answerSdp))
                    }
                }

                if (!isCaller && !c.offerSdp.isNullOrBlank() && c.offerSdp != lastRemoteOfferSdp && peer?.remoteDescription != null) {
                    val offer = SessionDescription(SessionDescription.Type.OFFER, c.offerSdp)
                    withContext(Dispatchers.Main) {
                        peer?.setRemoteDescription(object : SdpObserver {
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onSetSuccess() {
                                lastRemoteOfferSdp = c.offerSdp
                                peer?.createAnswer(object : SdpObserver {
                                    override fun onCreateSuccess(answer: SessionDescription) {
                                        peer?.setLocalDescription(object : SdpObserver {
                                            override fun onCreateSuccess(p0: SessionDescription?) {}
                                            override fun onSetSuccess() {
                                                scope.launch(Dispatchers.IO) { runCatching { callApi.publishAnswer(call!!.id, answer.description) } }
                                            }
                                            override fun onCreateFailure(e: String?) {}
                                            override fun onSetFailure(e: String?) {}
                                        }, answer)
                                    }
                                    override fun onSetSuccess() {}
                                    override fun onCreateFailure(e: String?) {}
                                    override fun onSetFailure(e: String?) {}
                                }, MediaConstraints())
                            }
                            override fun onCreateFailure(e: String?) { statusPanel.text = e ?: "Video offer failed" }
                            override fun onSetFailure(e: String?) { statusPanel.text = e ?: "Video offer failed" }
                        }, offer)
                    }
                }

                val ice = if (isCaller) c.calleeIce else c.callerIce
                while (remoteIceCount < ice.length()) {
                    val j = ice.getJSONObject(remoteIceCount++)
                    val candidate = IceCandidate(j.optString("sdpMid"), j.optInt("sdpMLineIndex"), j.optString("candidate"))
                    withContext(Dispatchers.Main) { peer?.addIceCandidate(candidate) }
                }

                if (c.status in listOf("declined", "missed", "ended", "failed", "cancelled")) {
                    CallNotificationManager.cancelIncoming(this@CallActivity)
                    runOnUiThread { statusPanel.text = when (c.status) {
                        "ended" -> "Call ended"
                        "cancelled" -> "Call cancelled"
                        "failed" -> "Call failed"
                        "missed" -> "Missed call"
                        else -> "Call declined"
                    }}
                    delay(500)
                    finishWithoutRemoteUpdate()
                    break
                }
                delay(1000)
            }
        }
    }

    private fun finishCall(statusValue: String) {
        if (!running) return
        running = false
        stopCallTone()
        controlsHideJob?.cancel()
        val id = call?.id
        scope.launch {
            if (!id.isNullOrBlank()) {
                runCatching { withContext(Dispatchers.IO) { callApi.updateStatus(id, statusValue) } }
            }
            returnToMainActivity()
            cleanup()
            if (!isFinishing) finish()
        }
    }

    private fun finishWithoutRemoteUpdate() {
        if (!running) return
        running = false
        stopCallTone()
        controlsHideJob?.cancel()
        returnToMainActivity()
        cleanup()
        if (!isFinishing) finish()
    }

    private fun returnToMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        startActivity(intent)
    }

    private fun cleanup() {
        if (cleanedUp) return
        cleanedUp = true
        stopCallTone()
        runCatching { capturer?.stopCapture() }
        capturer?.dispose()
        capturer = null
        videoSource?.dispose()
        videoSource = null
        localStream?.dispose()
        localStream = null
        peer?.close()
        peer?.dispose()
        peer = null
        factory?.dispose()
        factory = null
        runCatching { audioDeviceModule?.release() }
        audioDeviceModule = null
        if (::localView.isInitialized) runCatching { localView.release() }
        if (::remoteView.isInitialized) runCatching { remoteView.release() }
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        if (android.os.Build.VERSION.SDK_INT >= 31) audio.clearCommunicationDevice()
        audio.isSpeakerphoneOn = false
        audio.mode = AudioManager.MODE_NORMAL
        runCatching { egl.release() }
        scope.cancel()
    }

    private fun minimizeToChat() {
        val prefs = getSharedPreferences("heartbeat_call_state", MODE_PRIVATE)
        val otherUserId = call?.let { current ->
            val me = auth.currentSession()?.profile?.id
            if (current.callerId == me) current.calleeId else current.callerId
        }
        prefs.edit().apply {
            putString("active_call_id", call?.id)
            putString("active_call_user_id", otherUserId)
            putString("active_call_type", if (isVideoEnabled) "video" else "voice")
            apply()
        }
        val video = intent.getStringExtra("call_type") == "video" || isVideoEnabled
        if (!video) {
            // Voice calls do not need a PiP video surface. Keep this CallActivity
            // alive underneath MainActivity so the audio connection continues.
            startActivity(Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            })
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= 26 &&
            packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)
        ) {
            val builder = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
            if (android.os.Build.VERSION.SDK_INT >= 31) builder.setAutoEnterEnabled(false)
            enterPictureInPictureMode(builder.build())
        } else {
            startActivity(Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            })
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (::statusPanel.isInitialized) statusPanel.visibility = if (isInPictureInPictureMode) android.view.View.GONE else android.view.View.VISIBLE
        if (::controlsPanel.isInitialized) controlsPanel.visibility = if (isInPictureInPictureMode) android.view.View.GONE else android.view.View.VISIBLE
        if (::localView.isInitialized) {
            localView.visibility = if (!isInPictureInPictureMode && intent.getStringExtra("call_type") == "video") android.view.View.VISIBLE else android.view.View.GONE
        }
    }

    override fun onBackPressed() {
        if (isInPictureInPictureMode) finishCall("ended") else minimizeToChat()
    }

    override fun onDestroy() {
        controlsHideJob?.cancel()
        statsJob?.cancel()
        if (running) {
            running = false
            val id = call?.id
            if (!id.isNullOrBlank()) {
                val statusValue = if (isCaller && !callAnswered) "cancelled" else "ended"
                Thread {
                    runCatching { callApi.updateStatusBlocking(id, statusValue) }
                }.start()
            }
        }
        cleanup()
        super.onDestroy()
    }
}
