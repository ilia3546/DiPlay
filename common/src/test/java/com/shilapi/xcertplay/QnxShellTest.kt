package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class QnxShellTest {
    /** Plays the firmware's telnet login and shell as seen on the car, and records the lines the client typed. */
    private class FakeQnx(private val password: String) : AutoCloseable {
        val server = ServerSocket(0)
        val typed = CopyOnWriteArrayList<String>()
        private val worker = thread(isDaemon = true) {
            server.accept().use { client ->
                val out = client.getOutputStream()
                val input = client.getInputStream()
                fun say(text: String) = out.write(text.toByteArray(Charsets.ISO_8859_1)).also { out.flush() }
                fun line(): String {
                    val bytes = ByteArrayOutputStream()
                    while (true) {
                        val byte = input.read()
                        if (byte < 0) return bytes.toString("ISO-8859-1")
                        if (byte == 255) { input.read(); input.read(); continue }
                        if (byte == '\r'.code) {
                            input.read()
                            return bytes.toString("ISO-8859-1").also { typed.add(it) }
                        }
                        bytes.write(byte)
                    }
                }
                out.write(byteArrayOf(-1, -3, 24, -1, -3, 32))
                say("\r\n\r\nQNX Neutrino (localhost) (ttyp2)\r\n\r\r\n\rlogin: ")
                line()
                say("Password for root@localhost:")
                if (line() != password) {
                    say("\r\nLogin incorrect\r\n\rlogin: ")
                    line()
                    return@use
                }
                say("\r\n# ")
                while (true) {
                    val command = line()
                    if (command.isEmpty() || command == "exit") return@use
                    say("$command\r\n[n]@a001\r\ndata:json:{\"len\":40,\"data\":[${List(40) { 0 }.joinToString(",")}]}\r\n# ")
                }
            }
        }

        fun shell() = QnxShell(Socket("127.0.0.1", server.localPort).apply { soTimeout = 500 })

        override fun close() {
            server.close()
            worker.join(2_000)
        }
    }

    @Test fun logsInAndReadsWithNothingButTheReadCommand() {
        FakeQnx("secret").use { qnx ->
            val shell = qnx.shell()
            shell.login("secret") { true }
            val answer = shell.read("cat /tmp/pps/spi_service/a001", 4_000)
            assertTrue(answer, answer.contains("data:json"))
            assertEquals(DashboardTrip(distanceKm = 0.0, drivingMinutes = 0, litresPer100Km = 0.0), QnxTripData.parse(answer))
            shell.close()
            Thread.sleep(200)
            assertEquals(listOf("root", "secret", "cat /tmp/pps/spi_service/a001", "exit"), qnx.typed.toList())
        }
    }

    @Test fun aWrongPasswordIsRefusedOnceWithoutTypingMore() {
        FakeQnx("secret").use { qnx ->
            val shell = qnx.shell()
            try {
                shell.login("wrong") { true }
                fail("the login went through")
            } catch (refused: QnxShell.LoginRefused) {
                assertTrue(refused.answer, refused.answer.contains("incorrect"))
            }
            shell.close()
            Thread.sleep(200)
            assertEquals(listOf("root", "wrong"), qnx.typed.toList())
        }
    }
}
