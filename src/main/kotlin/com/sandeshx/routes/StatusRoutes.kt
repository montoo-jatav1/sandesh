package com.sandeshx.routes

import com.sandeshx.models.*
import com.sandeshx.services.StatusNotFoundException
import com.sandeshx.services.StatusPermissionException
import com.sandeshx.services.StatusService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.statusRoutes() {
    authenticate("auth-jwt") {
        route("/api/status") {
            post {
                val userId = call.currentUserId()
                val req = call.receive<CreateStatusRequest>()
                val type = runCatching { StatusType.valueOf(req.type) }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("type must be PHOTO or TEXT"))
                try {
                    call.respond(StatusService.create(userId, type, req.contentUrl, req.textContent, req.backgroundColor))
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Invalid request"))
                }
            }

            get("/feed") {
                call.respond(StatusService.feed(call.currentUserId()))
            }

            get("/mine") {
                call.respond(StatusService.mine(call.currentUserId()))
            }

            post("/{id}/view") {
                val statusId = call.parameters["id"]!!.toLong()
                try {
                    StatusService.markViewed(statusId, call.currentUserId())
                    call.respond(HttpStatusCode.OK)
                } catch (e: StatusNotFoundException) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(e.message ?: "Not found"))
                }
            }

            get("/{id}/viewers") {
                val statusId = call.parameters["id"]!!.toLong()
                try {
                    call.respond(StatusService.viewers(statusId, call.currentUserId()))
                } catch (e: StatusPermissionException) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse(e.message ?: "Not allowed"))
                } catch (e: StatusNotFoundException) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(e.message ?: "Not found"))
                }
            }

            delete("/{id}") {
                val statusId = call.parameters["id"]!!.toLong()
                try {
                    StatusService.delete(statusId, call.currentUserId())
                    call.respond(HttpStatusCode.OK)
                } catch (e: StatusPermissionException) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse(e.message ?: "Not allowed"))
                } catch (e: StatusNotFoundException) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(e.message ?: "Not found"))
                }
            }
        }
    }
}
