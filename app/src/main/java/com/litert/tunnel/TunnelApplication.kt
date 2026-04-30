package com.litert.tunnel

import android.app.Application
import com.litert.tunnel.repository.SettingsRepository
import com.litert.tunnel.repository.TunnelRepository
import com.litert.tunnel.service.TunnelService

class TunnelApplication : Application() {

    val repository = TunnelRepository()
    val settingsRepository by lazy { SettingsRepository(this) }

    override fun onCreate() {
        super.onCreate()
        TunnelService.repository         = repository
        TunnelService.settingsRepository = settingsRepository
    }
}
