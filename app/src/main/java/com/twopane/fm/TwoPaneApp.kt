package com.twopane.fm

import android.app.Application
import com.twopane.fm.util.ToolLoader

class TwoPaneApp : Application() {
    lateinit var toolLoader: ToolLoader
        private set

    override fun onCreate() {
        super.onCreate()
        toolLoader = ToolLoader(this)
        toolLoader.init()
    }
}
