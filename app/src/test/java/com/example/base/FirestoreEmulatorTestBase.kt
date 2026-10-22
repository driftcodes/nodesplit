package com.example.base

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.net.HttpURLConnection
import java.net.URL

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
@LooperMode(LooperMode.Mode.INSTRUMENTATION_TEST)
abstract class FirestoreEmulatorTestBase {

    protected lateinit var firestore: FirebaseFirestore
    protected lateinit var auth: FirebaseAuth
    protected lateinit var databaseId: String

    @Before
    open fun setUpFirebase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resId = context.resources.getIdentifier("firestore_database_id", "string", context.packageName)
        databaseId = if (resId != 0) context.getString(resId) else "(default)"

        val app = if (FirebaseApp.getApps(context).isEmpty()) {
            val newApp = FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setApplicationId("com.aistudio.veloledger.vpnbook")
                    .setProjectId(PROJECT_ID)
                    .setApiKey("fake-api-key-for-emulator")
                    .build()
            )
            val newFirestore = FirebaseFirestore.getInstance(newApp, databaseId)
            newFirestore.useEmulator(EMULATOR_HOST, FIRESTORE_PORT)
            newFirestore.firestoreSettings = FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
                .build()

            val newAuth = FirebaseAuth.getInstance(newApp)
            newAuth.useEmulator(EMULATOR_HOST, AUTH_PORT)
            newApp
        } else {
            FirebaseApp.getInstance()
        }

        firestore = FirebaseFirestore.getInstance(app, databaseId)
        auth = FirebaseAuth.getInstance(app)
    }

    @After
    open fun tearDownFirebase() {
        auth.signOut()
    }

    /**
     * Signs in like a real Google account: the Auth emulator accepts an unsigned JSON id token, so
     * the token carries a verified email, which the rules require for self-approval.
     */
    protected suspend fun signInTestUser(email: String): String = withContext(Dispatchers.IO) {
        withTimeout(AUTH_TIMEOUT_MS) {
            val fakeIdToken = """{"sub":"${email.replace("@", "_")}","email":"$email","email_verified":true}"""
            val credential = GoogleAuthProvider.getCredential(fakeIdToken, null)
            val result = auth.signInWithCredential(credential).await()
            checkNotNull(result.user?.uid) { "User auth failed" }
        }
    }

    /**
     * SEC-04: The app can no longer create config/workspace. Tests seed it the way the owner does in
     * the console, through the emulator's admin ("Bearer owner") REST access, which skips the rules.
     */
    protected suspend fun seedWorkspace(adminUid: String, costPerGbToman: Long = 1_000L) = withContext(Dispatchers.IO) {
        val url = URL(
            "http://$EMULATOR_HOST:$FIRESTORE_PORT/v1/projects/$PROJECT_ID/databases/$databaseId/documents/config/workspace"
        )
        val now = "2020-01-01T00:00:00Z"
        val body = """
            {"fields": {
              "adminUid": {"stringValue": "$adminUid"},
              "adminEmail": {"stringValue": ""},
              "businessName": {"stringValue": "NodeSplit VPN"},
              "costPerGbToman": {"integerValue": "$costPerGbToman"},
              "adminProfitPercent": {"integerValue": "50"},
              "settingsRevision": {"integerValue": "1"},
              "createdAt": {"timestampValue": "$now"},
              "updatedAt": {"timestampValue": "$now"}
            }}
        """.trimIndent()
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "PATCH"
            doOutput = true
            setRequestProperty("Authorization", "Bearer owner")
            setRequestProperty("Content-Type", "application/json")
        }
        connection.outputStream.use { it.write(body.toByteArray()) }
        check(connection.responseCode in 200..299) { "Seeding workspace failed: ${connection.responseCode}" }
        connection.disconnect()
    }

    /** Clears every document between tests so they do not depend on each other. */
    protected suspend fun clearEmulatorData() = withContext(Dispatchers.IO) {
        val url = URL("http://$EMULATOR_HOST:$FIRESTORE_PORT/emulator/v1/projects/$PROJECT_ID/databases/$databaseId/documents")
        val connection = (url.openConnection() as HttpURLConnection).apply { requestMethod = "DELETE" }
        connection.responseCode
        connection.disconnect()
    }

    private companion object {
        const val EMULATOR_HOST = "127.0.0.1"
        const val FIRESTORE_PORT = 8085
        const val AUTH_PORT = 9099
        const val PROJECT_ID = "demo-no-project"
        const val AUTH_TIMEOUT_MS = 5000L
    }
}
