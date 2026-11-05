package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.network.HotspotAudioFirewall.Rule
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HotspotAudioFirewallTest {
    private val commands = mutableListOf<String>()
    private val logs = mutableListOf<String>()
    private val exitCodes = mutableMapOf(HotspotAudioFirewall.CHECK to 1, HotspotAudioFirewall.INSERT to 0, HotspotAudioFirewall.DELETE to 0)
    private val journal = HotspotAudioFirewall.MemoryJournal()
    private val firewall = HotspotAudioFirewall(run = { command ->
        commands += command
        RootShell.Result.Completed(exitCodes.getValue(command.split(' ')[2]), "")
    }, journal = journal)
    private val rule = HotspotAudioFirewall.rule("wlan1", "192.168.43.20")!!
    private val session = Any()
    private val log: (String) -> Unit = { logs += it }

    private fun command(rule: Rule, action: String) = rule.command(action)

    @Test fun buildsAnIpv4RuleForTheIphoneOnTheHotspotInterface() {
        assertEquals(Rule("iptables", "wlan1", "192.168.43.20/32"), rule)
        assertEquals("iptables -w -C INPUT -i wlan1 -s 192.168.43.20/32 -p udp -j ACCEPT", rule.command(HotspotAudioFirewall.CHECK))
        assertEquals("iptables -w -I INPUT 1 -i wlan1 -s 192.168.43.20/32 -p udp -j ACCEPT", rule.command(HotspotAudioFirewall.INSERT))
        assertEquals("iptables -w -D INPUT -i wlan1 -s 192.168.43.20/32 -p udp -j ACCEPT", rule.command(HotspotAudioFirewall.DELETE))
    }

    @Test fun anIpv6IphoneGetsAnIp6tablesRuleWithoutItsScope() {
        val expected = InetAddress.getByName("fe80::1c2:3ff:fe04:506").hostAddress
        assertEquals(Rule("ip6tables", "wlan1", "$expected/128"), HotspotAudioFirewall.rule("wlan1", "fe80::1c2:3ff:fe04:506%wlan1"))
        assertEquals(Rule("ip6tables", "ap0", "${InetAddress.getByName("2001:db8::7").hostAddress}/128"),
            HotspotAudioFirewall.rule("ap0", "2001:db8::7"))
    }

    @Test fun anIpv4MappedIphoneAddressUsesIptables() {
        assertEquals(Rule("iptables", "wlan1", "192.168.43.20/32"), HotspotAudioFirewall.rule("wlan1", "::ffff:192.168.43.20"))
    }

    @Test fun rejectsAnythingButAPlainInterfaceAndANumericAddress() {
        val badInterfaces = listOf(null, "", "wlan1 ", "wlan1;reboot", "wlan1'", "\"wlan1\"", "\$(id)", "a".repeat(16))
        for (name in badInterfaces) assertNull("$name", HotspotAudioFirewall.rule(name, "192.168.43.20"))
        val badPeers = listOf(null, "", "iphone.local", "localhost", "256.1.1.1", "192.168.43", "192.168.43.20;reboot",
            "192.168.43.20 -j DROP", "'192.168.43.20'", "fe80::1%wlan1;reboot", "fe80::1%", "fe80::1 ", "1:2:3:4:5:6:7:8:9",
            "::g", "192.168.43.20/24")
        for (peer in badPeers) assertNull("$peer", HotspotAudioFirewall.rule("wlan1", peer))
    }

    @Test fun aJournalEntryIsRebuiltFromValidatedParts() {
        assertEquals(rule, HotspotAudioFirewall.decode("iptables wlan1 192.168.43.20/32"))
        for (entry in listOf("", "iptables wlan1", "iptables wlan1;id 192.168.43.20/32", "iptables wlan1 192.168.43.20/24",
            "ip6tables wlan1 192.168.43.20/32", "sh wlan1 192.168.43.20/32", "iptables wlan1 192.168.43.20/32 -j DROP")) {
            assertNull(entry, HotspotAudioFirewall.decode(entry))
        }
    }

    @Test fun addsTheRuleOnceAndRemovesExactlyThatRule() {
        firewall.allow(rule, session, log) { true }
        firewall.allow(rule, session, log) { true }
        assertEquals(listOf(command(rule, HotspotAudioFirewall.CHECK), command(rule, HotspotAudioFirewall.INSERT)), commands)
        assertEquals("iptables wlan1 192.168.43.20/32", journal.read())

        commands.clear()
        firewall.remove(session, log)
        firewall.remove(session, log)
        assertEquals(listOf("iptables -w -D INPUT -i wlan1 -s 192.168.43.20/32 -p udp -j ACCEPT"), commands)
        assertNull(journal.read())
    }

    @Test fun removingWithoutAddingDoesNothing() {
        firewall.remove(session, log)
        firewall.remove(null, log)
        assertEquals(emptyList<String>(), commands)
    }

    @Test fun anEndedSessionAddsNothing() {
        firewall.allow(rule, session, log) { false }
        firewall.remove(session, log)
        assertEquals(emptyList<String>(), commands)
    }

    @Test fun theEndOfAnOldSessionKeepsTheRuleOfTheNextOne() {
        val next = Any()
        firewall.allow(rule, session, log) { true }
        firewall.allow(rule, next, log) { true }
        commands.clear()
        firewall.remove(session, log)
        assertEquals(emptyList<String>(), commands)
        firewall.remove(next, log)
        assertEquals(listOf(command(rule, HotspotAudioFirewall.DELETE)), commands)
    }

    @Test fun aFailedRemovalIsTriedAgain() {
        firewall.allow(rule, session, log) { true }
        exitCodes[HotspotAudioFirewall.DELETE] = 4
        firewall.remove(session, log)
        exitCodes[HotspotAudioFirewall.DELETE] = 0
        firewall.remove(session, log)
        assertEquals(listOf(command(rule, HotspotAudioFirewall.CHECK), command(rule, HotspotAudioFirewall.INSERT),
            command(rule, HotspotAudioFirewall.DELETE), command(rule, HotspotAudioFirewall.DELETE)), commands)
        assertNull(journal.read())
    }

    @Test fun aRuleSomeoneElseAddedIsNeitherDuplicatedNorRemoved() {
        exitCodes[HotspotAudioFirewall.CHECK] = 0
        firewall.allow(rule, session, log) { true }
        firewall.remove(session, log)
        assertEquals(listOf(command(rule, HotspotAudioFirewall.CHECK)), commands)
        assertNull(journal.read())
    }

    @Test fun aRuleLeftByAKilledRunIsKeptForTheSameIphoneAndRemovedAtTheEnd() {
        journal.write(rule.encode())
        exitCodes[HotspotAudioFirewall.CHECK] = 0
        firewall.allow(rule, session, log) { true }
        firewall.remove(session, log)
        assertEquals(listOf(command(rule, HotspotAudioFirewall.CHECK), command(rule, HotspotAudioFirewall.DELETE)), commands)
        assertNull(journal.read())
    }

    @Test fun aRuleLeftByAKilledRunForAnotherIphoneIsRemovedBeforeTheNewOne() {
        val old = HotspotAudioFirewall.rule("wlan1", "192.168.43.9")!!
        journal.write(old.encode())
        firewall.allow(rule, session, log) { true }
        assertEquals(listOf(command(old, HotspotAudioFirewall.DELETE), command(rule, HotspotAudioFirewall.CHECK),
            command(rule, HotspotAudioFirewall.INSERT)), commands)
        assertEquals(rule.encode(), journal.read())
    }

    @Test fun aRuleLeftByAKilledRunIsRemovedWhenTheControllerCloses() {
        journal.write(rule.encode())
        firewall.remove(null, log)
        assertEquals(listOf(command(rule, HotspotAudioFirewall.DELETE)), commands)
        assertNull(journal.read())
    }

    @Test fun aFailedInsertIsNotRemovedLater() {
        exitCodes[HotspotAudioFirewall.INSERT] = 4
        firewall.allow(rule, session, log) { true }
        firewall.remove(session, log)
        assertEquals(listOf(command(rule, HotspotAudioFirewall.CHECK), command(rule, HotspotAudioFirewall.INSERT)), commands)
        assertEquals(listOf("hotspot audio firewall rule not added (iptables iface=wlan1): exit 4"), logs)
        assertNull(journal.read())
    }

    @Test fun aNewPeerReplacesTheOldRule() {
        val next = HotspotAudioFirewall.rule("wlan1", "192.168.43.21")!!
        firewall.allow(rule, session, log) { true }
        commands.clear()
        firewall.allow(next, Any(), log) { true }
        assertEquals(listOf(command(rule, HotspotAudioFirewall.DELETE), command(next, HotspotAudioFirewall.CHECK),
            command(next, HotspotAudioFirewall.INSERT)), commands)
    }

    @Test fun missingRootAddsNothing() {
        val unrooted = HotspotAudioFirewall(run = { commands += it; RootShell.Result.Unavailable("su unavailable (IOException)") })
        unrooted.allow(rule, session, log) { true }
        unrooted.remove(session, log)
        assertEquals(listOf(command(rule, HotspotAudioFirewall.CHECK)), commands)
        assertEquals(listOf("hotspot audio firewall rule not added (iptables iface=wlan1): su unavailable (IOException)"), logs)
    }
}
