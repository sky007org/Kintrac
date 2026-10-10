package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.example.data.model.SafetyStatus
import com.example.data.model.SosAlert
import com.example.data.model.UserProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class SafetyRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun saveUserProfile_authenticatedUser_success() = runBlocking {
        val uniqueEmail = "user_${UUID.randomUUID()}@test.com"
        val uid = signInTestUser(uniqueEmail)
        val repository = SafetyRepository(firestore)

        val profile = UserProfile(
            userId = uid,
            email = uniqueEmail,
            displayName = "Test Member",
            role = "protected",
            consentGiven = true,
            familyCode = "FAM999"
        )

        val result = withTimeout(5000L) {
            repository.saveUserProfile(profile)
        }
        assertTrue(result.isSuccess)

        val fetched = withTimeout(5000L) {
            repository.getUserProfile(uid).getOrThrow()
        }
        assertNotNull(fetched)
        assertEquals("FAM999", fetched?.familyCode)
        assertEquals(true, fetched?.consentGiven)
    }

    @Test
    fun updateSafetyStatus_authenticatedUser_updatesAndEmits() = runBlocking {
        val uniqueEmail = "status_${UUID.randomUUID()}@test.com"
        val uid = signInTestUser(uniqueEmail)
        val repository = SafetyRepository(firestore)

        val status = SafetyStatus(
            userId = uid,
            familyCode = "FAM999",
            displayName = "Test Device",
            sharingActive = true,
            latitude = 37.7749,
            longitude = -122.4194,
            accuracy = 5.0f,
            batteryLevel = 92,
            isCharging = true,
            activityState = "WALKING",
            stepCount = 1250
        )

        val result = withTimeout(5000L) {
            repository.updateSafetyStatus(status)
        }
        assertTrue(result.isSuccess)

        val observed = withTimeout(5000L) {
            repository.observeSafetyStatus(uid).first { it != null }
        }
        assertNotNull(observed)
        assertEquals(92, observed?.batteryLevel)
        assertEquals("WALKING", observed?.activityState)
        assertEquals(true, observed?.sharingActive)
    }

    @Test
    fun createSosAlert_authenticatedUser_successAndEmits() = runBlocking {
        val uniqueEmail = "sos_${UUID.randomUUID()}@test.com"
        val uid = signInTestUser(uniqueEmail)
        val repository = SafetyRepository(firestore)

        val alertId = "sos_${UUID.randomUUID()}"
        val alert = SosAlert(
            id = alertId,
            userId = uid,
            displayName = "Alerting User",
            familyCode = "FAM888",
            latitude = 37.7749,
            longitude = -122.4194,
            batteryLevel = 15,
            status = "ACTIVE",
            message = "Need emergency assistance!"
        )

        val createResult = withTimeout(5000L) {
            repository.createSosAlert(alert)
        }
        assertTrue("createSosAlert failed: ${createResult.exceptionOrNull()?.message}", createResult.isSuccess)

        val alerts = withTimeout(5000L) {
            repository.observeSosAlerts("FAM888").first { it.isNotEmpty() }
        }
        assertTrue(alerts.any { it.id == alertId })
    }

    @Test
    fun unauthenticatedAccess_failsSafely() = runBlocking {
        auth.signOut()
        val repository = SafetyRepository(firestore)

        val dummyStatus = SafetyStatus(
            userId = "unauthenticated_user",
            familyCode = "FAM999",
            displayName = "Ghost"
        )
        val result = repository.updateSafetyStatus(dummyStatus)
        assertTrue(result.isFailure)
    }
}
