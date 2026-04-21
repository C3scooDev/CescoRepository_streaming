package it.dogior.hadEnough

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MoviesNChillPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(MoviesNChill())
    }
}
