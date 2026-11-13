package com.example.appbackup

import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import org.json.JSONObject
import java.io.File

/** Runs commands with elevated privileges. Swap in a Shizuku-backed impl for non-root. */
interface Shell {
    fun run(cmd: String): Pair<Int, String>

    /** True when commands run as uid 0 (root, or Shizuku started via root). */
    val isRoot: Boolean get() = run("id -u").second.trim() == "0"
}

class RootShell : Shell {
    override fun run(cmd: String): Pair<Int, String> {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out.trim()
    }
}

data class AppInfo(val pkg: String, val label: String)

class BackupEngine(private val ctx: Context, private val sh: Shell = RootShell()) {

    val root: File = File(Environment.getExternalStorageDirectory(), "AppBackups")

    fun listApps(): List<AppInfo> {
        val pm = ctx.packageManager
        return pm.getInstalledApplications(0)
            .filter { it.packageName != ctx.packageName }
            .map { AppInfo(it.packageName, pm.getApplicationLabel(it).toString()) }
            .sortedBy { it.label.lowercase() }
    }

    /** Full backup: APK(s), /data/data, /sdcard/Android/data, /sdcard/Android/obb, /sdcard/Android/media. */
    fun backup(pkg: String): String {
        val pm = ctx.packageManager
        val info = pm.getApplicationInfo(pkg, 0)
        val pkgInfo = pm.getPackageInfo(pkg, 0)
        val dir = File(root, pkg).apply { mkdirs() }
        val d = dir.absolutePath
        val log = StringBuilder()

        sh.run("am force-stop $pkg") // avoid copying live/locked databases

        // 1. APK + split APKs
        val apkDir = File(dir, "apk").apply { mkdirs() }
        val apks = listOf(info.sourceDir) + (info.splitSourceDirs?.toList() ?: emptyList())
        apks.forEach { sh.run("cp '$it' '${apkDir.absolutePath}/'") }

        // 2. Private data, user data, OBB, media
        val targets = mapOf(
            "data" to "/data/data/$pkg",
            "user_data" to "/sdcard/Android/data/$pkg",
            "obb" to "/sdcard/Android/obb/$pkg",
            "media" to "/sdcard/Android/media/$pkg",
        )
        val saved = mutableListOf<String>()
        val privileged = sh.isRoot
        for ((name, path) in targets) {
            if (name == "data" && !privileged) {
                log.append("data: skipped (needs root; Shizuku is in ADB mode)\n")
                continue
            }
            val (exists, _) = sh.run("[ -d '$path' ]")
            if (exists != 0) continue
            val parent = path.substringBeforeLast('/')
            val (code, out) = sh.run("tar -czf '$d/$name.tar.gz' -C '$parent' '$pkg'")
            if (code == 0) saved += name else log.append("$name: $out\n")
        }

        // 3. Metadata for restore
        val meta = JSONObject()
            .put("package", pkg)
            .put("versionName", pkgInfo.versionName)
            .put("versionCode", pkgInfo.longVersionCode)
            .put("apks", apks.map { File(it).name })
            .put("parts", saved)
            .put("time", System.currentTimeMillis())
        File(dir, "meta.json").writeText(meta.toString(2))

        return "Backed up $pkg: apk + $saved\n$log"
    }

    /** Restore: install APKs, extract data, fix ownership and SELinux context. */
    fun restore(pkg: String): String {
        val dir = File(root, pkg)
        val meta = JSONObject(File(dir, "meta.json").readText())
        val parts = meta.getJSONArray("parts").let { a -> (0 until a.length()).map { a.getString(it) } }
        val log = StringBuilder()

        // 1. Install APK(s) (split-aware)
        val tmp = "/data/local/tmp/restore_$pkg"
        sh.run("rm -rf $tmp; mkdir -p $tmp; cp '${dir.absolutePath}'/apk/*.apk $tmp/")
        val (c, out) = sh.run("pm install-multiple -r -d $tmp/*.apk")
        sh.run("rm -rf $tmp")
        if (c != 0) return "Install failed: $out"

        sh.run("am force-stop $pkg")

        // 2. Private data -> needs chown + restorecon
        if ("data" in parts && !sh.isRoot) {
            log.append("data: skipped (needs root; Shizuku is in ADB mode)\n")
        } else if ("data" in parts) {
            val uid = sh.run("stat -c %u /data/data/$pkg").second
            sh.run("rm -rf /data/data/$pkg/*")
            sh.run("tar -xzf '${dir.absolutePath}/data.tar.gz' -C /data/data")
            sh.run("chown -R $uid:$uid /data/data/$pkg")
            sh.run("restorecon -R /data/data/$pkg")
        }

        // 3. Shared-storage parts
        val dest = mapOf(
            "user_data" to "/sdcard/Android/data",
            "obb" to "/sdcard/Android/obb",
            "media" to "/sdcard/Android/media",
        )
        for ((name, path) in dest) {
            if (name !in parts) continue
            sh.run("mkdir -p '$path'")
            val (code, o) = sh.run("tar -xzf '${dir.absolutePath}/$name.tar.gz' -C '$path'")
            if (code != 0) log.append("$name: $o\n")
        }
        return "Restored $pkg\n$log"
    }
}
