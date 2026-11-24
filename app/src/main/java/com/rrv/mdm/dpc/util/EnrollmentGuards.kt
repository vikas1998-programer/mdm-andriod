package com.rrv.mdm.dpc.util

import java.net.URI

/**
 * Pure, side-effect free security decisions for enrolment, bootstrap broadcasts and the kiosk PIN.
 * Kept free of Android types so they are unit-testable on the JVM.
 */
object EnrollmentGuards {

    /** Host of [url] lower-cased, or null if it cannot be parsed. */
    fun hostOf(url: String?): String? = try {
        URI(url?.trim().orEmpty()).host?.lowercase()
    } catch (_: Exception) {
        null
    }

    private fun isHttps(url: String?): Boolean = try {
        URI(url?.trim().orEmpty()).scheme.equals("https", ignoreCase = true)
    } catch (_: Exception) {
        false
    }

    /** Loopback, RFC1918, link-local, emulator host alias and *.local names. */
    fun isLocalHost(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        if (h == "localhost" || h == "10.0.2.2" || h == "::1" || h == "[::1]" || h.endsWith(".local")) return true
        val p = h.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4 || p.any { it !in 0..255 }) return false
        return p[0] == 127 || p[0] == 10 ||
            (p[0] == 192 && p[1] == 168) ||
            (p[0] == 172 && p[1] in 16..31) ||
            (p[0] == 169 && p[1] == 254)
    }

    /** True when the enrolment token would travel in cleartext to a host that is not local. */
    fun isCleartextToRemoteHost(serverUrl: String): Boolean {
        val uri = try { URI(serverUrl.trim()) } catch (_: Exception) { return true }
        if (uri.scheme.equals("https", ignoreCase = true)) return false
        return !isLocalHost(uri.host)
    }

    /**
     * Whether a server-supplied config may replace the enrolment host. Same host is fine;
     * a different host is only trusted when the enrolment response itself came over HTTPS.
     */
    fun isServerConfigTrusted(enrolmentUrl: String, configApiBaseUrl: String): Boolean {
        val enrolHost = hostOf(enrolmentUrl)
        val configHost = hostOf(configApiBaseUrl)
        if (enrolHost == null || configHost == null) return false
        return enrolHost == configHost || isHttps(enrolmentUrl)
    }

    /** Dev-only bootstrap broadcasts: debug builds on a device that is not yet enrolled. */
    fun allowDevBootstrap(isDebugBuild: Boolean, isEnrolled: Boolean): Boolean =
        isDebugBuild && !isEnrolled

    /** Kiosk admin PIN: only the admin-configured PIN is accepted; blank config denies everything. */
    fun isKioskPinValid(entered: String, configuredPin: String?): Boolean {
        val configured = configuredPin?.trim().orEmpty()
        return configured.isNotEmpty() && entered.trim() == configured
    }

    /** Message shown when an admin PIN prompt is used but no PIN has been configured by the admin. */
    const val NO_ADMIN_PIN_MESSAGE =
        "No admin PIN is configured. Ask your administrator to set one in the policy or unlock remotely."

    /** First non-blank configured PIN (lock-command PIN, then policy PIN); null when none. Never a built-in default. */
    fun resolveAdminPin(vararg configured: String?): String? =
        configured.firstOrNull { !it.isNullOrBlank() }?.trim()

    /** Admin PIN check shared by every admin prompt: only the admin-configured PIN is accepted. */
    fun isAdminPinValid(entered: String, configuredPin: String?): Boolean = isKioskPinValid(entered, configuredPin)
}
