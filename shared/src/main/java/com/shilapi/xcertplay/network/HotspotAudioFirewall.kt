package com.shilapi.xcertplay.network

import android.content.Context
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Keeps at most one root-added firewall rule that accepts UDP from the iPhone on the hotspot interface.
 *
 * Some firmware drops INPUT by default and bans a source that sends unanswered UDP as a port scan, which
 * cuts the one-way RTP audio stream from the iPhone after about 120 packets.
 *
 * The rule is recorded in [journal] before it is inserted, so one left behind by a killed process is
 * recognised as DiPlay's own and removed later, while an identical rule that someone else added is not.
 */
class HotspotAudioFirewall(
    private val run: (String) -> RootShell.Result = { RootShell.run(it) },
    private val journal: Journal = MemoryJournal(),
) {
    /** `-C`, `-I` and `-D` are built from the same arguments, so removal matches exactly what was added. */
    data class Rule(val binary: String, val interfaceName: String, val source: String) {
        // -w waits for the xtables lock that netd holds while it changes its own rules.
        fun command(action: String): String {
            val position = if (action == INSERT) " 1" else ""
            return "$binary -w $action INPUT$position -i $interfaceName -s $source -p udp -j ACCEPT"
        }

        internal fun encode(): String = "$binary $interfaceName $source"

        override fun toString(): String = "$binary iface=$interfaceName"
    }

    interface Journal {
        fun read(): String?
        fun write(entry: String?)
    }

    class MemoryJournal(private var entry: String? = null) : Journal {
        override fun read(): String? = entry
        override fun write(entry: String?) { this.entry = entry }
    }

    private val lock = Any()
    private var added: Rule? = null
    private var addedOwner: Any? = null

    /**
     * [stillActive] is checked under the same lock as [remove]: a session that ends before the rule is
     * added never leaves one that nothing will remove. [owner] is the session the rule is for.
     */
    fun allow(rule: Rule, owner: Any, log: (String) -> Unit, stillActive: () -> Boolean) {
        synchronized(lock) {
            if (!stillActive()) return
            if (added == rule) {
                addedOwner = owner
                return
            }
            removeAdded(log)
            if (added != null) {
                log("hotspot audio firewall rule not added ($rule): the previous rule is still in place")
                return
            }
            val leftover = leftover()
            if (leftover != null && leftover != rule) delete(leftover, log)
            val problem = when (val check = run(rule.command(CHECK))) {
                is RootShell.Result.Unavailable -> check.reason
                // iptables -C exits 1 when no such rule exists.
                is RootShell.Result.Completed -> when (check.exitCode) {
                    1 -> null
                    0 -> if (leftover == rule) {
                        added = rule
                        addedOwner = owner
                        log("hotspot audio firewall rule from an earlier DiPlay run kept ($rule)")
                        return
                    } else {
                        "already present, left as it is"
                    }
                    else -> "check exit ${check.exitCode}"
                }
            }
            if (problem != null) {
                log("hotspot audio firewall rule not added ($rule): $problem")
                return
            }
            journal.write(rule.encode())
            val insert = run(rule.command(INSERT))
            if (insert is RootShell.Result.Completed && insert.exitCode == 0) {
                added = rule
                addedOwner = owner
                log("hotspot audio firewall rule added ($rule)")
            } else {
                journal.write(null)
                log("hotspot audio firewall rule not added ($rule): ${describe(insert)}")
            }
        }
    }

    /**
     * Removes the rule only while it belongs to [owner], so the end of an old session cannot remove the
     * rule of the next one. A rule left by an earlier run is removed too once no session holds one.
     */
    fun remove(owner: Any?, log: (String) -> Unit) {
        synchronized(lock) {
            if (owner != null && addedOwner === owner) removeAdded(log)
            if (added == null) leftover()?.let { delete(it, log) }
        }
    }

    /** Keeps [added] after a failed `-D`, so the next [remove] tries again. */
    private fun removeAdded(log: (String) -> Unit) {
        val rule = added ?: return
        if (delete(rule, log)) {
            added = null
            addedOwner = null
        }
    }

    private fun delete(rule: Rule, log: (String) -> Unit): Boolean {
        val result = run(rule.command(DELETE))
        // -D exits 1 when the rule is already gone, for example after a reboot.
        val gone = result is RootShell.Result.Completed && (result.exitCode == 0 || result.exitCode == 1)
        if (gone) {
            if (journal.read() == rule.encode()) journal.write(null)
            log("hotspot audio firewall rule removed ($rule)")
        } else {
            log("hotspot audio firewall rule not removed ($rule): ${describe(result)}")
        }
        return gone
    }

    private fun leftover(): Rule? {
        if (added != null) return null
        val entry = journal.read() ?: return null
        return decode(entry) ?: run {
            journal.write(null)
            null
        }
    }

    private fun describe(result: RootShell.Result): String = when (result) {
        is RootShell.Result.Unavailable -> result.reason
        is RootShell.Result.Completed -> "exit ${result.exitCode}"
    }

    companion object {
        const val CHECK = "-C"
        const val INSERT = "-I"
        const val DELETE = "-D"
        private const val PREFS = "diplay_hotspot_audio_firewall"
        private const val KEY_RULE = "rule"
        private val IPV4 = Regex("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})")
        private val IPV6 = Regex("[0-9A-Fa-f:.]{2,45}")
        @Volatile private var shared: HotspotAudioFirewall? = null

        /** One instance per process: the controller of an old connection and of a new one share the rule. */
        fun shared(context: Context): HotspotAudioFirewall = shared ?: synchronized(this) {
            shared ?: run {
                val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                HotspotAudioFirewall(journal = object : Journal {
                    override fun read(): String? = prefs.getString(KEY_RULE, null)
                    override fun write(entry: String?) {
                        prefs.edit().apply { if (entry == null) remove(KEY_RULE) else putString(KEY_RULE, entry) }.commit()
                    }
                }).also { shared = it }
            }
        }

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

        /** Rebuilds a journal entry through [rule], so a changed file cannot put anything else in a command. */
        internal fun decode(entry: String): Rule? {
            val parts = entry.split(' ')
            if (parts.size != 3) return null
            val prefix = if (parts[0] == "iptables") "/32" else "/128"
            if (!parts[2].endsWith(prefix)) return null
            return rule(parts[1], parts[2].removeSuffix(prefix))?.takeIf { it.encode() == entry }
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
