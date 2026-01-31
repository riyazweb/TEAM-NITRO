import librosa
import numpy as np
import os
import subprocess
import tempfile
import warnings

# Suppress annoying warnings
warnings.filterwarnings("ignore", category=UserWarning)
warnings.filterwarnings("ignore", category=FutureWarning)

def extract_voice_features(audio_path, transcript_text):
    """
    Analyzes the AUDIO SIGNAL separate from meaning.
    Converts to wav first for maximum compatibility.
    """
    temp_wav = None
    try:
        # Convert webm to wav using ffmpeg for cleaner processing
        temp_wav = audio_path + ".wav"
        subprocess.run(['ffmpeg', '-y', '-i', audio_path, '-ar', '16000', '-ac', '1', temp_wav], 
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)

        # Load Audio
        y, sr = librosa.load(temp_wav, sr=16000)
        duration = librosa.get_duration(y=y, sr=sr)
        
        if duration < 0.5: # Skip if audio is too short (provided professional threshold)
            return None

        # --- FEATURE A: SPEED (Speaking Rate) ---
        word_count = len(transcript_text.split())
        wpm = (word_count / duration) * 60 if duration > 0 else 0
        
        # Determine Label
        speed_label = "Normal"
        if wpm < 110:
            speed_label = "Slow (Lethargic)"
        elif wpm > 160:
            speed_label = "Fast (Anxious/Manic)"

        # --- FEATURE B: PITCH (Monotone vs Expressive) ---
        try:
            f0, voiced_flag, voiced_probs = librosa.pyin(y, fmin=75, fmax=300)
            pitch_std = np.nanstd(f0) if np.any(~np.isnan(f0)) else 0
        except:
            pitch_std = 0
            
        tone_label = "Normal"
        if pitch_std < 15: 
            tone_label = "Flat / Monotone"

        # --- FEATURE C: PAUSES (Silence Ratio) ---
        non_silent_intervals = librosa.effects.split(y, top_db=25)
        non_silent_time = sum([end - start for start, end in non_silent_intervals]) / sr
        pause_time = max(0, duration - non_silent_time)
        pause_ratio = (pause_time / duration) * 100 if duration > 0 else 0
        
        pause_label = "Normal"
        if pause_ratio > 40: 
            pause_label = "High Pauses"

        return {
            "ui_labels": {
                "speed": speed_label,
                "tone": tone_label,
                "pauses": pause_label
            },
            "metrics": {
                "wpm": round(wpm, 2),
                "pitch_var": "Low" if pitch_std < 15 else "Normal",
                "pause_ratio": round(pause_ratio, 2),
                "pitch_std": round(pitch_std, 2)
            }
        }
    except Exception as e:
        print(f"Error in voice extraction: {e}")
        return None
    finally:
        # Clean up temp wav
        if temp_wav and os.path.exists(temp_wav):
            try:
                os.remove(temp_wav)
            except:
                pass
