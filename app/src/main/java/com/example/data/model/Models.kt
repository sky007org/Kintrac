package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

@IgnoreExtraProperties
data class UserProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val email: String = "",
    val displayName: String = "",
    val role: String = "protected", // "protected" or "guardian"
    val consentGiven: Boolean = false,
    val familyCode: String = "",
    val emergencyContactName: String = "",
    val emergencyContactPhone: String = "",
    val appMode: String = "family", // "family" or "corporate"
    val voiceLanguage: String = "bengali", // "bengali" or "english"
    val officeCheckInTime: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class SafetyStatus(
    @DocumentId val id: String = "",
    val userId: String = "",
    val familyCode: String = "",
    val displayName: String = "",
    val sharingActive: Boolean = false,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val accuracy: Float = 0f,
    val batteryLevel: Int = 100,
    val isCharging: Boolean = false,
    val activityState: String = "STILL", // STILL, WALKING, RUNNING, IN_VEHICLE, UNKNOWN
    val stepCount: Int = 0,
    @ServerTimestamp val lastUpdated: Timestamp? = null,
    @ServerTimestamp val createdAt: Timestamp? = null
)

@IgnoreExtraProperties
data class SosAlert(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val familyCode: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val batteryLevel: Int = 100,
    val status: String = "ACTIVE", // "ACTIVE" or "RESOLVED"
    val message: String = "Emergency! I need immediate help.",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val resolvedAt: Timestamp? = null
)
