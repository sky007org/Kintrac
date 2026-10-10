package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.repository.SafetyRepository
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.ConsentScreen
import com.example.ui.screens.GuardianDashboardScreen
import com.example.ui.screens.OnboardingScreen
import com.example.ui.screens.PrivacyPolicyScreen
import com.example.ui.screens.ProtectedDashboardScreen
import com.example.ui.screens.RoleSelectionScreen
import com.example.ui.screens.appSignOut
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.SafetyViewModel
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

enum class AppDestination {
    AUTH,
    ONBOARDING,
    CONSENT,
    ROLE_SELECTION,
    PROTECTED_DASHBOARD,
    GUARDIAN_DASHBOARD,
    PRIVACY_POLICY
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val repository = SafetyRepository(this)
        val viewModelFactory = SafetyViewModel.Factory(repository)

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    FamilySafetyApp(
                        viewModelFactory = viewModelFactory
                    )
                }
            }
        }
    }
}

@Composable
fun FamilySafetyApp(
    viewModelFactory: SafetyViewModel.Factory
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val credentialManager = remember { CredentialManager.create(context) }
    val auth = remember { FirebaseAuth.getInstance() }

    var currentUser by remember { mutableStateOf(auth.currentUser) }
    var currentDestination by remember { mutableStateOf<AppDestination>(AppDestination.AUTH) }
    var previousDestination by remember { mutableStateOf<AppDestination>(AppDestination.AUTH) }
    var hasCompletedOnboarding by remember { mutableStateOf(false) }

    // Silent Auto Sign-In on App Startup
    LaunchedEffect(Unit) {
        if (auth.currentUser == null) {
            val clientId = try {
                context.getString(R.string.default_web_client_id)
            } catch (_: Exception) {
                null
            }
            if (clientId != null) {
                val googleIdOption = GetGoogleIdOption.Builder()
                    .setFilterByAuthorizedAccounts(true)
                    .setServerClientId(clientId)
                    .setAutoSelectEnabled(true)
                    .build()
                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(googleIdOption)
                    .build()
                try {
                    val result = credentialManager.getCredential(context, request)
                    val cred = result.credential
                    if (cred is CustomCredential && cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                        val token = GoogleIdTokenCredential.createFrom(cred.data).idToken
                        val authCred = GoogleAuthProvider.getCredential(token, null)
                        auth.signInWithCredential(authCred).await()
                    }
                } catch (_: Exception) {
                    // Ignore silent sign-in error; user can sign in interactively
                }
            }
        }
    }

    // Observe Auth State
    DisposableEffect(auth) {
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            currentUser = firebaseAuth.currentUser
        }
        auth.addAuthStateListener(listener)
        onDispose {
            auth.removeAuthStateListener(listener)
        }
    }

    if (currentUser == null) {
        if (currentDestination == AppDestination.PRIVACY_POLICY) {
            PrivacyPolicyScreen(
                onNavigateBack = { currentDestination = AppDestination.AUTH }
            )
        } else {
            AuthScreen(
                onAuthSuccess = {
                    currentUser = auth.currentUser
                },
                onViewPrivacyPolicy = {
                    previousDestination = AppDestination.AUTH
                    currentDestination = AppDestination.PRIVACY_POLICY
                }
            )
        }
    } else {
        // Authenticated Session: Inject ViewModel
        val viewModel: SafetyViewModel = viewModel(factory = viewModelFactory)
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()

        // Sync destination based on User Profile state
        LaunchedEffect(uiState.userProfile, hasCompletedOnboarding) {
            val profile = uiState.userProfile
            if (currentDestination == AppDestination.PRIVACY_POLICY) return@LaunchedEffect

            if (!hasCompletedOnboarding && (profile == null || !profile.consentGiven)) {
                currentDestination = AppDestination.ONBOARDING
            } else if (profile == null || !profile.consentGiven) {
                currentDestination = AppDestination.CONSENT
            } else {
                currentDestination = if (profile.role == "guardian") {
                    AppDestination.GUARDIAN_DASHBOARD
                } else {
                    AppDestination.PROTECTED_DASHBOARD
                }
            }
        }

        when (currentDestination) {
            AppDestination.ONBOARDING -> {
                OnboardingScreen(
                    onFinishOnboarding = {
                        hasCompletedOnboarding = true
                        currentDestination = AppDestination.CONSENT
                    }
                )
            }

            AppDestination.CONSENT -> {
                BackHandler {
                    currentDestination = AppDestination.ONBOARDING
                }
                ConsentScreen(
                    onConsentAllow = {
                        viewModel.setConsent(true)
                        currentDestination = AppDestination.ROLE_SELECTION
                    },
                    onConsentDeny = {
                        viewModel.setConsent(false)
                        // Transition directly to Guardian role where user does not broadcast location
                        viewModel.updateRoleAndDetails(
                            role = "guardian",
                            familyCode = uiState.userProfile?.familyCode.takeIf { !it.isNullOrBlank() } ?: "FAM-${(1000..9999).random()}",
                            emergencyName = "",
                            emergencyPhone = ""
                        )
                        currentDestination = AppDestination.GUARDIAN_DASHBOARD
                    },
                    onViewPrivacyPolicy = {
                        previousDestination = AppDestination.CONSENT
                        currentDestination = AppDestination.PRIVACY_POLICY
                    }
                )
            }

            AppDestination.ROLE_SELECTION -> {
                BackHandler {
                    currentDestination = AppDestination.CONSENT
                }
                RoleSelectionScreen(
                    currentProfile = uiState.userProfile,
                    onSaveRole = { role, familyCode, emergencyName, emergencyPhone, appMode, voiceLanguage ->
                        viewModel.updateRoleAndDetails(
                            role = role,
                            familyCode = familyCode,
                            emergencyName = emergencyName,
                            emergencyPhone = emergencyPhone,
                            appMode = appMode,
                            voiceLanguage = voiceLanguage
                        )
                        currentDestination = if (role == "guardian") {
                            AppDestination.GUARDIAN_DASHBOARD
                        } else {
                            AppDestination.PROTECTED_DASHBOARD
                        }
                    }
                )
            }

            AppDestination.PROTECTED_DASHBOARD -> {
                ProtectedDashboardScreen(
                    userProfile = uiState.userProfile,
                    safetyStatus = uiState.ownSafetyStatus,
                    isSharingActive = uiState.isSharingActive,
                    onToggleSharing = { active ->
                        viewModel.toggleSafetySharing(context, active)
                    },
                    onTriggerSos = {
                        viewModel.triggerSos(context)
                    },
                    onTriggerPondAlert = {
                        viewModel.triggerPondAlert(context)
                    },
                    onOfficeCheckIn = { timeStr ->
                        viewModel.checkInOffice(timeStr)
                    },
                    onApplyLeave = { leaveType, reason ->
                        viewModel.applyForLeave(leaveType, reason)
                    },
                    onSetAppMode = { mode ->
                        viewModel.setAppMode(mode)
                    },
                    onSetVoiceLanguage = { lang ->
                        viewModel.setVoiceLanguage(lang)
                    },
                    onNavigateRoleSettings = {
                        currentDestination = AppDestination.ROLE_SELECTION
                    },
                    onViewPrivacyPolicy = {
                        previousDestination = AppDestination.PROTECTED_DASHBOARD
                        currentDestination = AppDestination.PRIVACY_POLICY
                    },
                    onSignOut = {
                        viewModel.toggleSafetySharing(context, false)
                        appSignOut(context, credentialManager, coroutineScope) {
                            currentUser = null
                            currentDestination = AppDestination.AUTH
                        }
                    }
                )
            }

            AppDestination.GUARDIAN_DASHBOARD -> {
                GuardianDashboardScreen(
                    userProfile = uiState.userProfile,
                    familyMembers = uiState.familyMembers,
                    sosAlerts = uiState.sosAlerts,
                    onResolveSos = { alertId ->
                        viewModel.resolveSos(alertId)
                    },
                    onNavigateRoleSettings = {
                        currentDestination = AppDestination.ROLE_SELECTION
                    },
                    onViewPrivacyPolicy = {
                        previousDestination = AppDestination.GUARDIAN_DASHBOARD
                        currentDestination = AppDestination.PRIVACY_POLICY
                    },
                    onSignOut = {
                        appSignOut(context, credentialManager, coroutineScope) {
                            currentUser = null
                            currentDestination = AppDestination.AUTH
                        }
                    }
                )
            }

            AppDestination.PRIVACY_POLICY -> {
                BackHandler {
                    currentDestination = previousDestination
                }
                PrivacyPolicyScreen(
                    onNavigateBack = {
                        currentDestination = previousDestination
                    }
                )
            }

            AppDestination.AUTH -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}
