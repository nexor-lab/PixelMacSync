package it.luigi.macsync

import android.app.Application

/**
 * DEBUG-ONLY Application that installs the on-device diagnostics journal.
 * Declared only in `src/debug/AndroidManifest.xml`, so beta/release use the
 * default Application and store no diagnostics.
 */
class DebugApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DebugJournal.install(this)
    }
}
