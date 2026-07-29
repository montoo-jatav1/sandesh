package com.sandeshx.services

import com.sandeshx.models.Users
import com.sandeshx.security.JwtConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant

data class AuthResult(val accessToken: String, val refreshToken: String, val isNewUser: Boolean, val userId: Long)

class AdminPasswordRequiredException(message: String) : Exception(message)

object AuthService {
    private val smsSender: SmsSender = run {
        val accountSid = System.getenv("ACCOUNT_SID")
        val authToken = System.getenv("AUTH_TOKEN")
        val fromNumber = System.getenv("TWILIO_FROM_NUMBER")
        if (!accountSid.isNullOrBlank() && !authToken.isNullOrBlank() && !fromNumber.isNullOrBlank()) {
            TwilioSmsSender(accountSid, authToken, fromNumber)
        } else {
            println("[AuthService] Twilio env vars not fully set (ACCOUNT_SID/AUTH_TOKEN/TWILIO_FROM_NUMBER) — falling back to LogSmsSender. OTPs will only appear in server logs, not real SMS.")
            LogSmsSender()
        }
    }

    private val PHONE_REGEX = Regex("^\\+[1-9][0-9]{7,14}$")

    // One specific account (set via env vars, never hardcoded in source) needs a
    // password in addition to the OTP — an extra layer for the admin/owner login.
    // Configure ADMIN_PHONE_NUMBER + ADMIN_PASSWORD on the server to enable this;
    // without them set, every account just uses normal OTP-only login.
    private fun adminPhoneNumber(): String? = System.getenv("ADMIN_PHONE_NUMBER")?.takeIf { it.isNotBlank() }
    private fun adminPassword(): String? = System.getenv("ADMIN_PASSWORD")?.takeIf { it.isNotBlank() }

    fun requiresAdminPassword(phoneNumber: String): Boolean {
        val adminPhone = adminPhoneNumber() ?: return false
        return phoneNumber == adminPhone
    }

    fun sendOtp(phoneNumber: String) {
        require(PHONE_REGEX.matches(phoneNumber)) { "Invalid phone number format. Use E.164, e.g. +919876543210" }
        val code = OtpService.generateAndStore(phoneNumber)
        smsSender.send(phoneNumber, code)
    }

    suspend fun verifyOtpAndLogin(phoneNumber: String, code: String, password: String?): AuthResult = withContext(Dispatchers.IO) {
        OtpService.verify(phoneNumber, code) // throws OtpInvalidException / OtpRateLimitException on failure

        if (requiresAdminPassword(phoneNumber)) {
            // Constant-time-ish comparison — not perfect, but this guards a single
            // known account, not a general multi-user password store.
            val expected = adminPassword()
            if (expected == null || password == null || !constantTimeEquals(password, expected)) {
                throw AdminPasswordRequiredException("This account needs a password as well as the OTP.")
            }
        }

        transaction {
            val existing = Users.selectAll().where { Users.phoneNumber eq phoneNumber }.singleOrNull()
            val userId = existing?.get(Users.id)?.value ?: Users.insertAndGetId {
                it[Users.phoneNumber] = phoneNumber
                it[Users.createdAt] = Instant.now()
            }.value

            AuthResult(
                accessToken = JwtConfig.generateAccessToken(userId),
                refreshToken = JwtConfig.generateRefreshToken(userId),
                isNewUser = existing == null,
                userId = userId
            )
        }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) result = result or (a[i].code xor b[i].code)
        return result == 0
    }
}
