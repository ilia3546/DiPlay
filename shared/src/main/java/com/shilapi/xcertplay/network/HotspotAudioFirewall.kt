package com.shilapi.xcertplay.network

import java.net.Inet4Address
import java.net.InetAddress

/**
 * Keeps at most one root-added firewall rule that accepts UDP from the iPhone on the hotspot interface.
 *
 * Some firmware drops INPUT by default and bans a source that sends unanswered UDP as a port scan, which
 * cuts the one-way RTP audio stream from the iPhone after about 120 packets.
 */
class HotspotAudioFirewall(
    private val run: (String) -> RootShell.Result = { RootShell.run(it) },
    private val log: (String) -> Unit = {},
) {
    /** `-C`, `-I` and `-D` are built from the same arguments, so removal matches exactly what was added. */
    data class Rule(val binary: String, val interfaceName: String, val source: String) {
        fun command(action: String): String {
            val position = if (action == INSERT) " 1" else ""
            return "$binary $action INPUT$position -i $interfaceName -s $source -p udp -j ACCEPT"
        }

        override fun toString(): String = "$binary iface=$interfaceName"
    }

    private val lock = Any()
    private var added: Rule? = null

    /**
     * [stillActive] is checked under the same lock as [remove]: a session that ends before the rule is
     * added never leaves one that nothing will remove.
     */
    fun allow(rule: Rule, stillActive: () -> Boolean) {
        synchronized(lock) {
            if (!stillActive() || added == rule) return
            removeAdded()
            val problem = when (val check = run(rule.command(CHECK))) {
                is RootShell.Result.Unavailable -> check.reason
                // iptables -C exits 1 when no such rule exists.
                is RootShell.Result.Completed -> when (check.exitCode) {
                    1 -> null
                    0 -> "already present, left as it is"
                    else -> "check exit ${check.exitCode}"
                }
            }
            if (problem != null) {
                log("hotspot audio firewall rule not added ($rule): $problem")
                return
            }
            when (val insert = run(rule.command(INSERT))) {
                is RootShell.Result.Unavailable -> log("hotspot audio firewall rule not added ($rule): ${insert.reason}")
                is RootShell.Result.Completed -> if (insert.exitCode == 0) {
                    added = rule
                    log("hotspot audio firewall rule added ($rule)")
                } else {
                    log("hotspot audio firewall rule not added ($rule): exit ${insert.exitCode}")
                }
            }
        }
    }

    /** Removes only the rule that [allow] added; does nothing when there is none. */
    fun remove() {
        synchronized(lock) { removeAdded() }
    }

    private fun removeAdded() {
        val rule = added ?: return
        added = null
        when (val delete = run(rule.command(DELETE))) {
            is RootShell.Result.Unavailable -> log("hotspot audio firewall rule not removed ($rule): ${delete.reason}")
            is RootShell.Result.Completed -> if (delete.exitCode == 0) {
                log("hotspot audio firewall rule removed ($rule)")
            } else {
                log("hotspot audio firewall rule not removed ($rule): exit ${delete.exitCode}")
            }
        }
    }

    companion object {
        const val CHECK = "-C"
        const val INSERT = "-I"
        const val DELETE = "-D"
        private val IPV4 = Regex("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})")
        private val IPV6 = Regex("[0-9A-Fa-f:.]{2,45}")

        /** Null unless [interfaceName] is a plain interface name and [peer] a numeric IP address. */
        fun rule(interfaceName: String?, peer: String?): Rule? {
            if (!RootShell.isSafeInterfaceName(interfaceName) || interfaceName == null || peer == null) return null
            val scope = peer.substringAfter('%', missingDelimiterValue = "")
            if (peer.contains('%') && !RootShell.isSafeInterfaceName(scope)) return null
            val address = numericAddress(peer.substringBefore('%')) ?: return null
            return if (address is Inet4Address) {
                Rule("iptables", interfaceName, "${address.hostAddress}/32")
            } else {
                Rule("ip6tables", interfaceName, "${address.hostAddress}/128")
            }
        }

        private fun numericAddress(text: String): InetAddress? {
            IPV4.matchEntire(text)?.let { match ->
                val octets = match.groupValues.drop(1).map(String::toInt)
                if (octets.any { it > 255 }) return null
                return InetAddress.getByAddress(ByteArray(4) { octets[it].toByte() })
            }
            // Host names cannot contain a colon, so InetAddress reads this as an IPv6 literal.
            if (':' !in text || !IPV6.matches(text)) return null
            return runCatching { InetAddress.getByName(text) }.getOrNull()
        }
    }
}
