package com.heartbeatheaven.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Rational
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import org.webrtc.*

class CallActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var status: TextView
    private lateinit var localView: SurfaceViewRenderer
    private lateinit var remoteView: SurfaceViewRenderer
    private lateinit var endButton: Button
    private lateinit var controlsPanel: LinearLayout
    private lateinit var statusPanel: TextView
    private var controlsHideJob: Job? = null
    private var controlsVisible = true
    private var callAnswered = false
    private var peer: PeerConnection? = null
    private var factory: PeerConnectionFactory? = null
    private var capturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
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
            setPadding(10, 12, 10, 12)
            setBackgroundColor(0xDD15171A.toInt())
        }

        fun control(text: String, description: String, onClick: () -> Unit): Button =
            Button(this).apply {
                this.text = text
                contentDescription = description
                textSize = 12f
                isAllCaps = false
                setOnClickListener { onClick() }
                minWidth = 0
                minimumWidth = 0
                setPadding(8, 4, 8, 4)
                layoutParams = LinearLayout.LayoutParams(0, 70).apply { weight = 1f }
            }

        val addPeopleButton = control("👥\nAdd", "Add people to group call") {
            showAddPeopleMessage()
        }
        val videoButton = control("🎥\nVideo", "Toggle video") {
            toggleVideoTrack()
        }
        val speakerButton = control("🔊\nSpeaker", "Toggle speaker") {
            toggleSpeaker()
        }
        val muteButton = control("🎙\nMute", "Toggle microphone") {
            toggleMute()
        }
        val chatButton = control("💬\nChat", "Open chat") {
            minimizeToChat()
        }

        controlsPanel.addView(addPeopleButton)
        controlsPanel.addView(videoButton)
        controlsPanel.addView(speakerButton)
        controlsPanel.addView(muteButton)
        controlsPanel.addView(chatButton)

        endButton = control("🔴\nEnd", "Answer or end call") {
            if (!callAnswered && !isCaller) answerIncoming() else finishCall(if (isCaller && !callAnswered) "cancelled" else "ended")
        }
        controlsPanel.addView(endButton)
        root.addView(controlsPanel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            bottomMargin = (48 * resources.displayMetrics.density).toInt()
            leftMargin = 8
            rightMargin = 8
        })

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
        val senders = peer?.senders ?: emptyList()
        senders.filter { it.track() is AudioTrack }.forEach {
            val track = it.track() as AudioTrack
            track.setEnabled(!track.enabled())
        }
    }

    private fun toggleSpeaker() {
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        audio.isSpeakerphoneOn = !audio.isSpeakerphoneOn
    }

    private fun toggleVideoTrack() {
        val videoSender = peer?.senders?.firstOrNull { it.track() is VideoTrack }
        if (videoSender?.track() is VideoTrack) {
            val track = videoSender.track() as VideoTrack
            track.setEnabled(!track.enabled())
            return
        }
        runOnUiThread { statusPanel.text = "Video is not available on this voice call yet" }
    }

    private fun showAddPeopleMessage() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Add people")
            .setMessage("Group-call participant selection is ready for the call controls. The multi-party WebRTC session layer will be added before this button starts a real group call.")
            .setPositiveButton("OK", null)
            .show()
    }

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
                }
                startSignalLoop()
            }.onFailure {
                statusPanel.text = it.message ?: "Could not start call"
                endButton.text = "Close"
            }
        }
    }

    private fun setupPeer(video: Boolean) {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(applicationContext).setEnableInternalTracer(false).createInitializationOptions()
        )
        factory = PeerConnectionFactory.builder().createPeerConnectionFactory()

        val audioConstraints = MediaConstraints()
        val audioSource = factory!!.createAudioSource(audioConstraints)
        val audioTrack = factory!!.createAudioTrack("audio_" + call!!.id, audioSource)

        val mediaConstraints = MediaConstraints()
        val fallbackIceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )
        scope.launch {
            val (relayServers, relayOnly) = runCatching { withContext(Dispatchers.IO) { callApi.fetchIceServers() } }
                .getOrElse { Pair(emptyList(), false) }
            if (relayOnly && relayServers.isNotEmpty()) {
                createPeerConnectionWithServers(relayServers, true, video)
            } else {
                createPeerConnectionWithServers(fallbackIceServers, false, video)
            }
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
                override fun onTrack(transceiver: RtpTransceiver) {}
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

        peer!!.addTrack(audioTrack, listOf("stream_" + call!!.id))
        if (video) {
            capturer = createCameraCapturer()
            videoSource = factory!!.createVideoSource(capturer!!.isScreencast)
            capturer!!.initialize(SurfaceTextureHelper.create("CallCamera", egl.eglBaseContext), this, videoSource!!.capturerObserver)
            capturer!!.startCapture(640, 360, 24)
            val videoTrack = factory!!.createVideoTrack("video_" + call!!.id, videoSource)
            videoTrack.addSink(localView)
            peer!!.addTrack(videoTrack, listOf("stream_" + call!!.id))
        } else {
            localView.visibility = android.view.View.GONE
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

    private fun createCameraCapturer(): VideoCapturer {
        val enumerator = Camera2Enumerator(this)
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
        scope.launch {
            runCatching {
                val id = call?.id ?: error("Call not found")
                call = withContext(Dispatchers.IO) { callApi.updateStatus(id, "accepted") }
                setActiveControls()
                val offer = call?.offerSdp ?: waitForOffer()
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

                if (isCaller && c.answerSdp != null && peer?.remoteDescription == null) {
                    withContext(Dispatchers.Main) {
                        peer?.setRemoteDescription(object : SdpObserver {
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onSetSuccess() {
                                stopCallTone()
                                setActiveControls()
                                statusPanel.text = "Connected"
                            }
                            override fun onCreateFailure(e: String?) { statusPanel.text = e ?: "Answer failed" }
                            override fun onSetFailure(e: String?) { statusPanel.text = e ?: "Answer failed" }
                        }, SessionDescription(SessionDescription.Type.ANSWER, c.answerSdp))
                    }
                }

                val ice = if (isCaller) c.calleeIce else c.callerIce
                while (remoteIceCount < ice.length()) {
                    val j = ice.getJSONObject(remoteIceCount++)
                    val candidate = IceCandidate(j.optString("sdpMid"), j.optInt("sdpMLineIndex"), j.optString("candidate"))
                    withContext(Dispatchers.Main) { peer?.addIceCandidate(candidate) }
                }

                if (c.status in listOf("declined", "missed", "ended", "failed", "cancelled")) {
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
        if (::localView.isInitialized) runCatching { localView.release() }
        if (::remoteView.isInitialized) runCatching { remoteView.release() }
        runCatching { egl.release() }
        scope.cancel()
    }

    private fun minimizeToChat() {
        val video = intent.getStringExtra("call_type") == "video"
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
