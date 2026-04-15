package com.openmdm.agent.di

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import com.openmdm.agent.BuildConfig
import com.openmdm.agent.data.MDMApi
import com.openmdm.agent.data.MDMRepository
import com.openmdm.agent.data.local.MDMDatabase
import com.openmdm.agent.data.local.dao.CommandDao
import com.openmdm.agent.data.repository.AppRepositoryImpl
import com.openmdm.agent.domain.repository.IAppRepository
import com.openmdm.agent.domain.repository.IEnrollmentRepository
import com.openmdm.agent.network.ProtocolHeaderInterceptor
import com.openmdm.agent.network.RetryInterceptor
import com.openmdm.agent.network.ServerCertificatePinner
import com.openmdm.agent.security.AndroidKeystoreDeviceIdentity
import com.openmdm.agent.security.DeviceIdentity
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

    @Provides
    @Singleton
    fun provideOkHttpClient(
        protocolHeaderInterceptor: ProtocolHeaderInterceptor,
        retryInterceptor: RetryInterceptor,
    ): OkHttpClient {
        return OkHttpClient.Builder()
            // Protocol header must be stamped FIRST so the retry
            // interceptor's synthetic requests carry it too.
            .addInterceptor(protocolHeaderInterceptor)
            .addInterceptor(retryInterceptor)
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
                ServerCertificatePinner.fromBuildConfig()?.let { pinner ->
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
    fun provideRetrofit(okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl(BuildConfig.MDM_SERVER_URL.let {
                if (it.endsWith("/")) it else "$it/"
            })
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
