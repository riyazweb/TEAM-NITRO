import eventlet
eventlet.monkey_patch()

from flask import Flask, render_template, request, send_from_directory
from flask_socketio import SocketIO, emit
from faster_whisper import WhisperModel
import google.generativeai as genai
import json
import io
import os
import subprocess
import tempfile
import time
from voice_engine import extract_voice_features

app = Flask(__name__)
app.config['SECRET_KEY'] = 'mentis-secret-key'

# Increase timeouts and buffer sizes to handle large audio chunks and high-frequency packets
socketio = SocketIO(
    app, 
    cors_allowed_origins="*",
    cors_credentials=True,
    max_http_buffer_size=20000000, 
    async_mode='eventlet',
    ping_timeout=60,
    ping_interval=25,
    logger=False,
    engineio_logger=False,
    allow_upgrades=True,
    transports=['polling', 'websocket']
)

# Fix for OpenMP duplicate library error
os.environ["KMP_DUPLICATE_LIB_OK"] = "TRUE"

# --- ML MODELS ---
print("⏳ Loading Hugging Face Emotion Model...")
try:
    # Temporarily disabled - model too heavy, causing hangs
    # emotion_classifier = pipeline("audio-classification", model="ehcalabres/wav2vec2-lg-xlsr-en-speech-emotion-recognition")
    emotion_classifier = None
    print("⚠️ ML Emotion Model DISABLED (to fix performance) - Gemini AI will still work!")
except Exception as e:
    print(f"❌ ML Model Error: {e}")
    emotion_classifier = None

def get_ml_prediction(audio_path):
    if not emotion_classifier:
        return None
    try:
        import signal
        
        def timeout_handler(signum, frame):
            raise TimeoutError("ML inference timeout")
        
        # Set 3 second timeout (Windows doesn't support signal.alarm, so we'll use threading)
        import threading
        result_container = [None]
        
        def run_inference():
            try:
                results = emotion_classifier(audio_path)
                scores = {res['label']: res['score'] for res in results}
                
                depression_signal = scores.get('sad', 0) + scores.get('neutral', 0)
                anxiety_signal = scores.get('fear', 0) + scores.get('angry', 0)
                
                result_container[0] = {
                    "depression_score": round(depression_signal * 100, 2),
                    "anxiety_score": round(anxiety_signal * 100, 2),
                    "primary_emotion": results[0]['label']
                }
            except Exception as e:
                print(f"ML Thread Error: {e}")
        
        thread = threading.Thread(target=run_inference)
        thread.daemon = True
        thread.start()
        thread.join(timeout=3.0)  # 3 second timeout
        
        if thread.is_alive():
            print("⚠️ ML model timeout - skipping")
            return None
            
        return result_container[0]
    except Exception as e:
        print(f"❌ ML Processing Error: {e}")
        return None

# Gemini Setup (google.generativeai)
GEMINI_KEY = "AIzaSyABd6lEZzbr4HOQ_i_r-uIku_a_j7SsOac"
genai.configure(api_key=GEMINI_KEY)

# Initialize Whisper model (using 'tiny' for speed)
print("⏳ Loading Whisper model...")
whisper_model = WhisperModel("tiny", device="cpu", compute_type="float32")
print("✅ Whisper Ready.")

# Session Memory to track cumulative conversation for a total summary
session_history = {}

def get_ai_suggestions(transcript, voice_metrics, sid):
    """Call Gemini for cumulative pathological analysis & therapeutic moves"""
    try:
        # Update history for this specific session
        if sid not in session_history:
            session_history[sid] = []
        
        # Only add if not empty 
        if transcript.strip():
            session_history[sid].append(transcript)
        
        # Use last 30 segments for deep historical context
        full_context = " ".join(session_history[sid][-30:]) if session_history[sid] else "No data."

        prompt = f"""
        ROLE: Senior Clinical Diagnostic Consultant.
        
        INPUT:
        Current Speech: "{transcript}"
        Historical Session Context: "{full_context}"
        Biometrics: {voice_metrics['wpm']} WPM (Normal is 140).
        
        TASK:
        Provide a psychological analysis in STRICT JSON format.
        
        SECTIONS REQUIRED:
        1. emotional_state: Summarize the CURRENT session mood/trajectory.
        2. key_insight: Identify the clinical "Problems + Past" conflicts revealed.
        3. suggested_questions: 3 questions for the doctor to ask.
        4. cure_steps: 3 actionable clinical advice steps for the patient.
        5. motivational_guidance: A short motivational metaphor/story to improve their state.
        6. risk_level: LOW/MEDIUM/HIGH/CRISIS.
        
        JSON FORMAT:
        {{
            "emotional_state": "text",
            "key_insight": "text",
            "suggested_questions": ["q1", "q2", "q3"],
            "cure_steps": ["step1", "step2", "step3"],
            "motivational_guidance": "text",
            "risk_level": "string"
        }}
        """

        model = genai.GenerativeModel('gemini-3-flash-preview')
        
        response = model.generate_content(
            prompt,
            generation_config=genai.types.GenerationConfig(
                temperature=0.7,
                response_mime_type="application/json"
            )
        )
        
        if response and response.text:
            return json.loads(response.text)
        return None
    except Exception as e:
        print(f"❌ Gemini Error: {e}")
        return None

@app.route("/")
def index():
    return render_template("index.html")

@app.route('/favicon.ico')
def favicon():
    return '', 204

@socketio.on('connect')
def handle_connect():
    print(f"🔗 Client Connected: {request.sid} from {request.remote_addr}")

@socketio.on('disconnect')
def handle_disconnect():
    print(f"❌ Client Disconnected: {request.sid}")

@socketio.on("offer")
def handle_offer(data):
    print(f"📡 OFFER from {request.sid}")
    # Wrap with sender ID for politeness logic
    payload = {
        "type": data.get("type"),
        "sdp": data.get("sdp"),
        "sender": request.sid
    }
    print(f"   Broadcasting offer from {request.sid}...")
    emit("offer", payload, broadcast=True, include_self=False)

@socketio.on("answer")
def handle_answer(data):
    print(f"📡 ANSWER from {request.sid}")
    # Wrap with sender ID
    payload = {
        "type": data.get("type"),
        "sdp": data.get("sdp"),
        "sender": request.sid
    }
    print(f"   Broadcasting answer from {request.sid}...")
    emit("answer", payload, broadcast=True, include_self=False)

@socketio.on("candidate")
def handle_candidate(data):
    # Wrap candidate too just in case
    # Ideally standard WebRTC expects just the candidate, but we are signaling manually
    # Let's keep candidate as direct object OR wrap it if we update JS
    # For now, JS expects straight candidate JSON or just the candidate object.
    # Let's wrap it to be consistent with ID logic
    payload = {
        "candidate": data.get("candidate"),
        "sdpMid": data.get("sdpMid"),
        "sdpMLineIndex": data.get("sdpMLineIndex"),
        "sender": request.sid
    }
    emit("candidate", payload, broadcast=True, include_self=False)

@socketio.on("join")
def handle_join():
    print(f"👤 User Joined: {request.sid}")
    print(f"📢 Broadcasting user-joined to all other clients...")
    emit("user-joined", {"sid": request.sid}, broadcast=True, include_self=False)
    print(f"✅ Broadcast complete")

@socketio.on("audio_chunk")
def handle_audio(data):
    try:
        if not data:
            return

        # 1. Save Blob to Temp File (raw webm)
        with tempfile.NamedTemporaryFile(suffix=".webm", delete=False) as temp_raw:
            temp_raw.write(data)
            raw_path = temp_raw.name
        
        # 2. Convert to WAV immediately (Fixes EBML Header Issues)
        wav_path = raw_path + ".wav"
        try:
            subprocess.run(['ffmpeg', '-y', '-i', raw_path, '-ar', '16000', '-ac', '1', wav_path], 
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
        except Exception as e:
            print(f"❌ FFMPEG Conversion Error: {e}")
            if os.path.exists(raw_path): os.remove(raw_path)
            return

        # 3. Transcribe (Whisper) using the FIXED wav file
        try:
            segments, _ = whisper_model.transcribe(wav_path)
            full_text = " ".join([seg.text for seg in segments]).strip()
        except Exception as e:
            print(f"⚠️ Whisper Error: {e}")
            full_text = ""
        
        if full_text:
            print(f"🗣️ Transcript: {full_text}")
            
            # 4. Analyze Voice (Librosa)
            print(f"🔍 Starting voice analysis...")
            voice_data = extract_voice_features(wav_path, full_text)
            if voice_data:
                print(f"✅ Voice metrics: WPM={voice_data['metrics']['wpm']}")
            else:
                print(f"⚠️ Voice analysis returned None")
            
            # 5. Integrate ML Biometric Scorer (Transformers)
            print(f"🤖 Starting ML biometric analysis...")
            try:
                ml_results = get_ml_prediction(wav_path)
                if ml_results:
                    print(f"✅ ML Results: Depression={ml_results['depression_score']}%, Anxiety={ml_results['anxiety_score']}%")
                else:
                    print(f"⚠️ ML analysis skipped or failed - continuing without it")
            except Exception as e:
                print(f"❌ ML crashed: {e}")
                ml_results = None

            # 6. Get AI Analysis (Gemini)
            print(f"🧠 Starting Gemini AI analysis...")
            try:
                suggestions = get_ai_suggestions(full_text, voice_data['metrics'] if voice_data else {'wpm':0, 'pause_ratio':0}, request.sid)
                if suggestions:
                    print(f"✅ AI Suggestions generated")
                else:
                    print(f"⚠️ Gemini returned None")
            except Exception as e:
                print(f"❌ Gemini crashed: {e}")
                suggestions = None
                
            # 7. Send Update
            print(f"📤 Sending update to client...")
            emit("ai_update", {
                "transcript": full_text,
                "voice_analysis": voice_data['ui_labels'] if voice_data else None,
                "metrics": voice_data['metrics'] if voice_data else None,
                "ml_analysis": ml_results,
                "ai_consult": suggestions,
                "sender": request.sid
            }, broadcast=True)
        else:
            print(f"😶 No speech detected in chunk.")

        # Cleanup
        for p in [raw_path, wav_path]:
            if os.path.exists(p): os.remove(p)
            
    except Exception as e:
        print(f"❌ Critical Processing Error: {e}")

if __name__ == "__main__":
    print("Starting server on http://127.0.0.1:5005")
    # For Windows development, keeping debug=True but ensuring use_reloader=False
    # to prevent multiple eventlet instances
    socketio.run(app, debug=True, host='127.0.0.1', port=5005, use_reloader=False, log_output=True)
