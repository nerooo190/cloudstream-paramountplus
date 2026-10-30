package de.cloudstream.paramountplus

import android.app.Activity
import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ParamountPlusPlugin : Plugin() {
    override fun load(context: Context) {
        val api = ParamountApi(AndroidVault(context.applicationContext))
        registerMainAPI(ParamountPlusProvider(api))
        openSettings = { settingsContext ->
            val activity = settingsContext as? Activity ?: context as? Activity
            if (activity != null && !activity.isFinishing) ParamountSettings(activity, api).show()
        }
    }
}
