package com.openmdm.agent.network

import com.openmdm.agent.BuildConfig
import com.openmdm.library.network.ServerCertificatePinner
import okhttp3.CertificatePinner

/**
 * Reads this app's pinning configuration out of `BuildConfig` and hands it to
 * the library's [ServerCertificatePinner].
 *
 * The pinning logic itself lives in `:library`, so an app that embeds the
 * library — rather than forking this agent — gets pinning too. Only the
 * *source* of the configuration is app-specific: this agent takes it from
 * gradle properties (`-PmdmServerHost`, `-PmdmServerPin`, `-PmdmServerPinBackup`),
 * and the release build refuses to assemble without them.
 */
object AgentCertificatePinner {

    fun fromBuildConfig(): CertificatePinner? = ServerCertificatePinner.create(
        host = BuildConfig.MDM_SERVER_HOST,
        primaryPin = BuildConfig.MDM_SERVER_PIN,
        backupPin = BuildConfig.MDM_SERVER_PIN_BACKUP,
    )
}
