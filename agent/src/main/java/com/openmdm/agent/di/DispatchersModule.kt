package com.openmdm.agent.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * The dispatcher for off-main-thread work (disk, network, JSON parsing).
 *
 * Injected rather than hardcoded as `Dispatchers.IO` so tests can substitute a
 * TestDispatcher. A ViewModel that reaches for `Dispatchers.IO` directly runs
 * its background work on the real thread pool, where `advanceUntilIdle()` cannot
 * reach it — which is exactly what made LauncherViewModelTest flake: `loadApps`
 * escaped to a real IO thread and clobbered the UI state at a time the test could
 * not control.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Module
@InstallIn(SingletonComponent::class)
object DispatchersModule {
    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
}
