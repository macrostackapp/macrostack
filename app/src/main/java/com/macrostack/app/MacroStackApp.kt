package com.macrostack.app

import android.app.Application
import com.macrostack.app.stacking.FusionManager

/** Holds what must outlive a screen — on-phone stacking keeps going if the activity is recreated. */
class MacroStackApp : Application() {
    val fusion: FusionManager by lazy { FusionManager(this) }
}
