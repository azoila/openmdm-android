package com.openmdm.agent.di

import javax.inject.Qualifier

/**
 * The server URL this device actually talks to: whatever provisioning supplied,
 * falling back to the build-time default. Qualified so it cannot be confused
 * with any other String binding.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ServerUrl
