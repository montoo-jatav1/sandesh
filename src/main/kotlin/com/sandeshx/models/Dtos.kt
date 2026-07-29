package com.sandeshx.models

import kotlinx.serialization.Serializable

@Serializable
data class SendOtpRequest(val phoneNumber: String)

@Serializable
data class SendOtpResponse(val message: String, val requiresPassword: Boolean = false)

@Serializable
data class VerifyOtpRequest(val phoneNumber: String, val code: String, val password: String? = null)

@Serializable
data class AuthResponse(val accessToken: String, val refreshToken: String, val isNewUser: Boolean, val userId: Long)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class UserProfileDto(
    val id: Long,
    val phoneNumber: String,
    val displayName: String?,
    val avatarUrl: String?,
    val isOnline: Boolean,
    val lastSeenAt: Long?,
    val isBot: Boolean = false
)

@Serializable
data class UpdateProfileRequest(val displayName: String? = null, val avatarUrl: String? = null)

@Serializable
data class SendMessageRequest(val receiverId: Long, val body: String? = null, val imageUrl: String? = null)

@Serializable
data class MessageDto(
    val id: Long,
    val senderId: Long,
    val receiverId: Long,
    val body: String?,
    val imageUrl: String?,
    val status: String,
    val createdAt: Long,
    val readAt: Long?,
    val edited: Boolean = false,
    val deleted: Boolean = false
)

@Serializable
data class PresignedUploadResponse(val uploadUrl: String, val fileUrl: String, val objectKey: String)

@Serializable
data class MediaUploadResponse(val id: Long, val path: String)

@Serializable
data class ErrorResponse(val error: String)

@Serializable
data class SmartRepliesRequest(val peerId: Long)

@Serializable
data class SmartRepliesResponse(val suggestions: List<String>)

@Serializable
data class RewriteRequest(val text: String, val style: String) // style: formal | funny | professional

@Serializable
data class RewriteResponse(val text: String)

@Serializable
data class GrammarFixRequest(val text: String)

@Serializable
data class GrammarFixResponse(val text: String)

@Serializable
data class SummarizeRequest(val peerId: Long)

@Serializable
data class SummarizeResponse(val summary: String)

@Serializable
data class MyraKeyStatusResponse(val hasCustomKey: Boolean)

@Serializable
data class SetMyraKeyRequest(val apiKey: String)

@Serializable
data class ChatPreferencesDto(
    val peerId: Long,
    val mutedUntil: Long? = null,
    val isFavourite: Boolean = false,
    val isArchived: Boolean = false,
    val nicknameForPeer: String? = null,
    val customWallpaperPath: String? = null,
    val disappearingDurationSeconds: Long? = null
)

@Serializable
data class UpdateChatPreferencesRequest(
    val mutedUntil: Long? = null,
    val clearMute: Boolean = false,
    val isFavourite: Boolean? = null,
    val isArchived: Boolean? = null,
    val nicknameForPeer: String? = null,
    val clearNickname: Boolean = false,
    val customWallpaperPath: String? = null,
    val clearWallpaper: Boolean = false,
    val disappearingDurationSeconds: Long? = null,
    val clearDisappearing: Boolean = false
)

@Serializable
data class BlockRequest(val userId: Long)

@Serializable
data class BlockedUserDto(val userId: Long, val blockedAt: Long)

@Serializable
data class ReportRequest(val reportedUserId: Long, val reason: String)

// ---------- Groups ----------

@Serializable
data class CreateGroupRequest(val name: String, val description: String? = null)

@Serializable
data class GroupDto(
    val id: Long,
    val name: String,
    val description: String?,
    val ownerId: Long,
    val avatarUrl: String?,
    val inviteCode: String,
    val memberCount: Int,
    val myRole: String? = null
)

@Serializable
data class GroupMemberDto(
    val userId: Long,
    val displayName: String?,
    val phoneNumber: String,
    val avatarUrl: String?,
    val role: String
)

@Serializable
data class UpdateGroupRequest(val name: String? = null, val description: String? = null, val avatarUrl: String? = null)

@Serializable
data class UpdateMemberRoleRequest(val role: String)

@Serializable
data class GroupMessageRequest(val body: String? = null, val imageUrl: String? = null)

@Serializable
data class GroupMessageDto(
    val id: Long,
    val groupId: Long,
    val senderId: Long,
    val body: String?,
    val imageUrl: String?,
    val createdAt: Long,
    val deleted: Boolean,
    val pinned: Boolean
)

// ---------- Status / Stories ----------

@Serializable
data class CreateStatusRequest(
    val type: String,
    val contentUrl: String? = null,
    val textContent: String? = null,
    val backgroundColor: String? = null
)

@Serializable
data class StatusDto(
    val id: Long,
    val userId: Long,
    val authorName: String?,
    val authorAvatarUrl: String?,
    val type: String,
    val contentUrl: String?,
    val textContent: String?,
    val backgroundColor: String?,
    val createdAt: Long,
    val expiresAt: Long,
    val viewCount: Int,
    val viewedByMe: Boolean
)

@Serializable
data class StatusViewerDto(val userId: Long, val displayName: String?, val phoneNumber: String, val viewedAt: Long)

@Serializable
sealed class WsEvent {
    @Serializable
    data class NewMessage(val message: MessageDto) : WsEvent()
    @Serializable
    data class Presence(val userId: Long, val isOnline: Boolean, val lastSeenAt: Long?) : WsEvent()
    @Serializable
    data class ReadReceipt(val messageId: Long, val readAt: Long) : WsEvent()
    @Serializable
    data class Typing(val userId: Long, val isTyping: Boolean) : WsEvent()
}
