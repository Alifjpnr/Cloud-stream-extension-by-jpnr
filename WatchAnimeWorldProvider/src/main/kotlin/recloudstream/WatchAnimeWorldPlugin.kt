package com.watchanimeworld

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class WatchAnimeWorldPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(WatchAnimeWorldProvider())
    }
}
