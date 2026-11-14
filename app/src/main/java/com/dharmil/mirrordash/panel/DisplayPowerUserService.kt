package com.dharmil.mirrordash.panel

import android.os.RemoteException
import java.util.concurrent.TimeUnit

/** Shizuku UserService with a single, fixed display-power operation. */
class DisplayPowerUserService : IDisplayPowerService.Stub() {
    override fun setDisplayPower(on: Boolean): Boolean {
        val action = if (on) DISPLAY_POWER_ON else DISPLAY_POWER_OFF
        val process = ProcessBuilder("cmd", "display", action, DEFAULT_DISPLAY_ID)
            .redirectErrorStream(true)
            .start()
        if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw RemoteException("Android display power command timed out.")
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }.take(MAX_ERROR_TEXT_CHARS)
        if (process.exitValue() != 0) {
            throw RemoteException(output.ifBlank { "Android could not change display power." })
        }
        return true
    }

    override fun destroy() {
        System.exit(0)
    }

    private companion object {
        const val DISPLAY_POWER_ON = "power-on"
        const val DISPLAY_POWER_OFF = "power-off"
        const val DEFAULT_DISPLAY_ID = "0"
        const val COMMAND_TIMEOUT_SECONDS = 3L
        const val MAX_ERROR_TEXT_CHARS = 240
    }
}
