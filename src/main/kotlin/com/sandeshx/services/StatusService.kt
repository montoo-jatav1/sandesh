package com.sandeshx.services

import com.sandeshx.models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Duration
import java.time.Instant

class StatusNotFoundException(message: String) : RuntimeException(message)
class StatusPermissionException(message: String) : RuntimeException(message)

object StatusService {
    private val STATUS_LIFETIME: Duration = Duration.ofHours(24)

    private fun toDto(row: ResultRow, myUserId: Long): StatusDto {
        val statusId = row[Statuses.id].value
        val viewCount = StatusViews.selectAll().where { StatusViews.statusId eq statusId }.count().toInt()
        val viewedByMe = StatusViews.selectAll()
            .where { (StatusViews.statusId eq statusId) and (StatusViews.viewerId eq myUserId) }
            .count() > 0
        val author = Users.selectAll().where { Users.id eq row[Statuses.userId] }.singleOrNull()
        return StatusDto(
            id = statusId,
            userId = row[Statuses.userId],
            authorName = author?.get(Users.displayName),
            authorAvatarUrl = author?.get(Users.avatarUrl),
            type = row[Statuses.type].name,
            contentUrl = row[Statuses.contentUrl],
            textContent = row[Statuses.textContent],
            backgroundColor = row[Statuses.backgroundColor],
            createdAt = row[Statuses.createdAt].epochSecond,
            expiresAt = row[Statuses.expiresAt].epochSecond,
            viewCount = viewCount,
            viewedByMe = viewedByMe
        )
    }

    suspend fun create(
        userId: Long,
        type: StatusType,
        contentUrl: String?,
        textContent: String?,
        backgroundColor: String?
    ): StatusDto = withContext(Dispatchers.IO) {
        if (type == StatusType.PHOTO) {
            require(!contentUrl.isNullOrBlank()) { "contentUrl is required for a photo status" }
        } else {
            require(!textContent.isNullOrBlank()) { "textContent is required for a text status" }
        }
        transaction {
            val now = Instant.now()
            val id = Statuses.insertAndGetId {
                it[Statuses.userId] = userId
                it[Statuses.type] = type
                it[Statuses.contentUrl] = contentUrl
                it[Statuses.textContent] = textContent
                it[Statuses.backgroundColor] = backgroundColor
                it[Statuses.createdAt] = now
                it[Statuses.expiresAt] = now.plus(STATUS_LIFETIME)
            }.value
            toDto(Statuses.selectAll().where { Statuses.id eq id }.single(), userId)
        }
    }

    /** All non-expired statuses in the system, newest first, grouped by author on the
     *  client. There's no "contacts" concept yet in SandeshX, so — unlike WhatsApp,
     *  which only shows contacts' statuses — this shows every active user's status.
     *  Worth revisiting once a contacts/following list exists. */
    suspend fun feed(myUserId: Long): List<StatusDto> = withContext(Dispatchers.IO) {
        transaction {
            val now = Instant.now()
            Statuses.selectAll()
                .where { Statuses.expiresAt greater now }
                .orderBy(Statuses.createdAt, SortOrder.DESC)
                .map { toDto(it, myUserId) }
        }
    }

    suspend fun mine(userId: Long): List<StatusDto> = withContext(Dispatchers.IO) {
        transaction {
            Statuses.selectAll()
                .where { Statuses.userId eq userId }
                .orderBy(Statuses.createdAt, SortOrder.DESC)
                .map { toDto(it, userId) }
        }
    }

    suspend fun markViewed(statusId: Long, viewerId: Long) = withContext(Dispatchers.IO) {
        transaction {
            val exists = Statuses.selectAll().where { Statuses.id eq statusId }.count() > 0
            if (!exists) throw StatusNotFoundException("Status not found or expired")
            val alreadyViewed = StatusViews.selectAll()
                .where { (StatusViews.statusId eq statusId) and (StatusViews.viewerId eq viewerId) }
                .count() > 0
            if (!alreadyViewed) {
                StatusViews.insert {
                    it[StatusViews.statusId] = statusId
                    it[StatusViews.viewerId] = viewerId
                    it[StatusViews.viewedAt] = Instant.now()
                }
            }
        }
    }

    suspend fun viewers(statusId: Long, requesterId: Long): List<StatusViewerDto> = withContext(Dispatchers.IO) {
        transaction {
            val status = Statuses.selectAll().where { Statuses.id eq statusId }.singleOrNull()
                ?: throw StatusNotFoundException("Status not found")
            if (status[Statuses.userId] != requesterId) {
                throw StatusPermissionException("Only the author can see who viewed this status")
            }
            (StatusViews innerJoin Users)
                .selectAll()
                .where { StatusViews.statusId eq statusId }
                .map {
                    StatusViewerDto(
                        userId = it[Users.id].value,
                        displayName = it[Users.displayName],
                        phoneNumber = it[Users.phoneNumber],
                        viewedAt = it[StatusViews.viewedAt].epochSecond
                    )
                }
        }
    }

    suspend fun delete(statusId: Long, requesterId: Long) = withContext(Dispatchers.IO) {
        transaction {
            val status = Statuses.selectAll().where { Statuses.id eq statusId }.singleOrNull()
                ?: throw StatusNotFoundException("Status not found")
            if (status[Statuses.userId] != requesterId) {
                throw StatusPermissionException("You can only delete your own status")
            }
            org.jetbrains.exposed.sql.transactions.TransactionManager.current()
                .exec("DELETE FROM statuses WHERE id = $statusId")
        }
    }
}
