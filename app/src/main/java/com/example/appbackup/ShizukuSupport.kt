package com.example.appbackup

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Runs inside the Shizuku-launched process (uid 2000 in ADB mode, uid 0 in root mode). */
class ShellService : IShellService.Stub() {
    override fun destroy() {
        System.exit(0)
    }

    override fun exec(cmd: String): String {
        val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        return "${p.waitFor()}\n$out"
    }
}

class ShizukuShell(private val svc: IShellService) : Shell {
    override fun run(cmd: String): Pair<Int, String> {
        val r = svc.exec(cmd)
        val i = r.indexOf('\n')
        return r.substring(0, i).trim().toInt() to r.substring(i + 1).trim()
    }
}

object ShellFactory {
    fun shizukuRunning(): Boolean = try { Shizuku.pingBinder() } catch (_: Throwable) { false }

    fun shizukuGranted(): Boolean = try {
        shizukuRunning() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) { false }

    /** Blocking: call from a background thread. */
    fun connectShizuku(ctx: Context, timeoutMs: Long = 10_000): Shell? {
        val latch = CountDownLatch(1)
        var svc: IShellService? = null
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder != null && binder.pingBinder()) {
                    svc = IShellService.Stub.asInterface(binder)
                    latch.countDown()
                }
            }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        val args = Shizuku.UserServiceArgs(ComponentName(ctx.packageName, ShellService::class.java.name))
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(false)
            .version(1)
        Shizuku.bindUserService(args, conn)
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return svc?.let { ShizukuShell(it) }
    }

    fun tryRoot(): Shell? = try {
        val s = RootShell()
        if (s.run("id -u").second == "0") s else null
    } catch (_: Exception) { null }
}
