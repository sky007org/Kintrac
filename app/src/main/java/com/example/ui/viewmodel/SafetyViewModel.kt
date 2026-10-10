package com.example.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.model.SafetyStatus
import com.example.data.model.SosAlert
import com.example.data.model.UserProfile
import com.example.data.repository.SafetyRepository
import com.example.service.SafetyTrackingService
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.UUID

data class SafetyUiState(
    val isLoading: Boolean = true,
    val userProfile: UserProfile? = null,
    val ownSafetyStatus: SafetyStatus? = null,
    val familyMembers: List<SafetyStatus> = emptyList(),
    val sosAlerts: List<SosAlert> = emptyList(),
    val isSharingActive: Boolean = false,
    val errorMessage: String? = null,
    val infoMessage: String? = null
)

class SafetyViewModel(private val repository: SafetyRepository) : ViewModel() {

    private val auth = FirebaseAuth.getInstance()
    private val _uiState = MutableStateFlow(SafetyUiState())
    val uiState: StateFlow<SafetyUiState> = _uiState.asStateFlow()

    private var profileJob: Job? = null
    private var ownStatusJob: Job? = null
    private var familyJob: Job? = null
    private var sosJob: Job? = null

    init {
        loadData()
    }

    fun loadData() {
        val uid = auth.currentUser?.uid ?: return
        _uiState.value = _uiState.value.copy(isLoading = true)

        profileJob?.cancel()
        profileJob = viewModelScope.launch {
            repository.observeUserProfile(uid)
                .catch { error ->
                    Log.e("SafetyViewModel", "Error observing profile", error)
                    _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = error.message)
                }
                .collectLatest { profile ->
                    _uiState.value = _uiState.value.copy(
                        userProfile = profile,
                        isLoading = false
                    )
                    profile?.familyCode?.let { code ->
                        if (code.isNotBlank()) {
                            observeFamilyCircle(code)
                        }
                    }
                }
        }

        ownStatusJob?.cancel()
        ownStatusJob = viewModelScope.launch {
            repository.observeSafetyStatus(uid)
                .catch { error ->
                    Log.e("SafetyViewModel", "Error observing own status", error)
                }
                .collectLatest { status ->
                    _uiState.value = _uiState.value.copy(
                        ownSafetyStatus = status,
                        isSharingActive = status?.sharingActive ?: false
                    )
                }
        }
    }

    private fun observeFamilyCircle(familyCode: String) {
        familyJob?.cancel()
        familyJob = viewModelScope.launch {
            repository.observeFamilyMembers(familyCode)
                .catch { error ->
                    Log.e("SafetyViewModel", "Error observing family members", error)
                }
                .collectLatest { members ->
                    _uiState.value = _uiState.value.copy(familyMembers = members)
                }
        }

        sosJob?.cancel()
        sosJob = viewModelScope.launch {
            repository.observeSosAlerts(familyCode)
                .catch { error ->
                    Log.e("SafetyViewModel", "Error observing SOS alerts", error)
                }
                .collectLatest { alerts ->
                    _uiState.value = _uiState.value.copy(sosAlerts = alerts)
                }
        }
    }

    fun setConsent(consentGiven: Boolean) {
        val user = auth.currentUser ?: return
        viewModelScope.launch {
            val existing = _uiState.value.userProfile
            val generatedCode = if (existing?.familyCode.isNullOrBlank()) {
                "FAM-${(1000..9999).random()}"
            } else {
                existing!!.familyCode
            }

            val updated = UserProfile(
                userId = user.uid,
                email = user.email ?: "",
                displayName = user.displayName ?: "Family Member",
                role = existing?.role ?: "protected",
                consentGiven = consentGiven,
                familyCode = generatedCode,
                emergencyContactName = existing?.emergencyContactName ?: "",
                emergencyContactPhone = existing?.emergencyContactPhone ?: ""
            )
            repository.saveUserProfile(updated)
        }
    }

    fun updateRoleAndDetails(
        role: String,
        familyCode: String,
        emergencyName: String,
        emergencyPhone: String,
        appMode: String = "family",
        voiceLanguage: String = "bengali"
    ) {
        val user = auth.currentUser ?: return
        viewModelScope.launch {
            val current = _uiState.value.userProfile
            val profile = UserProfile(
                userId = user.uid,
                email = user.email ?: "",
                displayName = user.displayName ?: "Family Member",
                role = role,
                consentGiven = current?.consentGiven ?: true,
                familyCode = familyCode.trim().uppercase(),
                emergencyContactName = emergencyName.trim(),
                emergencyContactPhone = emergencyPhone.trim(),
                appMode = appMode,
                voiceLanguage = voiceLanguage
            )
            val result = repository.saveUserProfile(profile)
            if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(infoMessage = "Settings saved successfully")
            } else {
                _uiState.value = _uiState.value.copy(errorMessage = result.exceptionOrNull()?.message)
            }
        }
    }

    fun toggleSafetySharing(context: Context, active: Boolean) {
        val user = auth.currentUser ?: return
        val profile = _uiState.value.userProfile
        val currentStatus = _uiState.value.ownSafetyStatus

        viewModelScope.launch {
            if (active) {
                // Start Foreground Service
                val serviceIntent = Intent(context, SafetyTrackingService::class.java).apply {
                    action = SafetyTrackingService.ACTION_START
                    putExtra(SafetyTrackingService.EXTRA_FAMILY_CODE, profile?.familyCode ?: "DEFAULT")
                    putExtra(SafetyTrackingService.EXTRA_DISPLAY_NAME, profile?.displayName ?: "Family Member")
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }

                val status = (currentStatus ?: SafetyStatus()).copy(
                    userId = user.uid,
                    familyCode = profile?.familyCode ?: "DEFAULT",
                    displayName = profile?.displayName ?: "Protected Member",
                    sharingActive = true
                )
                repository.updateSafetyStatus(status)
                _uiState.value = _uiState.value.copy(isSharingActive = true)
            } else {
                // Stop Service
                val stopIntent = Intent(context, SafetyTrackingService::class.java).apply {
                    action = SafetyTrackingService.ACTION_STOP
                }
                context.startService(stopIntent)

                val status = (currentStatus ?: SafetyStatus()).copy(
                    userId = user.uid,
                    sharingActive = false
                )
                repository.updateSafetyStatus(status)
                _uiState.value = _uiState.value.copy(isSharingActive = false)
            }
        }
    }

    fun setAppMode(mode: String) {
        val user = auth.currentUser ?: return
        val current = _uiState.value.userProfile ?: return
        val defaultLang = if (mode == "corporate") "english" else "bengali"
        viewModelScope.launch {
            val updated = current.copy(
                appMode = mode,
                voiceLanguage = defaultLang
            )
            repository.saveUserProfile(updated)
            _uiState.value = _uiState.value.copy(
                infoMessage = if (mode == "corporate") "Switched to Corporate Mode" else "Switched to Family Safety Mode"
            )
        }
    }

    fun setVoiceLanguage(language: String) {
        val user = auth.currentUser ?: return
        val current = _uiState.value.userProfile ?: return
        viewModelScope.launch {
            val updated = current.copy(voiceLanguage = language)
            repository.saveUserProfile(updated)
            _uiState.value = _uiState.value.copy(
                infoMessage = "Voice language set to ${if (language == "bengali") "Bengali (bn-BD)" else "English (en-US)"}"
            )
        }
    }

    fun checkInOffice(timeStr: String) {
        val user = auth.currentUser ?: return
        val current = _uiState.value.userProfile ?: return
        viewModelScope.launch {
            val updated = current.copy(officeCheckInTime = timeStr)
            repository.saveUserProfile(updated)
            _uiState.value = _uiState.value.copy(
                infoMessage = "Office Check-In recorded at $timeStr"
            )
        }
    }

    fun applyForLeave(leaveType: String, reason: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                infoMessage = "Leave application ($leaveType) submitted for approval."
            )
        }
    }

    fun triggerPondAlert(context: Context) {
        val user = auth.currentUser ?: return
        val profile = _uiState.value.userProfile
        val currentStatus = _uiState.value.ownSafetyStatus

        val lat = currentStatus?.latitude ?: 0.0
        val lng = currentStatus?.longitude ?: 0.0
        val mapsUrl = if (lat != 0.0 && lng != 0.0) "https://maps.google.com/?q=$lat,$lng" else "Location unavailable"
        val message = "Sotorkota! Apnar poribarer sodosyo pukurer kache chole geche. Druto lokkho korun. Location: $mapsUrl"

        viewModelScope.launch {
            val alert = SosAlert(
                id = "pond_${UUID.randomUUID()}",
                userId = user.uid,
                displayName = profile?.displayName ?: "Family Member",
                familyCode = profile?.familyCode ?: "DEFAULT",
                latitude = lat,
                longitude = lng,
                batteryLevel = currentStatus?.batteryLevel ?: 100,
                status = "ACTIVE",
                message = message
            )
            repository.createSosAlert(alert)
            _uiState.value = _uiState.value.copy(infoMessage = "Pond danger alert sent to guardians!")

            val contactPhone = profile?.emergencyContactPhone
            if (!contactPhone.isNullOrBlank()) {
                try {
                    val smsIntent = Intent(Intent.ACTION_SENDTO).apply {
                        data = Uri.parse("smsto:$contactPhone")
                        putExtra("sms_body", message)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(smsIntent)
                } catch (e: Exception) {
                    Log.w("SafetyViewModel", "SMS intent failed to launch", e)
                }
            }
        }
    }

    fun triggerSos(context: Context) {
        val user = auth.currentUser ?: return
        val profile = _uiState.value.userProfile
        val currentStatus = _uiState.value.ownSafetyStatus

        val lat = currentStatus?.latitude ?: 0.0
        val lng = currentStatus?.longitude ?: 0.0
        val mapsUrl = if (lat != 0.0 && lng != 0.0) "https://maps.google.com/?q=$lat,$lng" else "Location unavailable"
        val isCorporate = profile?.appMode == "corporate"

        val message = if (isCorporate) {
            "EMERGENCY ALERT: Employee ${profile?.displayName ?: "Staff"} needs immediate assistance! / SOTORKOTA: Kormochari bipode porechen! Location: $mapsUrl"
        } else {
            "Sotorkota! Apnar poribarer sodosyo ${profile?.displayName ?: "Sodosyo"} bipode porechen. Druto sahajjo korun. Location: $mapsUrl"
        }

        viewModelScope.launch {
            val alert = SosAlert(
                id = "sos_${UUID.randomUUID()}",
                userId = user.uid,
                displayName = profile?.displayName ?: "Family Member",
                familyCode = profile?.familyCode ?: "DEFAULT",
                latitude = lat,
                longitude = lng,
                batteryLevel = currentStatus?.batteryLevel ?: 100,
                status = "ACTIVE",
                message = message
            )
            repository.createSosAlert(alert)
            _uiState.value = _uiState.value.copy(infoMessage = "Emergency SOS alert broadcast!")

            // Open SMS Intent to emergency contact if configured
            val contactPhone = profile?.emergencyContactPhone
            if (!contactPhone.isNullOrBlank()) {
                try {
                    val smsIntent = Intent(Intent.ACTION_SENDTO).apply {
                        data = Uri.parse("smsto:$contactPhone")
                        putExtra("sms_body", message)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(smsIntent)
                } catch (e: Exception) {
                    Log.w("SafetyViewModel", "SMS intent failed to launch", e)
                }
            }
        }
    }

    fun resolveSos(alertId: String) {
        viewModelScope.launch {
            repository.resolveSosAlert(alertId)
            _uiState.value = _uiState.value.copy(infoMessage = "SOS alert resolved")
        }
    }

    fun clearMessages() {
        _uiState.value = _uiState.value.copy(errorMessage = null, infoMessage = null)
    }

    class Factory(private val repository: SafetyRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SafetyViewModel(repository) as T
        }
    }
}
