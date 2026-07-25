package com.nap.safe

import android.app.Application
import com.google.android.material.color.DynamicColors

class NapSafeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Apply Material You dynamic color system if supported by the device
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
