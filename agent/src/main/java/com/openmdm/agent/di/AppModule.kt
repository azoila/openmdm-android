package com.openmdm.agent.di

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import com.openmdm.agent.BuildConfig
import com.openmdm.agent.data.ProvisioningStore
import com.openmdm.agent.di.ServerUrl
import com.openmdm.agent.data.MDMApi
import com.openmdm.agent.data.MDMRepository
import com.openmdm.agent.data.local.MDMDatabase
import com.openmdm.agent.data.local.dao.CommandDao
import com.openmdm.agent.data.repository.AppRepositoryImpl
import com.openmdm.agent.domain.repository.IAppRepository
import com.openmdm.agent.domain.repository.IEnrollmentRepository
import com.openmdm.library.network.AgentEnvelopeInterceptor
import com.openmdm.library.network.ProtocolHeaderInterceptor
import com.openmdm.agent.network.RetryInterceptor
import com.openmdm.agent.network.AgentCertificatePinner
import com.openmdm.library.security.AndroidKeystoreDeviceIdentity
import com.openmdm.library.security.DeviceIdentity
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * The protocol-v2 header interceptor lives in `:library` and is deliberately
     * un-annotated there — a published AAR should not carry javax.inject
     * annotations for consumers who do not use Hilt. So the agent binds it here.
     */
    @Provides
    @Singleton
    fun provideProtocolHeaderInterceptor(): ProtocolHeaderInterceptor =
        ProtocolHeaderInterceptor()

    @Provides
    @Singleton
    fun provideAgentEnvelopeInterceptor(): AgentEnvelopeInterceptor =
        AgentEnvelopeInterceptor()

    @Provides
    @Singleton
    fun provideOkHttpClient(
        protocolHeaderInterceptor: ProtocolHeaderInterceptor,
        retryInterceptor: RetryInterceptor,
        agentEnvelopeInterceptor: AgentEnvelopeInterceptor,
    ): OkHttpClient {
        return OkHttpClient.Builder()
            // Protocol header must be stamped FIRST so the retry
            // interceptor's synthetic requests carry it too.
            .addInterceptor(protocolHeaderInterceptor)
            .addInterceptor(retryInterceptor)
            // Envelope decoding sits INSIDE the retry interceptor so a
            // decoded `retry` action surfaces to it as a retryable 503.
            .addInterceptor(agentEnvelopeInterceptor)
            .apply {
                // TLS certificate pinning is opt-in via BuildConfig.
                // When a valid pin is configured at build time, we
                // install a CertificatePinner that rejects any
                // server TLS cert not matching the expected SPKI
                // hash. Without this, a MITM on the first-enroll
                // network can substitute their own cert and receive
                // the device's public key for pinning against their
                // own server — which is the exact regression the
                // Phase 2b rollout doc calls out.
                //
                // Pins are extracted from BuildConfig so APK release
                // and pin rotation use the same cadence. See
                // ServerCertificatePinner for the gradle property
                // wiring and the README for how to obtain the pin.
                AgentCertificatePinner.fromBuildConfig()?.let { pinner ->
                    certificatePinner(pinner)
                }
            }
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BODY
                    })
                }
            }
            .build()
    }

    @Provides
    @Singleton
    fun provideDeviceIdentity(
        @ApplicationContext context: Context,
    ): DeviceIdentity = AndroidKeystoreDeviceIdentity(context)

    @Provides
    @Singleton
    fun provideProvisioningStore(
        @ApplicationContext context: Context,
    ): ProvisioningStore = ProvisioningStore(context)

    /**
     * The server this device actually talks to.
     *
     * A device provisioned by QR / NFC / `afw#` / zero-touch is told its server in
     * the admin extras bundle, and that is the **only** channel through which it
     * learns it. `BuildConfig.MDM_SERVER_URL` is a build-time default — right for
     * a fleet that forks and rebuilds the agent, useless for one that provisions a
     * stock APK against its own server.
     *
     * So provisioning wins, and the build-time value is the fallback. This is the
     * one place that decision needs to be made: everything downstream (Retrofit,
     * the enrollment use case) reads through here.
     */
    @Provides
    @Singleton
    @ServerUrl
    fun provideServerUrl(@ApplicationContext context: Context): String {
        val provisioned = ProvisioningStore(context).serverUrl
        val effective = provisioned ?: BuildConfig.MDM_SERVER_URL
        return if (effective.endsWith("/")) effective else "$effective/"
    }

    @Provides
    @Singleton
    fun provideRetrofit(
        okHttpClient: OkHttpClient,
        @ServerUrl serverUrl: String,
    ): Retrofit {
        return Retrofit.Builder()
            .baseUrl(serverUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideMDMApi(retrofit: Retrofit): MDMApi {
        return retrofit.create(MDMApi::class.java)
    }

    @Provides
    @Singleton
    fun provideMDMRepository(@ApplicationContext context: Context): MDMRepository {
        return MDMRepository(context)
    }

    // ============================================
    // Room Database
    // ============================================

    @Provides
    @Singleton
    fun provideMDMDatabase(@ApplicationContext context: Context): MDMDatabase {
        return Room.databaseBuilder(
            context,
            MDMDatabase::class.java,
            MDMDatabase.DATABASE_NAME
        ).build()
    }

    @Provides
    @Singleton
    fun provideCommandDao(database: MDMDatabase): CommandDao {
        return database.commandDao()
    }

    // ============================================
    // WorkManager
    // ============================================

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager {
        return WorkManager.getInstance(context)
    }
}

/**
 * Module for binding interfaces to implementations.
 *
 * Uses @Binds for efficient interface-to-implementation binding.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindEnrollmentRepository(impl: MDMRepository): IEnrollmentRepository

    @Binds
    @Singleton
    abstract fun bindAppRepository(impl: AppRepositoryImpl): IAppRepository
}
