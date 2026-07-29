package com.sandeshx.services

import com.sandeshx.models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.deleteWhere
import java.time.Instant

class GroupPermissionException(message: String) : RuntimeException(message)
class GroupNotFoundException(message: String) : RuntimeException(message)

object GroupService {

    private fun randomInviteCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // no ambiguous chars (0/O, 1/I)
        return (1..8).map { chars.random() }.joinToString("")
    }

    private fun roleOf(groupId: Long, userId: Long): GroupRole? =
        GroupMembers.selectAll()
            .where { (GroupMembers.groupId eq groupId) and (GroupMembers.userId eq userId) }
            .singleOrNull()?.get(GroupMembers.role)

    private fun memberCount(groupId: Long): Int =
        GroupMembers.selectAll().where { GroupMembers.groupId eq groupId }.count().toInt()

    private fun toDto(row: ResultRow, myUserId: Long): GroupDto {
        val groupId = row[Groups.id].value
        return GroupDto(
            id = groupId,
            name = row[Groups.name],
            description = row[Groups.description],
            ownerId = row[Groups.ownerId],
            avatarUrl = row[Groups.avatarUrl],
            inviteCode = row[Groups.inviteCode],
            memberCount = memberCount(groupId),
            myRole = roleOf(groupId, myUserId)?.name
        )
    }

    suspend fun create(ownerId: Long, name: String, description: String?): GroupDto = withContext(Dispatchers.IO) {
        require(name.isNotBlank()) { "Group name is required" }
        transaction {
            var code: String
            do { code = randomInviteCode() } while (
                Groups.selectAll().where { Groups.inviteCode eq code }.count() > 0
            )
            val id = Groups.insertAndGetId {
                it[Groups.name] = name.trim()
                it[Groups.description] = description?.trim()
                it[Groups.ownerId] = ownerId
                it[Groups.inviteCode] = code
                it[Groups.createdAt] = Instant.now()
            }.value
            GroupMembers.insert {
                it[GroupMembers.groupId] = id
                it[GroupMembers.userId] = ownerId
                it[GroupMembers.role] = GroupRole.OWNER
                it[GroupMembers.joinedAt] = Instant.now()
            }
            toDto(Groups.selectAll().where { Groups.id eq id }.single(), ownerId)
        }
    }

    suspend fun mine(userId: Long): List<GroupDto> = withContext(Dispatchers.IO) {
        transaction {
            val groupIds = GroupMembers.selectAll().where { GroupMembers.userId eq userId }.map { it[GroupMembers.groupId] }
            if (groupIds.isEmpty()) return@transaction emptyList()
            Groups.selectAll().where { Groups.id inList groupIds }.map { toDto(it, userId) }
        }
    }

    suspend fun getById(groupId: Long, myUserId: Long): GroupDto = withContext(Dispatchers.IO) {
        transaction {
            val row = Groups.selectAll().where { Groups.id eq groupId }.singleOrNull()
                ?: throw GroupNotFoundException("Group not found")
            toDto(row, myUserId)
        }
    }

    suspend fun findByInviteCode(code: String, myUserId: Long): GroupDto = withContext(Dispatchers.IO) {
        transaction {
            val row = Groups.selectAll().where { Groups.inviteCode eq code.uppercase() }.singleOrNull()
                ?: throw GroupNotFoundException("Invalid invite code")
            toDto(row, myUserId)
        }
    }

    suspend fun join(code: String, userId: Long): GroupDto = withContext(Dispatchers.IO) {
        transaction {
            val row = Groups.selectAll().where { Groups.inviteCode eq code.uppercase() }.singleOrNull()
                ?: throw GroupNotFoundException("Invalid invite code")
            val groupId = row[Groups.id].value
            val alreadyMember = roleOf(groupId, userId) != null
            if (!alreadyMember) {
                GroupMembers.insert {
                    it[GroupMembers.groupId] = groupId
                    it[GroupMembers.userId] = userId
                    it[GroupMembers.role] = GroupRole.MEMBER
                    it[GroupMembers.joinedAt] = Instant.now()
                }
            }
            toDto(row, userId)
        }
    }

    suspend fun leave(groupId: Long, userId: Long) = withContext(Dispatchers.IO) {
        transaction {
            val role = roleOf(groupId, userId) ?: throw GroupPermissionException("Not a member")
            if (role == GroupRole.OWNER) {
                throw GroupPermissionException("The owner can't leave — transfer ownership or delete the group instead")
            }
            org.jetbrains.exposed.sql.transactions.TransactionManager.current()
                .exec("DELETE FROM group_members WHERE group_id = $groupId AND user_id = $userId")
        }
    }

    suspend fun members(groupId: Long): List<GroupMemberDto> = withContext(Dispatchers.IO) {
        transaction {
            (GroupMembers innerJoin Users)
                .selectAll()
                .where { GroupMembers.groupId eq groupId }
                .map {
                    GroupMemberDto(
                        userId = it[Users.id].value,
                        displayName = it[Users.displayName],
                        phoneNumber = it[Users.phoneNumber],
                        avatarUrl = it[Users.avatarUrl],
                        role = it[GroupMembers.role].name
                    )
                }
        }
    }

    suspend fun updateRole(groupId: Long, actingUserId: Long, targetUserId: Long, newRole: GroupRole) =
        withContext(Dispatchers.IO) {
            transaction {
                val actingRole = roleOf(groupId, actingUserId)
                if (actingRole != GroupRole.OWNER && actingRole != GroupRole.ADMIN) {
                    throw GroupPermissionException("Only the owner or an admin can change roles")
                }
                if (newRole == GroupRole.OWNER) {
                    throw GroupPermissionException("Ownership transfer isn't supported yet")
                }
                val targetRole = roleOf(groupId, targetUserId) ?: throw GroupNotFoundException("That user isn't a member")
                if (targetRole == GroupRole.OWNER) {
                    throw GroupPermissionException("Can't change the owner's role")
                }
                GroupMembers.update({ (GroupMembers.groupId eq groupId) and (GroupMembers.userId eq targetUserId) }) {
                    it[role] = newRole
                }
            }
        }

    suspend fun removeMember(groupId: Long, actingUserId: Long, targetUserId: Long) = withContext(Dispatchers.IO) {
        transaction {
            val actingRole = roleOf(groupId, actingUserId)
            if (actingRole != GroupRole.OWNER && actingRole != GroupRole.ADMIN) {
                throw GroupPermissionException("Only the owner or an admin can remove members")
            }
            val targetRole = roleOf(groupId, targetUserId) ?: throw GroupNotFoundException("That user isn't a member")
            if (targetRole == GroupRole.OWNER) {
                throw GroupPermissionException("Can't remove the owner")
            }
            org.jetbrains.exposed.sql.transactions.TransactionManager.current()
                .exec("DELETE FROM group_members WHERE group_id = $groupId AND user_id = $targetUserId")
        }
    }

    suspend fun updateInfo(groupId: Long, actingUserId: Long, name: String?, description: String?, avatarUrl: String?) =
        withContext(Dispatchers.IO) {
            transaction {
                val actingRole = roleOf(groupId, actingUserId)
                if (actingRole != GroupRole.OWNER && actingRole != GroupRole.ADMIN) {
                    throw GroupPermissionException("Only the owner or an admin can edit group info")
                }
                Groups.update({ Groups.id eq groupId }) {
                    if (name != null) it[Groups.name] = name.trim()
                    if (description != null) it[Groups.description] = description.trim()
                    if (avatarUrl != null) it[Groups.avatarUrl] = avatarUrl
                }
            }
        }

    // ---- Messages ----

    private fun messageRowToDto(row: ResultRow) = GroupMessageDto(
        id = row[GroupMessages.id].value,
        groupId = row[GroupMessages.groupId],
        senderId = row[GroupMessages.senderId],
        body = row[GroupMessages.body],
        imageUrl = row[GroupMessages.imageUrl],
        createdAt = row[GroupMessages.createdAt].epochSecond,
        deleted = row[GroupMessages.deleted],
        pinned = row[GroupMessages.pinned]
    )

    suspend fun requireMember(groupId: Long, userId: Long) = withContext(Dispatchers.IO) {
        transaction {
            roleOf(groupId, userId) ?: throw GroupPermissionException("Not a member of this group")
        }
    }

    suspend fun send(groupId: Long, senderId: Long, body: String?, imageUrl: String?): GroupMessageDto =
        withContext(Dispatchers.IO) {
            require(!body.isNullOrBlank() || !imageUrl.isNullOrBlank()) { "Message must have text or an image" }
            transaction {
                if (roleOf(groupId, senderId) == null) throw GroupPermissionException("Not a member of this group")
                val id = GroupMessages.insertAndGetId {
                    it[GroupMessages.groupId] = groupId
                    it[GroupMessages.senderId] = senderId
                    it[GroupMessages.body] = body
                    it[GroupMessages.imageUrl] = imageUrl
                    it[GroupMessages.createdAt] = Instant.now()
                }
                messageRowToDto(GroupMessages.selectAll().where { GroupMessages.id eq id }.single())
            }
        }

    suspend fun history(groupId: Long, limit: Int = 50, beforeId: Long? = null): List<GroupMessageDto> =
        withContext(Dispatchers.IO) {
            transaction {
                var query = GroupMessages.selectAll().where { GroupMessages.groupId eq groupId }
                if (beforeId != null) query = query.andWhere { GroupMessages.id less beforeId }
                query.orderBy(GroupMessages.id, SortOrder.DESC).limit(limit).map(::messageRowToDto).reversed()
            }
        }

    suspend fun memberUserIds(groupId: Long): List<Long> = withContext(Dispatchers.IO) {
        transaction { GroupMembers.selectAll().where { GroupMembers.groupId eq groupId }.map { it[GroupMembers.userId] } }
    }
}
