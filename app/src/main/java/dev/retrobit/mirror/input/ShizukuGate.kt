package dev.retrobit.mirror.input

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import dev.retrobit.mirror.BuildConfig
import dev.retrobit.mirror.IShellInput
import rikka.shizuku.Shizuku

object ShizukuGate {
    const val NOT_INSTALLED = "not_installed"
    const val NOT_RUNNING = "not_running"
    const val DENIED = "denied"
    const val READY = "ready"
    const val UNKNOWN = "unknown"

    private const val REQ = 30
    private const val SERVICE_VERSION = 1

    @Volatile
    var service: IShellInput? = null
        private set

    @Volatile
    var status: String = UNKNOWN
        private set

    private var args: Shizuku.UserServiceArgs? = null
    private var listening = false
    private var appContext: Context? = null

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQ) {
            status = if (grantResult == PackageManager.PERMISSION_GRANTED) READY else DENIED
            if (status == READY) bind(appContext)
            InputHub.refreshGates()
        }
    }

    private val binderListener = Shizuku.OnBinderReceivedListener {
        refreshStatus(appContext)
        if (status == READY) bind(appContext)
        InputHub.refreshGates()
    }

    private val deadListener = Shizuku.OnBinderDeadListener {
        service = null
        status = NOT_RUNNING
        InputHub.refreshGates()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IShellInput.Stub.asInterface(binder)
            Log.i("RetroBit", "shell input connected: ${runCatching { service?.ping() }.getOrNull()}")
            InputHub.refreshGates()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            InputHub.refreshGates()
        }
    }

    fun listen() {
        if (listening) return
        listening = true
        try {
            Shizuku.addRequestPermissionResultListener(permissionListener)
            Shizuku.addBinderReceivedListenerSticky(binderListener)
            Shizuku.addBinderDeadListener(deadListener)
        } catch (t: Throwable) {
            Log.w("RetroBit", "shizuku listen ${t.message}")
        }
    }

    fun refreshStatus(context: Context?): String {
        appContext = context?.applicationContext ?: appContext
        val installed = appContext?.let { isInstalled(it) } == true
        val running = try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
        status = when {
            !running && !installed -> NOT_INSTALLED
            !running -> NOT_RUNNING
            else -> {
                val granted = try {
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                } catch (_: Throwable) {
                    false
                }
                if (granted) READY else DENIED
            }
        }
        return status
    }

    fun requestPermission(context: Context) {
        appContext = context.applicationContext
        listen()
        refreshStatus(context)
        if (status == DENIED || (status == READY && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED)) {
            try {
                Shizuku.requestPermission(REQ)
            } catch (t: Throwable) {
                Log.w("RetroBit", "requestPermission ${t.message}")
            }
        } else if (status == READY) {
            bind(context)
        }
    }

    fun bind(context: Context?) {
        val ctx = context?.applicationContext ?: appContext ?: return
        appContext = ctx
        if (args == null) {
            args = Shizuku.UserServiceArgs(ComponentName(ctx, ShellInputService::class.java))
                .processNameSuffix("shellinput")
                .debuggable(BuildConfig.DEBUG)
                .version(SERVICE_VERSION)
                .daemon(false)
                .tag("retrobit-input")
        }
        try {
            Shizuku.bindUserService(args!!, connection)
        } catch (t: Throwable) {
            Log.w("RetroBit", "bindUserService ${t.message}")
        }
    }

    fun unbind() {
        val current = args ?: return
        try {
            Shizuku.unbindUserService(current, connection, true)
        } catch (_: Throwable) {
        }
        service = null
    }

    private fun isInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
            true
        } catch (_: Throwable) {
            false
        }
    }
}
