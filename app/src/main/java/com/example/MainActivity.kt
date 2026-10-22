package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.data.repository.LedgerRepository
import com.example.ui.auth.authStateFlow
import com.example.ui.auth.signOutUser
import com.example.ui.screens.AuthGateScreen
import com.example.ui.screens.MainLedgerScreen
import com.example.ui.screens.PendingApprovalScreen
import com.example.ui.theme.VeloLedgerTheme
import com.example.ui.viewmodel.LedgerViewModel
import com.example.ui.viewmodel.WorkspaceSessionState
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VeloLedgerTheme {
                VeloLedgerAppRoot()
            }
        }
    }
}

@Composable
fun VeloLedgerAppRoot(auth: FirebaseAuth = Firebase.auth) {
    val currentUser by auth.authStateFlow().collectAsStateWithLifecycle(initialValue = auth.currentUser)
    val user = currentUser
    var signOutEpoch by rememberSaveable { mutableIntStateOf(0) }

    if (user == null) {
        AuthGateScreen(
            onAuthSuccess = {
                // Auth state flow will emit the authenticated user automatically
            }
        )
    } else {
        AuthenticatedSessionContent(
            currentUserId = user.uid,
            currentUserEmail = user.email ?: "",
            currentUserDisplayName = user.displayName ?: "",
            sessionEpoch = signOutEpoch,
            onSignedOut = {
                signOutEpoch++
            }
        )
    }
}

@Composable
private fun AuthenticatedSessionContent(
    currentUserId: String,
    currentUserEmail: String,
    currentUserDisplayName: String,
    sessionEpoch: Int,
    onSignedOut: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    val viewModel: LedgerViewModel = viewModel(
        key = "${currentUserId}_session_${sessionEpoch}",
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) {
                    "APPLICATION_KEY missing from CreationExtras"
                }
                val databaseId = app.getString(R.string.firestore_database_id)
                val db = FirebaseFirestore.getInstance(databaseId)
                val authInstance = FirebaseAuth.getInstance()
                LedgerViewModel(
                    repository = LedgerRepository(db, authInstance),
                    currentUserId = currentUserId,
                    currentUserEmail = currentUserEmail,
                    currentUserDisplayName = currentUserDisplayName
                )
            }
        }
    )

    val sessionState by viewModel.sessionState.collectAsStateWithLifecycle()

    when (val state = sessionState) {
        is WorkspaceSessionState.Loading -> {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "Syncing NodeSplit Cloud Workspace...",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
            }
        }

        is WorkspaceSessionState.Error -> {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Unable to Load Ledger Workspace",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(18.dp))
                    Button(onClick = viewModel::initializeWorkspaceAndProfile) {
                        Text("Retry Connection")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            signOutUser(credentialManager, scope) {
                                onSignedOut()
                            }
                        }
                    ) {
                        Text("Sign Out")
                    }
                }
            }
        }

        is WorkspaceSessionState.Ready -> {
            if (!state.profile.isApproved) {
                PendingApprovalScreen(
                    profile = state.profile,
                    onRefreshCheck = viewModel::initializeWorkspaceAndProfile,
                    onSignOut = {
                        signOutUser(credentialManager, scope) {
                            onSignedOut()
                        }
                    }
                )
            } else {
                MainLedgerScreen(
                    viewModel = viewModel,
                    workspace = state.workspace,
                    profile = state.profile,
                    onSignOut = {
                        signOutUser(credentialManager, scope) {
                            onSignedOut()
                        }
                    }
                )
            }
        }
    }
}
