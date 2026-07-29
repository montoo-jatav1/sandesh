package com.sandeshx.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import com.sandeshx.models.Users
import com.sandeshx.models.Messages
import com.sandeshx.models.MediaFiles
import com.sandeshx.models.Channels
import com.sandeshx.models.ChannelSubscribers
import com.sandeshx.models.ChannelPosts
import com.sandeshx.models.ChatPreferences
import com.sandeshx.models.BlockedUsers
import com.sandeshx.models.Reports
import com.sandeshx.models.Groups
import com.sandeshx.models.GroupMembers
import com.sandeshx.models.GroupMessages
import com.sandeshx.models.Statuses
import com.sandeshx.models.StatusViews

object DatabaseFactory {

    fun init() {
        val config = HikariConfig().apply {
            jdbcUrl = System.getenv("DATABASE_URL") ?: "jdbc:postgresql://localhost:5432/sandeshx"
            driverClassName = "org.postgresql.Driver"
            username = System.getenv("DATABASE_USER") ?: "sandeshx"
            password = System.getenv("DATABASE_PASSWORD") ?: "sandeshx"
            maximumPoolSize = 10
            // Keep the pool fully warm instead of lazily opening connections under
            // load — a cold connection open (TCP + TLS + Postgres auth handshake) can
            // easily add 100-300ms+ on a hosted DB, which was showing up as random
            // extra latency on whichever request happened to need a new connection.
            minimumIdle = 10
            connectionTimeout = 5000
            isAutoCommit = false
            // REPEATABLE_READ was stricter (and slower, more lock contention under
            // concurrent writers) than this app actually needs — a chat app doing
            // simple single-row inserts/updates has no cross-row consistency
            // requirement that justifies it. READ_COMMITTED is Postgres's own
            // default and is enough here.
            transactionIsolation = "TRANSACTION_READ_COMMITTED"
        }
        val dataSource = HikariDataSource(config)
        Database.connect(dataSource)

        transaction {
            // NOTE: ChatPreferences/BlockedUsers/Reports were previously missing from this
            // list, meaning those tables never actually got created — mute/archive/
            // favourite/nickname/wallpaper-per-chat and block/report were silently
            // broken (every query against them would fail with "relation does not exist").
            SchemaUtils.createMissingTablesAndColumns(
                Users, Messages, MediaFiles, Channels, ChannelSubscribers, ChannelPosts,
                ChatPreferences, BlockedUsers, Reports,
                Groups, GroupMembers, GroupMessages, Statuses, StatusViews
            )
        }
    }
}
