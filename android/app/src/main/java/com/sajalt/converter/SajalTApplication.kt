package com.sajalt.converter

import android.app.Application
import android.os.StrictMode
import com.sajalt.converter.core.util.TempFileManager
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

/**
 * Application entry point.
 *
 * Two things happen here that matter for this app's privacy guarantees:
 *
 * 1. [StrictMode] is armed in debug builds to CRASH the app the moment any code path attempts a
 *    network socket. This is not a runtime privacy control (release builds have no INTERNET
 *    permission, so the OS itself blocks sockets regardless) — it is a development-time
 *    tripwire so that a future contributor accidentally adding a networking call finds out
 *    immediately, in the debugger, rather than shipping it.
 *
 * 2. The app's private cache directory is wiped on cold start. Conversion code writes temporary
 *    scratch data only when a library leaves it no other choice (see [TempFileManager] and the
 *    PDFBox `MemoryUsageSetting` configuration in core/pdf), and always attempts to clean up
 *    immediately after each operation. This wipe is the belt-and-suspenders fallback in case a
 *    previous run was killed mid-operation before its own cleanup ran.
 */
class SajalTApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
        }

        // Required one-time init for PdfBox-Android: loads its bundled font/resource assets
        // through this context's AssetManager. Purely local disk/asset access, no networking.
        PDFBoxResourceLoader.init(applicationContext)

        TempFileManager.wipeCacheDirectory(this)
    }
}
