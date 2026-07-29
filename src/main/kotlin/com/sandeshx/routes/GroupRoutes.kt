package com.sandeshx.routes

import com.sandeshx.models.*
import com.sandeshx.services.GroupNotFoundException
import com.sandeshx.services.GroupPermissionException
import com.sandeshx.services.GroupService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.groupRoutes() {
    authenticate("auth-jwt") {
        route("/api/groups") {
            post {
                val userId = call.currentUserId()
                val req = call.receive<CreateGroupRequest>()
                try {
                    call.respond(GroupService.create(userId, req.name, req.description))
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Invalid request"))
                }
            }

            get("/mine") {
                call.respond(GroupService.mine(call.currentUserId()))
            }

            get("/invite/{code}") {
                val code = call.parameters["code"]!!
                try {
                    call.respond(GroupService.findByInviteCode(code, call.currentUserId()))
                } catch (e: GroupNotFoundException) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(e.message ?: "Not found"))
                }
            }

            post("/join/{code}") {
                val code = call.parameters["code"]!!
                try {
                    call.respond(GroupService.join(code, call.currentUserId()))
                } catch (e: GroupNotFoundException) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(e.message ?: "Not found"))
                }
            }

            route("/{id}") {
                get {
                    val groupId = call.parameters["id"]!!.toLong()
                    try {
                        call.respond(GroupService.getById(groupId, call.currentUserId()))
                    } catch (e: GroupNotFoundException) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse(e.message ?: "Not found"))
                    }
                }

                put {
                    val groupId = call.parameters["id"]!!.toLong()
                    val req = call.receive<UpdateGroupRequest>()
                    try {
                        GroupService.updateInfo(groupId, call.currentUserId(), req.name, req.description, req.avatarUrl)
                        call.respond(GroupService.getById(groupId, call.currentUserId()))
                    } catch (e: GroupPermissionException) {
                        call.respond(HttpStatusCode.Forbidden, ErrorResponse(e.message ?: "Not allowed"))
                    }
                }

                post("/leave") {
                    val groupId = call.parameters["id"]!!.toLong()
                    try {
                        GroupService.leave(groupId, call.currentUserId())
                        call.respond(HttpStatusCode.OK)
                    } catch (e: GroupPermissionException) {
                        call.respond(HttpStatusCode.Forbidden, ErrorResponse(e.message ?: "Not allowed"))
                    }
                }

                get("/members") {
                    val groupId = call.parameters["id"]!!.toLong()
                    call.respond(GroupService.members(groupId))
                }

                post("/members/{userId}/role") {
                    val groupId = call.parameters["id"]!!.toLong()
                    val targetUserId = call.parameters["userId"]!!.toLong()
                    val req = call.receive<UpdateMemberRoleRequest>()
                    val role = runCatching { GroupRole.valueOf(req.role) }.getOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid role"))
                    try {
                        GroupService.updateRole(groupId, call.currentUserId(), targetUserId, role)
                        call.respond(HttpStatusCode.OK)
                    } catch (e: GroupPermissionException) {
                        call.respond(HttpStatusCode.Forbidden, ErrorResponse(e.message ?: "Not allowed"))
                    } catch (e: GroupNotFoundException) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse(e.message ?: "Not found"))
                    }
                }

                delete("/members/{userId}") {
                    val groupId = call.parameters["id"]!!.toLong()
                    val targetUserId = call.parameters["userId"]!!.toLong()
                    try {
                        GroupService.removeMember(groupId, call.currentUserId(), targetUserId)
                        call.respond(HttpStatusCode.OK)
                    } catch (e: GroupPermissionException) {
                        call.respond(HttpStatusCode.Forbidden, ErrorResponse(e.message ?: "Not allowed"))
                    } catch (e: GroupNotFoundException) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse(e.message ?: "Not found"))
                    }
                }

                get("/messages") {
                    val groupId = call.parameters["id"]!!.toLong()
                    try {
                        GroupService.requireMember(groupId, call.currentUserId())
                    } catch (e: GroupPermissionException) {
                        return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse(e.message ?: "Not allowed"))
                    }
                    val before = call.request.queryParameters["before"]?.toLongOrNull()
                    call.respond(GroupService.history(groupId, beforeId = before))
                }

                // Fallback REST send (primary path is the WebSocket "group_message" event —
                // this exists so a message can still be sent if the socket briefly drops).
                post("/messages") {
                    val groupId = call.parameters["id"]!!.toLong()
                    val req = call.receive<GroupMessageRequest>()
                    try {
                        call.respond(GroupService.send(groupId, call.currentUserId(), req.body, req.imageUrl))
                    } catch (e: GroupPermissionException) {
                        call.respond(HttpStatusCode.Forbidden, ErrorResponse(e.message ?: "Not allowed"))
                    } catch (e: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Invalid request"))
                    }
                }
            }
        }
    }
}
