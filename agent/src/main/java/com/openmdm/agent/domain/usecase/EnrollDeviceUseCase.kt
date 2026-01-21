package com.openmdm.agent.domain.usecase

import android.content.Context
import com.openmdm.agent.BuildConfig
import com.openmdm.agent.data.EnrollmentRequest
import com.openmdm.agent.data.MDMApi
import com.openmdm.agent.domain.repository.IEnrollmentRepository
import com.openmdm.agent.util.DeviceInfoCollector
import com.openmdm.agent.util.SignatureGenerator
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject

/**
 * Use case for enrolling a device with the MDM server.
 *
 * Handles the complete enrollment flow:
 * 1. Collect device information
 * 2. Generate enrollment signature
 * 3. Send enrollment request to server
 * 4. Save enrollment data on success
 */
class EnrollDeviceUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mdmApi: MDMApi,
    private val enrollmentRepository: IEnrollmentRepository,
    private val deviceInfoCollector: DeviceInfoCollector,
    private val signatureGenerator: SignatureGenerator
) {

    /**
     * Enroll the device with the given device code and server URL.
     *
     * @param deviceCode The enrollment code provided by the administrator
     * @param serverUrl The MDM server URL
     * @return Result indicating success or failure with error message
     */
    suspend operator fun invoke(deviceCode: String, serverUrl: String): Result<Unit> {
        return try {
            val deviceInfo = deviceInfoCollector.collectDeviceInfo()
            val timestamp = generateTimestamp()

            val signature = signatureGenerator.generateEnrollmentSignature(
                model = deviceInfo.model,
                manufacturer = deviceInfo.manufacturer,
                osVersion = deviceInfo.osVersion,
                serialNumber = deviceInfo.serialNumber,
                imei = deviceInfo.imei,
                macAddress = deviceInfo.macAddress,
                androidId = deviceInfo.androidId,
                method = "device_code:$deviceCode",
                timestamp = timestamp
            )

            val request = EnrollmentRequest(
                model = deviceInfo.model,
                manufacturer = deviceInfo.manufacturer,
                osVersion = deviceInfo.osVersion,
                sdkVersion = deviceInfo.sdkVersion,
                serialNumber = deviceInfo.serialNumber,
                imei = deviceInfo.imei,
                macAddress = deviceInfo.macAddress,
                androidId = deviceInfo.androidId,
                agentVersion = BuildConfig.VERSION_NAME,
                agentPackage = context.packageName,
                method = "device_code:$deviceCode",
                timestamp = timestamp,
                signature = signature
            )

            val response = mdmApi.enroll(request)
            if (response.isSuccessful) {
                response.body()?.let { body ->
                    enrollmentRepository.saveEnrollment(
                        deviceId = body.deviceId,
                        enrollmentId = body.enrollmentId,
                        token = body.token,
                        refreshToken = body.refreshToken,
                        serverUrl = serverUrl,
                        policyVersion = body.policy?.version
                    )
                    Result.success(Unit)
                } ?: Result.failure(EnrollmentException("Empty response from server"))
            } else {
                val errorBody = response.errorBody()?.string()
                Result.failure(EnrollmentException("Enrollment failed: ${response.code()} - $errorBody"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun generateTimestamp(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }
}

/**
 * Exception thrown when enrollment fails.
 */
class EnrollmentException(message: String) : Exception(message)
