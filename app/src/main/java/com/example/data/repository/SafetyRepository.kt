package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.model.SafetyStatus
import com.example.data.model.SosAlert
import com.example.data.model.UserProfile
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class SafetyRepository(private val db: FirebaseFirestore) {

    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private val auth = FirebaseAuth.getInstance()

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    suspend fun saveUserProfile(profile: UserProfile): Result<Unit> {
        return try {
            val uid = requireUserId()
            val docRef = db.collection("users").document(uid)
            val payload = mutableMapOf<String, Any>(
                "userId" to uid,
                "email" to profile.email,
                "displayName" to profile.displayName,
                "role" to profile.role,
                "consentGiven" to profile.consentGiven,
                "familyCode" to profile.familyCode,
                "emergencyContactName" to profile.emergencyContactName,
                "emergencyContactPhone" to profile.emergencyContactPhone,
                "appMode" to profile.appMode,
                "voiceLanguage" to profile.voiceLanguage,
                "officeCheckInTime" to profile.officeCheckInTime,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            // On first creation or if createdAt is missing, write createdAt server timestamp
            val existing = docRef.get().await()
            if (!existing.exists() || !existing.contains("createdAt")) {
                payload["createdAt"] = FieldValue.serverTimestamp()
            }
            docRef.set(payload, SetOptions.merge()).await()
            Result.success(Unit)
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, "users")
            Result.failure(e)
        }
    }

    suspend fun getUserProfile(userId: String): Result<UserProfile?> {
        val docRef = db.collection("users").document(userId)
        return try {
            val snapshot = docRef.get().await()
            val profile = if (snapshot.exists()) snapshot.toObject(UserProfile::class.java) else null
            Result.success(profile)
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, docRef.path)
            Result.failure(e)
        }
    }

    fun observeUserProfile(userId: String): Flow<UserProfile?> = callbackFlow {
        val docRef = db.collection("users").document(userId)
        val registration = docRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                handleFirestoreError(error, OperationType.GET, docRef.path)
                close(error)
                return@addSnapshotListener
            }
            val profile = if (snapshot != null && snapshot.exists()) {
                snapshot.toObject(UserProfile::class.java)
            } else {
                null
            }
            trySend(profile)
        }
        awaitClose { registration.remove() }
    }

    suspend fun updateSafetyStatus(status: SafetyStatus): Result<Unit> {
        return try {
            val uid = requireUserId()
            val docRef = db.collection("safety_status").document(uid)
            val payload = mutableMapOf<String, Any>(
                "userId" to uid,
                "familyCode" to status.familyCode,
                "displayName" to status.displayName,
                "sharingActive" to status.sharingActive,
                "latitude" to status.latitude,
                "longitude" to status.longitude,
                "accuracy" to status.accuracy,
                "batteryLevel" to status.batteryLevel,
                "isCharging" to status.isCharging,
                "activityState" to status.activityState,
                "stepCount" to status.stepCount,
                "lastUpdated" to FieldValue.serverTimestamp()
            )
            val existing = docRef.get().await()
            if (!existing.exists() || !existing.contains("createdAt")) {
                payload["createdAt"] = FieldValue.serverTimestamp()
            }
            docRef.set(payload, SetOptions.merge()).await()
            Result.success(Unit)
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, "safety_status")
            Result.failure(e)
        }
    }

    fun observeSafetyStatus(userId: String): Flow<SafetyStatus?> = callbackFlow {
        val docRef = db.collection("safety_status").document(userId)
        val registration = docRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                handleFirestoreError(error, OperationType.GET, docRef.path)
                close(error)
                return@addSnapshotListener
            }
            val status = if (snapshot != null && snapshot.exists()) {
                snapshot.toObject(SafetyStatus::class.java)
            } else {
                null
            }
            trySend(status)
        }
        awaitClose { registration.remove() }
    }

    fun observeFamilyMembers(familyCode: String): Flow<List<SafetyStatus>> = callbackFlow {
        val collectionRef = db.collection("safety_status")
        val query = collectionRef.whereEqualTo("familyCode", familyCode)
        val registration = query.addSnapshotListener { snapshot, error ->
            if (error != null) {
                handleFirestoreError(error, OperationType.LIST, collectionRef.path)
                close(error)
                return@addSnapshotListener
            }
            val list = snapshot?.documents?.mapNotNull { it.toObject(SafetyStatus::class.java) } ?: emptyList()
            trySend(list)
        }
        awaitClose { registration.remove() }
    }

    suspend fun createSosAlert(alert: SosAlert): Result<String> {
        return try {
            val uid = requireUserId()
            val docRef = db.collection("sos_alerts").document(alert.id.ifBlank { "sos_${System.currentTimeMillis()}" })
            val payload = mapOf(
                "userId" to uid,
                "displayName" to alert.displayName,
                "familyCode" to alert.familyCode,
                "latitude" to alert.latitude,
                "longitude" to alert.longitude,
                "batteryLevel" to alert.batteryLevel,
                "status" to "ACTIVE",
                "message" to alert.message,
                "createdAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
            Result.success(docRef.id)
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, "sos_alerts")
            Result.failure(e)
        }
    }

    suspend fun resolveSosAlert(alertId: String): Result<Unit> {
        val docRef = db.collection("sos_alerts").document(alertId)
        return try {
            docRef.update(
                mapOf(
                    "status" to "RESOLVED",
                    "resolvedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    fun observeSosAlerts(familyCode: String): Flow<List<SosAlert>> = callbackFlow {
        val collectionRef = db.collection("sos_alerts")
        val query = collectionRef.whereEqualTo("familyCode", familyCode)
        val registration = query.addSnapshotListener { snapshot, error ->
            if (error != null) {
                handleFirestoreError(error, OperationType.LIST, collectionRef.path)
                close(error)
                return@addSnapshotListener
            }
            val list = snapshot?.documents?.mapNotNull { it.toObject(SosAlert::class.java) } ?: emptyList()
            trySend(list)
        }
        awaitClose { registration.remove() }
    }
}
