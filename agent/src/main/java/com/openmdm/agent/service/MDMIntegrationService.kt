package com.openmdm.agent.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import com.openmdm.agent.data.MDMRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Service that provides MDM integration for other apps via bound service.
 *
 * Supports two communication patterns:
 * 1. Messenger-based (for cross-process IPC)
 * 2. Local Binder (for same-process binding)
 *
 * Intent Actions:
 * - com.openmdm.action.Connect: Standard OpenMDM connect
 * - com.hmdm.action.Connect: HeadwindMDM-compatible connect (for easier migration)
 *
 * Messenger Messages:
 * - MSG_GET_DEVICE_ID: Request device ID (enrollment ID / pairing code)
 * - MSG_GET_SERVER_URL: Request server URL
 * - MSG_GET_STATUS: Request full status
 * - MSG_REGISTER_CALLBACK: Register for status updates
 * - MSG_UNREGISTER_CALLBACK: Unregister callback
 *
 * Response Messages:
 * - MSG_DEVICE_ID: Contains device ID
 * - MSG_SERVER_URL: Contains server URL
 * - MSG_STATUS: Contains full status bundle
 * - MSG_CONNECTED: Sent when MDM is connected
 * - MSG_DISCONNECTED: Sent when MDM is disconnected
 * - MSG_CONFIG_CHANGED: Sent when configuration changes
 */
@AndroidEntryPoint
class MDMIntegrationService : Service() {

    companion object {
        private const val TAG = "MDMIntegrationService"

        // Request messages from client
        const val MSG_GET_DEVICE_ID = 1
        const val MSG_GET_SERVER_URL = 2
        const val MSG_GET_STATUS = 3
        const val MSG_REGISTER_CALLBACK = 4
        const val MSG_UNREGISTER_CALLBACK = 5
        const val MSG_FORCE_CONFIG_UPDATE = 6

        // Response messages to client
        const val MSG_DEVICE_ID = 101
        const val MSG_SERVER_URL = 102
        const val MSG_STATUS = 103
        const val MSG_CONNECTED = 104
        const val MSG_DISCONNECTED = 105
        const val MSG_CONFIG_CHANGED = 106

        // Bundle keys
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_IS_ENROLLED = "is_enrolled"
        const val KEY_IS_MANAGED = "is_managed"
    }

    @Inject
    lateinit var mdmRepository: MDMRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val registeredCallbacks = mutableListOf<Messenger>()
    private val binder = LocalBinder()

    /**
     * Local binder for in-process binding.
     */
    inner class LocalBinder : Binder() {
        fun getService(): MDMIntegrationService = this@MDMIntegrationService

        suspend fun getDeviceId(): String? = mdmRepository.getEnrollmentState().enrollmentId
        suspend fun getServerUrl(): String = mdmRepository.getEnrollmentState().serverUrl
        suspend fun isEnrolled(): Boolean = mdmRepository.getEnrollmentState().isEnrolled
    }

    /**
     * Handler for Messenger-based IPC.
     */
    private inner class IncomingHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            val replyTo = msg.replyTo
            Log.d(TAG, "Received message: ${msg.what} from client")

            when (msg.what) {
                MSG_GET_DEVICE_ID -> {
                    serviceScope.launch {
                        val state = mdmRepository.getEnrollmentState()
                        replyTo?.let { messenger ->
                            val reply = Message.obtain(null, MSG_DEVICE_ID)
                            reply.data.putString(KEY_DEVICE_ID, state.enrollmentId ?: "")
                            try {
                                messenger.send(reply)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to send device ID reply", e)
                            }
                        }
                    }
                }

                MSG_GET_SERVER_URL -> {
                    serviceScope.launch {
                        val state = mdmRepository.getEnrollmentState()
                        replyTo?.let { messenger ->
                            val reply = Message.obtain(null, MSG_SERVER_URL)
                            reply.data.putString(KEY_SERVER_URL, state.serverUrl)
                            try {
                                messenger.send(reply)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to send server URL reply", e)
                            }
                        }
                    }
                }

                MSG_GET_STATUS -> {
                    serviceScope.launch {
                        val state = mdmRepository.getEnrollmentState()
                        replyTo?.let { messenger ->
                            val reply = Message.obtain(null, MSG_STATUS)
                            reply.data.apply {
                                putString(KEY_DEVICE_ID, state.enrollmentId ?: "")
                                putString(KEY_SERVER_URL, state.serverUrl)
                                putBoolean(KEY_IS_ENROLLED, state.isEnrolled)
                                putBoolean(KEY_IS_MANAGED, state.isEnrolled)
                            }
                            try {
                                messenger.send(reply)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to send status reply", e)
                            }
                        }
                    }
                }

                MSG_REGISTER_CALLBACK -> {
                    replyTo?.let { messenger ->
                        if (!registeredCallbacks.contains(messenger)) {
                            registeredCallbacks.add(messenger)
                            Log.d(TAG, "Registered callback, total: ${registeredCallbacks.size}")

                            // Send immediate connected message
                            serviceScope.launch {
                                val state = mdmRepository.getEnrollmentState()
                                val reply = Message.obtain(null, MSG_CONNECTED)
                                reply.data.apply {
                                    putString(KEY_DEVICE_ID, state.enrollmentId ?: "")
                                    putString(KEY_SERVER_URL, state.serverUrl)
                                    putBoolean(KEY_IS_ENROLLED, state.isEnrolled)
                                    putBoolean(KEY_IS_MANAGED, state.isEnrolled)
                                }
                                try {
                                    messenger.send(reply)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to send connected message", e)
                                    registeredCallbacks.remove(messenger)
                                }
                            }
                        }
                    }
                }

                MSG_UNREGISTER_CALLBACK -> {
                    replyTo?.let { messenger ->
                        registeredCallbacks.remove(messenger)
                        Log.d(TAG, "Unregistered callback, remaining: ${registeredCallbacks.size}")
                    }
                }

                MSG_FORCE_CONFIG_UPDATE -> {
                    Log.d(TAG, "Force config update requested")
                    // Trigger MDM sync - this would need to call the MDM service
                    // For now, just notify that config may have changed
                    notifyConfigChanged()
                }

                else -> super.handleMessage(msg)
            }
        }
    }

    private val messenger = Messenger(IncomingHandler())

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "MDMIntegrationService created")

        // Monitor enrollment state changes
        serviceScope.launch {
            mdmRepository.enrollmentState.collectLatest { state ->
                Log.d(TAG, "Enrollment state changed: isEnrolled=${state.isEnrolled}")
                notifyConfigChanged()
            }
        }
    }

    override fun onBind(intent: Intent): IBinder {
        Log.d(TAG, "Service bound with action: ${intent.action}")
        return when (intent.action) {
            "com.openmdm.action.Connect", "com.hmdm.action.Connect" -> {
                // For external apps, return Messenger
                messenger.binder
            }
            else -> {
                // For local binding, return LocalBinder
                binder
            }
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "Service unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        registeredCallbacks.clear()
        Log.d(TAG, "MDMIntegrationService destroyed")
        super.onDestroy()
    }

    private fun notifyConfigChanged() {
        val deadCallbacks = mutableListOf<Messenger>()

        registeredCallbacks.forEach { callback ->
            try {
                val msg = Message.obtain(null, MSG_CONFIG_CHANGED)
                callback.send(msg)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to notify callback, removing", e)
                deadCallbacks.add(callback)
            }
        }

        registeredCallbacks.removeAll(deadCallbacks)
    }
}
