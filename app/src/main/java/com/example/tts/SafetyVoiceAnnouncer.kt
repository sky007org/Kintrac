package com.example.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

enum class VoiceLanguage {
    BENGALI,
    ENGLISH
}

class SafetyVoiceAnnouncer(context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var isReady = false
    private val pendingUtterances = mutableListOf<Pair<String, VoiceLanguage>>()
    var currentLanguage: VoiceLanguage = VoiceLanguage.BENGALI

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            applyLanguage(currentLanguage)
            tts?.setSpeechRate(0.4f) // slow rate 0.4 for clear corporate cadence
            tts?.setPitch(1.0f)

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Log.d("VoiceTTS", "Started speaking: $utteranceId")
                }
                override fun onDone(utteranceId: String?) {
                    Log.d("VoiceTTS", "Finished speaking: $utteranceId")
                }
                override fun onError(utteranceId: String?) {
                    Log.e("VoiceTTS", "TTS error on: $utteranceId")
                }
            })

            isReady = true
            flushPending()
        } else {
            Log.e("VoiceTTS", "TTS initialization failed: $status")
        }
    }

    fun setLanguage(lang: VoiceLanguage) {
        currentLanguage = lang
        if (isReady) {
            applyLanguage(lang)
        }
    }

    private fun applyLanguage(lang: VoiceLanguage) {
        when (lang) {
            VoiceLanguage.BENGALI -> {
                val banglaLocale = Locale("bn", "BD")
                val result = tts?.setLanguage(banglaLocale)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale("bn"))
                }
                tts?.setSpeechRate(0.4f)
            }
            VoiceLanguage.ENGLISH -> {
                tts?.setLanguage(Locale.US)
                tts?.setSpeechRate(0.4f)
            }
        }
    }

    private fun flushPending() {
        while (pendingUtterances.isNotEmpty()) {
            val (text, lang) = pendingUtterances.removeAt(0)
            speakInternal(text, lang)
        }
    }

    private fun speakInternal(text: String, lang: VoiceLanguage) {
        applyLanguage(lang)
        val params = Bundle()
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        tts?.speak(text, TextToSpeech.QUEUE_ADD, params, "utt_${System.currentTimeMillis()}")
    }

    fun speak(text: String, lang: VoiceLanguage = currentLanguage) {
        if (isReady) {
            speakInternal(text, lang)
        } else {
            pendingUtterances.add(Pair(text, lang))
        }
    }

    // --- Family Mode Announcements (Always in Bengali bn-BD as requested) ---
    fun speakMedicineReminder(medicineName: String = "Nirdharito Oshud") {
        val text = "Sommanito byaboharkari, apnar oshud grohoner somoy hoyeche. Onugroho kore oshud grohon korun. Apnar nirdharito oshud grohoner somoy uposthit. Oshuder nam: $medicineName."
        speak(text, VoiceLanguage.BENGALI)
    }

    fun speakNamazAlert(namazName: String = "Johor") {
        val text = "Sommanito byaboharkari, ekhon $namazName namazer somoy hoyeche. Namaz adayer jonno prostut hon."
        speak(text, VoiceLanguage.BENGALI)
    }

    fun speakPondDangerAlert() {
        // User requested: "Bipodjonok elaka theke dure sore jan"
        val text = "Bipodjonok elaka theke dure sore jan. Pukurer bipodjonok simanar kache chole eshechen."
        speak(text, VoiceLanguage.BENGALI)
    }

    fun speakSafetyHealthCheck() {
        val text = "Sommanito byaboharkari, apni sustho achen kina onugroho kore nischit korun."
        speak(text, VoiceLanguage.BENGALI)
    }

    fun speakCheckedInSafe() {
        val text = "Sommanito byaboharkari, apnar nirapotta sthiti songrokkhon kora hoyeche. Apni nirapod achen."
        speak(text, VoiceLanguage.BENGALI)
    }

    // --- Corporate Mode Announcements ---
    fun speakCorporateTiffinBreak() {
        // User requested: "Dear employee, your tiffin break time has started."
        if (currentLanguage == VoiceLanguage.ENGLISH) {
            speak("Dear employee, your tiffin break time has started. Please enjoy your break.", VoiceLanguage.ENGLISH)
        } else {
            speak("Sommanito kormochari, apnar tiffin break shuru hoyeche.", VoiceLanguage.BENGALI)
        }
    }

    fun speakOfficeCheckIn(timeStr: String) {
        if (currentLanguage == VoiceLanguage.ENGLISH) {
            speak("Office check-in confirmed at $timeStr. Have a productive and safe workday.", VoiceLanguage.ENGLISH)
        } else {
            speak("Sommanito kormochari, $timeStr e apnar office check-in nischit kora hoyeche.", VoiceLanguage.BENGALI)
        }
    }

    fun speakLeaveApplicationSubmitted() {
        if (currentLanguage == VoiceLanguage.ENGLISH) {
            speak("Your leave application has been successfully submitted to your supervisor.", VoiceLanguage.ENGLISH)
        } else {
            speak("Sommanito kormochari, apnar chutir abedon shofolbhabe joma deya hoyeche.", VoiceLanguage.BENGALI)
        }
    }

    fun speakCorporateHazardZone() {
        if (currentLanguage == VoiceLanguage.ENGLISH) {
            speak("Warning: You have entered a restricted industrial hazard zone. Please step back immediately.", VoiceLanguage.ENGLISH)
        } else {
            speak("Sotorkota: Bipodjonok karkhana elakay probesh korechen. Druto dure sore jan.", VoiceLanguage.BENGALI)
        }
    }

    fun speakDangerZoneAlert(zoneName: String = "Railway Crossing") {
        val text = "Sotorkota: Protected byakti $zoneName er bipodjonok elakay probesh korechen. Druto jogajog korun."
        speak(text, VoiceLanguage.BENGALI)
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
