package io.synctuary.android

import android.app.Application

/**
 * Application entry point. Currently a no-op; lives here so the manifest's
 * `android:name=".SynctuaryApp"` is wired and ready for Hilt / Koin / a DI
 * graph initializer once the data layer lands.
 *
 * Keep this file thin: any startup work that touches disk, network, or crypto
 * MUST go on a background dispatcher — see ADR (TBD) for the launcher-time
 * budget.
 */
class SynctuaryApp : Application()
