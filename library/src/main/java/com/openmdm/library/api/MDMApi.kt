package com.openmdm.library.api

import retrofit2.Response
import retrofit2.http.*

/**
 * OpenMDM Server API Interface
 *
 * Retrofit interface for communicating with OpenMDM server.
 */
interface MDMApi {

    @POST("agent/enroll")
    suspend fun enroll(@Body request: EnrollmentRequest): Response<EnrollmentResponse>

    @POST("agent/heartbeat")
    suspend fun heartbeat(
        @Header("Authorization") token: String,
        @Body request: HeartbeatRequest
    ): Response<HeartbeatResponse>

    @GET("agent/config")
    suspend fun getConfig(
        @Header("Authorization") token: String
    ): Response<DeviceConfigResponse>

    @GET("agent/commands/pending")
    suspend fun getPendingCommands(
        @Header("Authorization") token: String
    ): Response<PendingCommandsResponse>

    @POST("agent/commands/{commandId}/ack")
    suspend fun acknowledgeCommand(
        @Header("Authorization") token: String,
        @Path("commandId") commandId: String
    ): Response<Unit>

    @POST("agent/commands/{commandId}/complete")
    suspend fun completeCommand(
        @Header("Authorization") token: String,
        @Path("commandId") commandId: String,
        @Body result: CommandResultRequest
    ): Response<Unit>

    @POST("agent/commands/{commandId}/fail")
    suspend fun failCommand(
        @Header("Authorization") token: String,
        @Path("commandId") commandId: String,
        @Body error: CommandErrorRequest
    ): Response<Unit>

    @POST("agent/push-token")
    suspend fun registerPushToken(
        @Header("Authorization") token: String,
        @Body request: PushTokenRequest
    ): Response<Unit>

    @HTTP(method = "DELETE", path = "agent/push-token", hasBody = true)
    suspend fun unregisterPushToken(
        @Header("Authorization") token: String,
        @Body request: PushTokenRequest
    ): Response<Unit>

    @POST("agent/events")
    suspend fun reportEvent(
        @Header("Authorization") token: String,
        @Body request: EventRequest
    ): Response<Unit>

    @POST("agent/refresh-token")
    suspend fun refreshToken(
        @Body request: RefreshTokenRequest
    ): Response<TokenResponse>
}
