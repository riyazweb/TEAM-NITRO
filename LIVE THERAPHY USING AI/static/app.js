let USER_ROLE = null;

// Initialize Socket with reliable transports
const socket = io({
  transports: ["polling", "websocket"],
  upgrade: true,
  reconnection: true,
  reconnectionAttempts: 10,
  timeout: 30000
});

// Use a global status tracker
const status = {
    camera: false,
    socket: false,
    peer: "disconnected"
};

function updateStatus(message) {
    console.log(`[STATUS] ${message}`);
    const statusText = document.getElementById("transcription-text");
    if (statusText) {
        statusText.innerText = message;
        // Make it flash to indicate activity
        statusText.style.opacity = "0.5";
        setTimeout(() => statusText.style.opacity = "1", 200);
    }
}

socket.on("connect", () => {
    console.log("✅ Socket.io Connected! ID:", socket.id);
    status.socket = true;
    updateStatus("Connected to Server. Waiting for Role...");
});

socket.on("connect_error", (error) => {
    console.error("❌ Socket.io Connection Error:", error);
    updateStatus("Connection Error: " + error.message);
});

socket.on("disconnect", () => {
    updateStatus("Disconnected from Server.");
});

window.initApp = function(role) {
    USER_ROLE = role;
    console.log("🚀 Initializing App with Role:", role);
    updateStatus("Initializing Camera & Mic...");
    
    const localVideo = document.getElementById("localVideo");
    const remoteVideo = document.getElementById("remoteVideo");

    // --- CHART.JS INITIALIZATION ---
    const chartEl = document.getElementById('liveChart');
    let liveChart;
    if (chartEl) {
        const ctx = chartEl.getContext('2d');
        liveChart = new Chart(ctx, {
            type: 'line',
            data: {
                labels: [],
                datasets: [
                    {
                        label: '🟢 Normal (140)',
                        data: [],
                        borderColor: '#22c55e',
                        borderDash: [5, 5],
                        borderWidth: 1.5,
                        fill: false,
                        pointRadius: 0
                    },
                    {
                        label: '📉 You (Live)',
                        data: [],
                        borderColor: '#3b82f6',
                        backgroundColor: 'rgba(59, 130, 246, 0.1)',
                        tension: 0.4,
                        fill: true,
                        borderWidth: 2
                    }
                ]
            },
            options: {
                responsive: true,
                plugins: { legend: { display: true, labels: { boxWidth: 12, font: { size: 10 } } } },
                scales: {
                    y: { min: 40, max: 220, ticks: { font: { size: 9 } } },
                    x: { display: false }
                }
            }
        });
    }

    let localStream;
    let isAudioMuted = false;
    let isVideoOff = false;

    const config = {
      iceServers: [
        { urls: "stun:stun.l.google.com:19302" },
        { urls: "stun:stun1.l.google.com:19302" },
        { urls: "stun:stun2.l.google.com:19302" },
        { urls: "stun:stun3.l.google.com:19302" },
        { urls: "stun:stun.ekiga.net" }
      ],
      iceCandidatePoolSize: 10
    };

    const pc = new RTCPeerConnection(config);

    // Media Capture & Audio Processing
    navigator.mediaDevices.getUserMedia({ 
        video: { 
            width: { ideal: 640 }, 
            height: { ideal: 360 },
            facingMode: "user"
        }, 
        audio: {
            echoCancellation: true,
            noiseSuppression: true,
            autoGainControl: true
        }
    })
    .then(stream => {
      console.log("✅ Media devices accessed successfully");
      status.camera = true;
      localStream = stream;
      
      if (localVideo) {
        localVideo.muted = true;
        localVideo.srcObject = stream;
        localVideo.onloadedmetadata = () => {
            localVideo.play().catch(e => console.error("Local video play error:", e));
        };
      }
      
      stream.getTracks().forEach(track => {
        pc.addTrack(track, stream);
      });

      initButtonListeners(localStream, pc);

      try {
          initAudioRecorder(stream);
      } catch (e) {
          console.error("Audio Recorder initialization failed:", e);
      }

      // SIGNALING START
      updateStatus("Ready to Join...");
      if (socket.connected) {
          socket.emit("join");
      } else {
          socket.on("connect", () => {
              socket.emit("join");
          });
      }
    })
    .catch(error => {
      updateStatus("Camera Error: " + error.message);
      alert("Camera/Mic denied. Please allow permissions.");
    });

    function initAudioRecorder(stream) {
        if (!stream || stream.getAudioTracks().length === 0) return;
        const audioStream = new MediaStream(stream.getAudioTracks());
        let supportedMime = "audio/webm;codecs=opus";
        if (!MediaRecorder.isTypeSupported(supportedMime)) supportedMime = "audio/webm";

        let recorder;
        const startRecording = () => {
            try {
                recorder = new MediaRecorder(audioStream, { mimeType: supportedMime });
                recorder.ondataavailable = async (event) => {
                    if (event.data && event.data.size > 200) {
                        const buffer = await event.data.arrayBuffer();
                        socket.emit("audio_chunk", buffer);
                    }
                };
                recorder.onstop = () => {
                    if (localStream && localStream.active) {
                        setTimeout(startRecording, 200); 
                    }
                };
                recorder.start();
                setTimeout(() => {
                    if (recorder.state === "recording") recorder.stop();
                }, 5000); // 5s chunks
            } catch (e) {
                setTimeout(startRecording, 1000);
            }
        };
        startRecording();
    }

    // --- PEER CONNECTION LISTENERS ---
    let makingOffer = false;
    let ignoreOffer = false;
    let isSettingRemoteDescription = false;
    const iceCandidateQueue = [];

    pc.ontrack = event => {
      console.log("📺 Remote track received!", event.streams[0]);
      updateStatus("Receiving Video Stream...");
      if (remoteVideo) {
        if (remoteVideo.srcObject !== event.streams[0]) {
            remoteVideo.srcObject = event.streams[0];
        }
        remoteVideo.onloadedmetadata = () => {
            remoteVideo.play()
                .then(() => updateStatus("Connected to Peer (Video Active)"))
                .catch(e => console.error("Remote play error:", e));
        };
      }
    };

    pc.oniceconnectionstatechange = () => {
        console.log("🧊 ICE State:", pc.iceConnectionState);
        status.peer = pc.iceConnectionState;
        
        if (pc.iceConnectionState === "connected") updateStatus("ICE Connected! Waiting for video...");
        if (pc.iceConnectionState === "failed") updateStatus("Connection Failed. Check Firewall/Ngrok.");
        if (pc.iceConnectionState === "disconnected") updateStatus("Peer Disconnected.");
        if (pc.iceConnectionState === "checking") updateStatus("Checking Connection Paths...");
    };

    pc.onicecandidate = event => {
      if (event.candidate) {
        // Send raw JSON of candidate wrapped in object handled by server? 
        // Server expects dictionary to wrap ID. Standard logic:
        socket.emit("candidate", event.candidate.toJSON());
      }
    };

    socket.on("candidate", async data => {
      try {
        if (!data || !data.candidate) return;
        const candidate = new RTCIceCandidate(data.candidate ? data.candidate : data);
        if (pc.remoteDescription && !isSettingRemoteDescription) {
          await pc.addIceCandidate(candidate);
        } else {
          iceCandidateQueue.push(candidate);
        }
      } catch (err) {
        console.error("ICE Error:", err);
      }
    });

    async function processIceQueue() {
      while (iceCandidateQueue.length > 0) {
        const c = iceCandidateQueue.shift();
        try { await pc.addIceCandidate(c); } catch (e) {}
      }
    }

    async function startCall() {
        if (makingOffer || pc.signalingState !== "stable") return;
        try {
            makingOffer = true;
            updateStatus("Initiating Call...");
            const offer = await pc.createOffer({ offerToReceiveAudio: true, offerToReceiveVideo: true });
            await pc.setLocalDescription(offer);
            
            // Server now expects wrapped, but if we send straight JSON, server wrapper might break?
            // Wait, we updated server to receive payload. 
            // Correct flow: Client sends SDP -> Server wraps it -> Others receive wrapped.
            // But we have to send standard JSON. Server wrapper added 'sender' from request.sid
            socket.emit("offer", pc.localDescription.toJSON()); 
        } catch (err) {
            console.error(err);
        } finally {
            makingOffer = false;
        }
    }

    socket.on("user-joined", (data) => {
        console.log("👤 User Joined:", data.sid);
        updateStatus("Peer Joined. Calling...");
        startCall();
    });

    // ROBUST PERFECT NEGOTIATION WITH ID COMPARISON
    socket.on("offer", async (payload) => {
        try {
            // Unpack payload from server (which added 'sender')
            // If payload has 'sender', use it. If not (legacy), assume impolite?
            const senderId = payload.sender || "unknown";
            const sdp = payload; // payload contains type/sdp + sender

            // Determine Politeness based on String Collision
            // If my ID is lexicographically smaller, I am polite (I yield).
            // If my ID is larger, I am impolite (I ignore/override).
            // This guarantees one winner regardless of Role.
            const isPolite = (socket.id < senderId); 
            
            const offerCollision = (makingOffer || pc.signalingState !== "stable");
            
            ignoreOffer = !isPolite && offerCollision;
            if (ignoreOffer) {
                console.warn("⚠️ Collision: Ignoring offer (Impolite).");
                return;
            }
            if (offerCollision && isPolite) {
                console.log("🔄 Collision: Rolling back (Polite).");
                await pc.setLocalDescription({ type: "rollback" });
            }

            isSettingRemoteDescription = true;
            updateStatus("Received Offer. Answering...");
            
            // Construct RTCSessionDescription (strip extra fields if strict, but constructor ignores extra)
            await pc.setRemoteDescription(new RTCSessionDescription(sdp));
            
            const answer = await pc.createAnswer();
            await pc.setLocalDescription(answer);
            
            isSettingRemoteDescription = false;
            socket.emit("answer", pc.localDescription.toJSON());
            
            await processIceQueue();
        } catch (err) {
            console.error("Offer Error:", err);
            isSettingRemoteDescription = false;
        }
    });

    socket.on("answer", async (payload) => {
        try {
            updateStatus("Answer Received. Connecting...");
            isSettingRemoteDescription = true;
            await pc.setRemoteDescription(new RTCSessionDescription(payload));
            isSettingRemoteDescription = false;
            await processIceQueue();
        } catch (err) {
            console.error("Answer Error:", err);
            isSettingRemoteDescription = false;
        }
    });

    // --- AI VISUALIZATION ---
    socket.on("ai_update", (data) => {
        if (!liveChart || !data.metrics) return;
        const timeNow = new Date().toLocaleTimeString('en-US', { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' });
        liveChart.data.labels.push(timeNow);
        liveChart.data.datasets[0].data.push(140); 
        liveChart.data.datasets[1].data.push(data.metrics.wpm);
        if(liveChart.data.labels.length > 15) {
            liveChart.data.labels.shift();
            liveChart.data.datasets[0].data.shift();
            liveChart.data.datasets[1].data.shift();
        }
        liveChart.update('none');

        if (data.ai_consult) {
            const suggestions = data.ai_consult;
            if(document.getElementById("ai-emotion")) document.getElementById("ai-emotion").innerText = suggestions.emotional_state || "";
            if(document.getElementById("ai-insight")) document.getElementById("ai-insight").innerText = suggestions.key_insight || "";
            if(document.getElementById("ai-motivation")) document.getElementById("ai-motivation").innerText = suggestions.motivational_guidance || "";
            
            const qDiv = document.getElementById("ai-questions");
            if(qDiv) {
                qDiv.innerHTML = "";
                (suggestions.suggested_questions || []).forEach(q => {
                    const el = document.createElement("div");
                    el.className = "text-[10px] bg-white text-blue-900 px-3 py-1.5 rounded-custom border border-blue-100 shadow-sm font-bold uppercase transition-transform hover:scale-105";
                    el.innerText = q;
                    qDiv.appendChild(el);
                });
            }

            const cDiv = document.getElementById("ai-cure-steps");
            if(cDiv) {
                cDiv.innerHTML = (suggestions.cure_steps || [])
                    .map(step => `<div class="flex gap-2 text-amber-900 border-l-2 border-amber-300 pl-2 font-medium"><span>${step}</span></div>`)
                    .join("");
            }

            const riskEl = document.getElementById("ai-risk");
            if(riskEl) {
                const risk = (suggestions.risk_level || "low").toLowerCase();
                riskEl.innerText = suggestions.risk_level;
                riskEl.className = "text-[9px] font-black px-2 py-1 rounded-custom uppercase tracking-tighter " + 
                    (risk.includes("high") ? "bg-red-600 text-white" : 
                     risk.includes("medium") ? "bg-amber-500 text-white" : "bg-primary text-white");
            }
        }
    });

    function initButtonListeners(stream, pc) {
        document.getElementById("btn-toggle-audio").addEventListener("click", (e) => {
            isAudioMuted = !isAudioMuted;
            stream.getAudioTracks().forEach(t => t.enabled = !isAudioMuted);
            e.currentTarget.innerHTML = `<span class="material-symbols-outlined">${isAudioMuted ? 'mic_off' : 'mic'}</span>`;
        });
        document.getElementById("btn-toggle-video").addEventListener("click", (e) => {
            isVideoOff = !isVideoOff;
            stream.getVideoTracks().forEach(t => t.enabled = !isVideoOff);
            e.currentTarget.innerHTML = `<span class="material-symbols-outlined">${isVideoOff ? 'videocam_off' : 'videocam'}</span>`;
        });
        document.getElementById("btn-end-call").addEventListener("click", () => window.location.reload());
    }
};
