package com.dharmil.mirrordash.panel

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import rikka.shizuku.Shizuku
import java.util.ArrayDeque
import java.util.concurrent.Executors

object ShizukuPanelController {
    private data class Request(val on: Boolean, val callback: (Boolean, Boolean, String) -> Unit)

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val pending = ArrayDeque<Request>()
    private var service: IDisplayPowerService? = null
    private var binding = false
    private var userServiceArgs: Shizuku.UserServiceArgs? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val remote = IDisplayPowerService.Stub.asInterface(binder)
            val waiting = synchronized(lock) {
                service = remote
                binding = false
                buildList { while (pending.isNotEmpty()) add(pending.removeFirst()) }
            }
            if (remote == null) {
                waiting.forEach { reply(it, false, "Shizuku did not provide the display control service.") }
            } else {
                for (request in waiting) dispatch(remote, request)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            val waiting = synchronized(lock) {
                service = null
                binding = false
                buildList { while (pending.isNotEmpty()) add(pending.removeFirst()) }
            }
            waiting.forEach { reply(it, false, "Shizuku disconnected before panel control was ready.") }
        }
    }

    fun hasPermission(): Boolean = runCatching {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun setPanelPower(context: Context, on: Boolean, callback: (Boolean, Boolean, String) -> Unit) {
        if (!hasPermission()) {
            callback(on, false, "Start Shizuku and grant MirrorDash permission to control the phone panel.")
            return
        }
        var activeService: IDisplayPowerService? = null
        var shouldBind = false
        synchronized(lock) {
            activeService = service
            if (activeService == null) {
                pending.addLast(Request(on, callback))
                if (!binding) {
                    binding = true
                    shouldBind = true
                }
            }
        }
        if (activeService != null) {
            dispatch(activeService, Request(on, callback))
        } else if (shouldBind) {
            bindUserService(context.applicationContext)
        }
    }

    private fun bindUserService(context: Context) {
        try {
            val args = synchronized(lock) {
                userServiceArgs ?: Shizuku.UserServiceArgs(
                    ComponentName(context, DisplayPowerUserService::class.java),
                ).daemon(false).tag("mirrordash-display-power-v1").version(1).also { userServiceArgs = it }
            }
            Shizuku.bindUserService(args, connection)
        } catch (error: Exception) {
            val waiting = synchronized(lock) {
                binding = false
                buildList { while (pending.isNotEmpty()) add(pending.removeFirst()) }
            }
            val message = error.message ?: "Shizuku could not start the display control service."
            waiting.forEach { reply(it, false, message) }
        }
    }

    private fun dispatch(remote: IDisplayPowerService?, request: Request) {
        if (remote == null) {
            reply(request, false, "Shizuku display control is unavailable.")
            return
        }
        worker.execute {
            val result = runCatching { remote.setDisplayPower(request.on) }
            if (result.isSuccess && result.getOrDefault(false)) {
                reply(
                    request,
                    true,
                    if (request.on) "Phone panel is on." else "Phone panel is off; the phone remains unlocked.",
                )
            } else {
                val cause = result.exceptionOrNull()
                val message = if (cause is RemoteException) cause.message else cause?.message
                reply(request, false, message ?: "This phone did not accept the display power command.")
            }
        }
    }

    private fun reply(request: Request, success: Boolean, message: String) {
        mainHandler.post { request.callback(request.on, success, message) }
    }
}
