package com.sandeshx.services

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.Notification
import com.sandeshx.models.Users
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.ByteArrayInputStream
import java.io.FileInputStream

object NotificationService {
    private var initialized = false

    private fun ensureInit() {
        if (initialized) return

        // Two ways to supply the Firebase Admin service-account credentials:
        //  - FIREBASE_CREDENTIALS_JSON: paste the *entire contents* of the
        //    service-account JSON file directly as an env var value. This is
        //    the one to use on Render — a plain web service has no persistent
        //    disk to keep an uploaded file on across deploys/restarts, so a
        //    file path is easy to set once and then silently stop working
        //    the next time the service restarts.
        //  - FIREBASE_CREDENTIALS_PATH: a file path, for local dev or hosts
        //    that do have a real persistent filesystem.
        val credsJson = System.getenv("FIREBASE_CREDENTIALS_JSON")
        val credsPath = System.getenv("FIREBASE_CREDENTIALS_PATH")

        val credentialsStream = when {
            !credsJson.isNullOrBlank() -> ByteArrayInputStream(credsJson.toByteArray(Charsets.UTF_8))
            !credsPath.isNullOrBlank() -> runCatching { FileInputStream(credsPath) }.getOrNull()
            else -> null
        }

        if (credentialsStream == null) {
            println(
                "[NotificationService] Neither FIREBASE_CREDENTIALS_JSON nor a valid " +
                    "FIREBASE_CREDENTIALS_PATH is set — push notifications disabled. " +
                    "See README.md for exact setup steps."
            )
            return
        }

        val options = try {
            FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.fromStream(credentialsStream))
                .build()
        } catch (e: Exception) {
            println("[NotificationService] Firebase credentials were set but invalid/unparseable: ${e.message}")
            return
        }
        if (FirebaseApp.getApps().isEmpty()) FirebaseApp.initializeApp(options)
        initialized = true
        println("[NotificationService] Firebase Admin initialized — push notifications enabled.")
    }

    /** Called when a message is sent to a receiver with no active WebSocket connection. */
    fun notifyNewMessage(receiverId: Long, senderDisplayName: String, preview: String) {
        ensureInit()
        if (!initialized) return

        val token = transaction {
            Users.selectAll().where { Users.id eq receiverId }.singleOrNull()?.get(Users.fcmToken)
        } ?: return

        val message = Message.builder()
            .setToken(token)
            .setNotification(
                Notification.builder()
                    .setTitle(senderDisplayName)
                    .setBody(preview)
                    .build()
            )
            .build()

        runCatching { FirebaseMessaging.getInstance().send(message) }
            .onFailure { println("[NotificationService] Push failed: ${it.message}") }
    }
}
