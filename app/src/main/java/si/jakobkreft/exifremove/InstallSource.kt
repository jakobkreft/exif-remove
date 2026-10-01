// SPDX-FileCopyrightText: 2026 Jakob Kreft
// SPDX-License-Identifier: GPL-3.0-or-later

package si.jakobkreft.exifremove

import android.content.Context
import android.os.Build

/**
 * Where this copy of the app came from.
 *
 * Google Play forbids pointing users at outside payment pages, so a build
 * installed from Play offers a rating link instead of the donation one. The
 * check is on the installing package rather than a build flavour, so the same
 * APK stays correct whether it arrives from Play, F-Droid or a direct download
 * — which also keeps the F-Droid reproducible build unchanged.
 */
object InstallSource {

    /** Play installs are attributed to the store, or to its older feedback package. */
    private val PLAY_INSTALLERS = setOf(
        "com.android.vending",
        "com.google.android.feedback",
    )

    fun isFromPlayStore(context: Context): Boolean = try {
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager
                .getInstallSourceInfo(context.packageName)
                .installingPackageName
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getInstallerPackageName(context.packageName)
        }
        installer in PLAY_INSTALLERS
    } catch (e: Exception) {
        // An unknown installer is not Play, and the donation link is the
        // safe default everywhere Play's rules do not apply.
        false
    }
}
