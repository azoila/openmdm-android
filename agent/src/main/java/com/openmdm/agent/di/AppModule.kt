package com.openmdm.agent.di

import android.content.Context
import com.openmdm.agent.BuildConfig
import com.openmdm.agent.data.MDMApi
import com.openmdm.agent.data.MDMRepository
import com.openmdm.agent.data.repository.AppRepositoryImpl
import com.openmdm.agent.domain.repository.IAppRepository
import com.openmdm.agent.domain.repository.IEnrollmentRepository
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
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
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
