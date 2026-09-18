package com.heartbeatheaven.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
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
    private var peer: PeerConnection? = null
    private var factory: PeerConnectionFactory? = null
    private var capturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var localStream: MediaStream? = null
    private var call: CallSession? = null
    private var isCaller = false
    private var remoteIceCount = 0
    private var running = true
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
        root.addView(localView, FrameLayout.LayoutParams(320, 420, Gravity.TOP or Gravity.END).apply {
            topMargin = 32
            rightMargin = 16
        })

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(20, 24, 20, 24)
        }
        status = TextView(this).apply { text = "Connecting…"; textSize = 18f; setTextColor(-1); gravity = Gravity.CENTER }
        panel.addView(status, LinearLayout.LayoutParams(-1, -2))
        endButton = Button(this).apply { text = "End call"; setOnClickListener { finishCall("ended") } }
        panel.addView(endButton, LinearLayout.LayoutParams(-1, -2))
        root.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)
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
                if (isCaller) createAndPublishOffer()
                else acceptIncoming()
                startSignalLoop()
            }.onFailure {
                status.text = it.message ?: "Could not start call"
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
        peer = factory!!.createPeerConnection(
            listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
            ),
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
                    runOnUiThread { status.text = when (s) {
                        PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> "Connected"
                        PeerConnection.IceConnectionState.CHECKING -> "Connecting…"
                        PeerConnection.IceConnectionState.DISCONNECTED -> "Network reconnecting…"
                        PeerConnection.IceConnectionState.FAILED -> "Connection failed"
                        else -> status.text
                    }}
                }
                override fun onStandardizedIceConnectionChange(s: PeerConnection.IceConnectionState) {}
                override fun onConnectionChange(s: PeerConnection.PeerConnectionState) {
                    if (s == PeerConnection.PeerConnectionState.CONNECTED) runOnUiThread { status.text = "Connected" }
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
                override fun onCreateFailure(e: String?) { runOnUiThread { status.text = e ?: "Offer failed" } }
                override fun onSetFailure(e: String?) {}
            }, MediaConstraints())
        }
        status.text = "Ringing…"
    }

    private suspend fun acceptIncoming() {
        status.text = "Incoming call…"
        var current: CallSession? = null
        repeat(30) {
            current = callApi.get(call!!.id)
            if (current?.status == "accepted" || current?.answerSdp != null) return@repeat
            delay(500)
        }
        if (current?.status != "accepted") {
            call = callApi.updateStatus(call!!.id, "accepted")
            val offer = call!!.offerSdp ?: waitForOffer()
            val remote = SessionDescription(SessionDescription.Type.OFFER, offer)
            withContext(Dispatchers.Main) {
                peer!!.setRemoteDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() {
                        peer!!.createAnswer(object : SdpObserver {
                            override fun onCreateSuccess(answer: SessionDescription) {
                                peer!!.setLocalDescription(object : SdpObserver {
                                    override fun onCreateSuccess(p0: SessionDescription?) {}
                                    override fun onSetSuccess() { scope.launch(Dispatchers.IO) { callApi.publishAnswer(call!!.id, answer.description) } }
                                    override fun onCreateFailure(e: String?) {}
                                    override fun onSetFailure(e: String?) {}
                                }, answer)
                            }
                            override fun onSetSuccess() {}
                            override fun onCreateFailure(e: String?) {}
                            override fun onSetFailure(e: String?) {}
                        }, MediaConstraints())
                    }
                    override fun onCreateFailure(e: String?) {}
                    override fun onSetFailure(e: String?) {}
                }, remote)
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

    private fun startSignalLoop() {
        scope.launch(Dispatchers.IO) {
            while (isActive && running) {
                val c = callApi.get(call!!.id) ?: break
                call = c
                if (isCaller && c.answerSdp != null && peer!!.remoteDescription == null) {
                    withContext(Dispatchers.Main) {
                        peer!!.setRemoteDescription(object : SdpObserver {
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onSetSuccess() { status.text = "Connecting…" }
                            override fun onCreateFailure(e: String?) {}
                            override fun onSetFailure(e: String?) {}
                        }, SessionDescription(SessionDescription.Type.ANSWER, c.answerSdp))
                    }
                }
                val ice = if (isCaller) c.calleeIce else c.callerIce
                while (remoteIceCount < ice.length()) {
                    val j = ice.getJSONObject(remoteIceCount++)
                    val candidate = IceCandidate(j.optString("sdpMid"), j.optInt("sdpMLineIndex"), j.optString("candidate"))
                    withContext(Dispatchers.Main) { peer?.addIceCandidate(candidate) }
                }
                if (c.status in listOf("declined","missed","ended","failed","cancelled")) break
                delay(1000)
            }
        }
    }

    private fun finishCall(statusValue: String) {
        if (!running) { finish(); return }
        running = false
        val id = call?.id
        scope.launch {
            if (!id.isNullOrBlank()) runCatching { withContext(Dispatchers.IO) { callApi.updateStatus(id, statusValue) } }
            cleanup()
            finish()
        }
    }

    private fun cleanup() {
        runCatching { capturer?.stopCapture() }
        capturer?.dispose()
        videoSource?.dispose()
        localStream?.dispose()
        peer?.close()
        peer?.dispose()
        factory?.dispose()
        localView.release()
        remoteView.release()
        egl.release()
        scope.cancel()
    }

    override fun onBackPressed() { finishCall("ended") }
    override fun onDestroy() {
        if (running) {
            running = false
            runCatching { call?.id?.let { callApi.updateStatus(it, if (isCaller) "cancelled" else "ended") } }
        }
        cleanup()
        super.onDestroy()
    }
}
