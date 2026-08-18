package com.nfaalerts.collector

import android.app.Application

class NfaCollectorApp : Application() {
    val appContainer: AppContainer by lazy { AppContainer() }
}

class AppContainer
