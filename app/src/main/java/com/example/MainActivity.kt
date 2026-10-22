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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.OutlinedButton
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
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

/**
 * REL-01: Holds the ViewModelStore of the signed-in session. It survives rotation (it is an
 * activity-scoped ViewModel) but is cleared as soon as the account changes or signs out, so an old
 * session's Firestore listeners never keep running in the background.
 */
class SessionStoreHolder : ViewModel() {
    private var sessionKey: String? = null
    private var store = ViewModelStore()

    fun ownerFor(key: String): ViewModelStoreOwner {
        if (key != sessionKey) {
            store.clear()
            store = ViewModelStore()
            sessionKey = key
        }
        val current = store
        return object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = current
        }
    }

    fun endSession() {
        store.clear()
        store = ViewModelStore()
        sessionKey = null
    }

    override fun onCleared() {
        store.clear()
    }
}

@Composable
fun VeloLedgerAppRoot(auth: FirebaseAuth = Firebase.auth) {
    val currentUser by auth.authStateFlow().collectAsStateWithLifecycle(initialValue = auth.currentUser)
    val user = currentUser
    var signOutEpoch by rememberSaveable { mutableIntStateOf(0) }
    val sessionStores: SessionStoreHolder = viewModel()

    if (user == null) {
        LaunchedEffect(Unit) { sessionStores.endSession() }
        AuthGateScreen(
            onAuthSuccess = {
                // Auth state flow will emit the authenticated user automatically
            }
        )
    } else {
        val sessionOwner = remember(user.uid, signOutEpoch) {
            sessionStores.ownerFor("${user.uid}_$signOutEpoch")
        }
        AuthenticatedSessionContent(
            sessionOwner = sessionOwner,
            currentUserId = user.uid,
            currentUserEmail = user.email ?: "",
            currentUserDisplayName = user.displayName ?: "",
            onSignedOut = {
                sessionStores.endSession()
                signOutEpoch++
            }
        )
    }
}

@Composable
private fun AuthenticatedSessionContent(
    sessionOwner: ViewModelStoreOwner,
    currentUserId: String,
    currentUserEmail: String,
    currentUserDisplayName: String,
    onSignedOut: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    val viewModel: LedgerViewModel = viewModel(
        viewModelStoreOwner = sessionOwner,
        key = "ledger_$currentUserId",
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
                            text = "Loading your workspace…",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
            }
        }

        is WorkspaceSessionState.NotConfigured -> {
            WorkspaceNotConfiguredScreen(
                signedInUid = state.signedInUid,
                onRetry = viewModel::initializeWorkspaceAndProfile,
                onSignOut = {
                    signOutUser(credentialManager, scope) {
                        onSignedOut()
                    }
                }
            )
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
                        text = "Couldn't load your workspace",
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
                        Text("Try again")
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

/**
 * SEC-04: Shown when config/workspace does not exist. The owner creates it once in the Firebase
 * console with their own UID (shown here), so no stranger can claim a fresh workspace.
 */
@Composable
private fun WorkspaceNotConfiguredScreen(
    signedInUid: String,
    onRetry: () -> Unit,
    onSignOut: () -> Unit
) {
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
                text = "Workspace not set up yet",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "If you are the owner, create the document config/workspace in the Firebase console and set adminUid to the ID below. Then tap Try again. If you are a seller, ask the owner to finish setup.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "Your user ID",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SelectionContainer {
                Text(
                    text = signedInUid,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(modifier = Modifier.height(18.dp))
            Button(onClick = onRetry) {
                Text("Try again")
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = onSignOut) {
                Text("Sign out")
            }
        }
    }
}
