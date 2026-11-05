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
    private val firewall = HotspotAudioFirewall(run = { command ->
        commands += command
        RootShell.Result.Completed(exitCodes.getValue(command.split(' ')[1]), "")
    }, log = logs::add)
    private val rule = HotspotAudioFirewall.rule("wlan1", "192.168.43.20")!!

    @Test fun buildsAnIpv4RuleForTheIphoneOnTheHotspotInterface() {
        assertEquals(Rule("iptables", "wlan1", "192.168.43.20/32"), rule)
        assertEquals("iptables -C INPUT -i wlan1 -s 192.168.43.20/32 -p udp -j ACCEPT", rule.command(HotspotAudioFirewall.CHECK))
        assertEquals("iptables -I INPUT 1 -i wlan1 -s 192.168.43.20/32 -p udp -j ACCEPT", rule.command(HotspotAudioFirewall.INSERT))
        assertEquals("iptables -D INPUT -i wlan1 -s 192.168.43.20/32 -p udp -j ACCEPT", rule.command(HotspotAudioFirewall.DELETE))
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

    @Test fun addsTheRuleOnceAndRemovesExactlyThatRule() {
        firewall.allow(rule) { true }
        firewall.allow(rule) { true }
        assertEquals(listOf(rule.command(HotspotAudioFirewall.CHECK), rule.command(HotspotAudioFirewall.INSERT)), commands)

        commands.clear()
        firewall.remove()
        firewall.remove()
        assertEquals(listOf("iptables -D INPUT -i wlan1 -s 192.168.43.20/32 -p udp -j ACCEPT"), commands)
    }

    @Test fun removingWithoutAddingDoesNothing() {
        firewall.remove()
        assertEquals(emptyList<String>(), commands)
    }

    @Test fun anEndedSessionAddsNothing() {
        firewall.allow(rule) { false }
        firewall.remove()
        assertEquals(emptyList<String>(), commands)
    }

    @Test fun aRuleThatIsAlreadyThereIsNeitherDuplicatedNorRemoved() {
        exitCodes[HotspotAudioFirewall.CHECK] = 0
        firewall.allow(rule) { true }
        firewall.remove()
        assertEquals(listOf(rule.command(HotspotAudioFirewall.CHECK)), commands)
    }

    @Test fun aFailedInsertIsNotRemovedLater() {
        exitCodes[HotspotAudioFirewall.INSERT] = 4
        firewall.allow(rule) { true }
        firewall.remove()
        assertEquals(listOf(rule.command(HotspotAudioFirewall.CHECK), rule.command(HotspotAudioFirewall.INSERT)), commands)
        assertEquals(listOf("hotspot audio firewall rule not added (iptables iface=wlan1): exit 4"), logs)
    }

    @Test fun aNewPeerReplacesTheOldRule() {
        val next = HotspotAudioFirewall.rule("wlan1", "192.168.43.21")!!
        firewall.allow(rule) { true }
        commands.clear()
        firewall.allow(next) { true }
        assertEquals(listOf(rule.command(HotspotAudioFirewall.DELETE), next.command(HotspotAudioFirewall.CHECK),
            next.command(HotspotAudioFirewall.INSERT)), commands)
    }

    @Test fun missingRootAddsNothing() {
        val unrooted = HotspotAudioFirewall(run = { commands += it; RootShell.Result.Unavailable("su unavailable (IOException)") },
            log = logs::add)
        unrooted.allow(rule) { true }
        unrooted.remove()
        assertEquals(listOf(rule.command(HotspotAudioFirewall.CHECK)), commands)
        assertEquals(listOf("hotspot audio firewall rule not added (iptables iface=wlan1): su unavailable (IOException)"), logs)
    }
}
