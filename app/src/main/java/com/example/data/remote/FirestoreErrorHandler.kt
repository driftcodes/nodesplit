package com.example.data.remote

import android.util.Log
import com.example.BuildConfig
import com.google.firebase.auth.FirebaseAuth
import org.json.JSONArray
import org.json.JSONObject

enum class OperationType(val value: String) {
    CREATE("create"),
    UPDATE("update"),
    DELETE("delete"),
    LIST("list"),
    GET("get"),
    WRITE("write"),
}

private fun maskEmailForLog(email: String?): String {
    if (email.isNullOrBlank()) return "none"
    val parts = email.split("@")
    if (parts.size != 2) return "***"
    val local = parts[0]
    val domain = parts[1]
    val prefix = local.take(2)
    return "${prefix}***@$domain"
}

private fun maskUidForLog(uid: String?): String {
    if (uid.isNullOrBlank()) return "unauthenticated"
    return if (uid.length <= 6) "***" else "${uid.take(4)}...${uid.takeLast(2)}"
}

fun handleFirestoreError(exception: Exception, operationType: OperationType, path: String?): String {
    // PRIV-02: Release builds log only the operation and the error code, never paths (which contain
    // user ids), emails or message text.
    if (!BuildConfig.DEBUG) {
        val code = (exception as? com.google.firebase.firestore.FirebaseFirestoreException)?.code?.name
            ?: exception.javaClass.simpleName
        val summary = "Firestore ${operationType.value} failed: $code"
        Log.e("FirestoreError", summary)
        return summary
    }
    val auth = FirebaseAuth.getInstance()
    val currentUser = auth.currentUser

    val providerInfoList = currentUser?.providerData?.map { provider ->
        JSONObject().apply {
            put("providerId", provider.providerId)
            put("emailMasked", maskEmailForLog(provider.email))
        }
    } ?: emptyList()

    val authInfoJson = JSONObject().apply {
        put("userMasked", maskUidForLog(currentUser?.uid))
        put("emailMasked", maskEmailForLog(currentUser?.email))
        put("emailVerified", currentUser?.isEmailVerified)
        put("providerInfo", JSONArray(providerInfoList))
    }

    val sanitizedError = (exception.message ?: exception.toString())
        .replace(Regex("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}"), "[redacted-email]")

    val errorInfoJson = JSONObject().apply {
        put("error", sanitizedError)
        put("operationType", operationType.value)
        put("path", path)
        put("authInfo", authInfoJson)
    }

    val jsonString = errorInfoJson.toString()
    Log.e("FirestoreError", "Firestore Error: $jsonString")
    return jsonString
}
