package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QnxEtmFlagTest {
    @Test fun thePrintedPairIsReadAfterTheEchoedCommand() {
        val answer = QnxEtmFlag.ENSURE_COMMAND + "\r\n0000000 02 01                                           ..\r\n0000002 \r\n# "
        assertEquals(2 to 1, QnxEtmFlag.parse(answer))
    }

    @Test fun aWrappedEchoDoesNotCountAsTheFlags() {
        val wrapped = "[ -f /dev/shmem/etm_flag ] && case \"\$(hd /dev/shmem/etm_flag)\" in *\"\r\n0000000 02 00\"*) ;; esac\r\n" +
            "0000000 00 01                                           ..\r\n0000002 \r\n# "
        assertEquals(0 to 1, QnxEtmFlag.parse(wrapped))
        assertNull(QnxEtmFlag.parse("hd: /dev/shmem/etm_flag: No such file or directory\r\n# "))
    }

    @Test fun theWriteIsGuardedOnTheQnxSide() {
        val command = QnxEtmFlag.ENSURE_COMMAND
        assertEquals(1, Regex("conv=notrunc").findAll(command).count())
        assert(command.contains("*\" 02 00 \"*)")) { command }
        assert(command.contains("seek=1 count=1")) { command }
    }
}
