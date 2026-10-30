package com.spizganed.quickbuds.protocol

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for realme Buds T01 (064412) protocol vectors (Part B) and T01-gated behavior. */
class T01ProtocolTest {

    private val t01Model = JSONObject(
        """{"id":"064412","name":"realme Buds T01","gameConfirm":"read","statusQuery":"06"}"""
    )
    private val otherModel = JSONObject(
        """{"id":"065414","name":"OnePlus Buds 4","statusQuery":"05 04 06"}"""
    )

    private fun hexToBytes(hex: String): ByteArray =
        hex.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()

    private fun decodeSingle(hex: String): Pair<Wire.Message, Msg?> {
        val msg = Wire.Reassembler().push(hexToBytes(hex)).single()
        return msg to Decode.decode(msg)
    }

    @Test fun outboundGameModeVectorsMatchPartB() {
        assertEquals(
            "AA 09 00 00 03 04 91 02 00 06 01",
            OpoProtocol.bytesToHex(Commands.feature(Feature.GAME_MODE, true, seq = 0x91))
        )
        assertEquals(
            "AA 09 00 00 03 04 A5 02 00 06 00",
            OpoProtocol.bytesToHex(Commands.feature(Feature.GAME_MODE, false, seq = 0xA5))
        )
        val ask = RowRules.askQuery(t01Model, null)
        assertEquals("06", ask)
        assertEquals(
            "AA 09 00 00 0D 01 55 02 00 01 06",
            OpoProtocol.bytesToHex(Commands.queryStatus(ask, RowRules.asksWind(t01Model), seq = 0x55))
        )
    }

    @Test fun outboundSoundEffectVectorsMatchPartB() {
        assertEquals(
            "AA 08 00 00 06 04 65 01 00 00",
            OpoProtocol.bytesToHex(Commands.setSoundEffect(OpoProtocol.SoundEffect.BASS_BOOST_PLUS, seq = 0x65))
        )
        assertEquals(
            "AA 08 00 00 06 04 79 01 00 01",
            OpoProtocol.bytesToHex(Commands.setSoundEffect(OpoProtocol.SoundEffect.BALANCED, seq = 0x79))
        )
        assertEquals(
            "AA 08 00 00 06 04 7B 01 00 02",
            OpoProtocol.bytesToHex(Commands.setSoundEffect(OpoProtocol.SoundEffect.BRIGHT, seq = 0x7B))
        )
        assertEquals(
            "AA 07 00 00 0F 01 66 00 00",
            OpoProtocol.bytesToHex(Commands.querySoundEffect(seq = 0x66))
        )
    }

    @Test fun outboundVolumeEnhancerVectorsMatchPartB() {
        assertEquals(
            "AA 08 00 00 07 04 8F 01 00 00",
            OpoProtocol.bytesToHex(Commands.volumeEnhancer(false, seq = 0x8F))
        )
        assertEquals(
            "AA 08 00 00 07 04 90 01 00 01",
            OpoProtocol.bytesToHex(Commands.volumeEnhancer(true, seq = 0x90))
        )
    }

    @Test fun inboundGameModeVectorsDecodeAndMatchByCmdOnT01() {
        val (ackWire, ackMsg) = decodeSingle("AA 08 04 00 03 84 D8 01 00 00")
        assertEquals(Msg.Ack(OpoProtocol.CMD_SET_FEATURE, 0, emptyList()), ackMsg)
        assertTrue(Replies.matchesReply(OpoProtocol.CMD_SET_FEATURE, 0x91, ackWire.cmd, ackWire.seq, "064412"))
        assertFalse(Replies.matchesReply(OpoProtocol.CMD_SET_FEATURE, 0x91, ackWire.cmd, ackWire.seq, "065414"))

        val (onWire, onMsg) = decodeSingle("AA 0B 04 00 0D 81 D9 04 00 00 01 06 01")
        assertEquals(Msg.Features(listOf(Feature.GAME_MODE to 1)), onMsg)
        assertTrue(Replies.matchesReply(OpoProtocol.CMD_QUERY_STATUS, 0x00, onWire.cmd, onWire.seq, "064412"))

        val (offWire, offMsg) = decodeSingle("AA 0B 04 00 0D 81 F5 04 00 00 01 06 00")
        assertEquals(Msg.Features(listOf(Feature.GAME_MODE to 0)), offMsg)
        assertTrue(Replies.matchesReply(OpoProtocol.CMD_QUERY_STATUS, 0x00, offWire.cmd, offWire.seq, "064412"))
    }

    @Test fun inboundSoundEffectVectorsDecodeAndMatchByCmdOnT01() {
        val (ackWire, ackMsg) = decodeSingle("AA 08 04 00 06 84 9C 01 00 00")
        assertEquals(Msg.Ack(OpoProtocol.CMD_SET_EQ, 0, emptyList()), ackMsg)
        assertTrue(Replies.matchesReply(OpoProtocol.CMD_SET_EQ, 0x65, ackWire.cmd, ackWire.seq, "064412"))
        assertFalse(Replies.matchesReply(OpoProtocol.CMD_SET_EQ, 0x65, ackWire.cmd, ackWire.seq, "065414"))

        val (_, sound0) = decodeSingle("AA 09 04 00 0F 81 9D 02 00 00 00")
        assertEquals(Msg.EqCurrent(0), sound0)
        assertEquals(OpoProtocol.SoundEffect.BASS_BOOST_PLUS, OpoProtocol.SoundEffect.fromId((sound0 as Msg.EqCurrent).id))

        val (_, sound1) = decodeSingle("AA 09 04 00 0F 81 B9 02 00 00 01")
        assertEquals(Msg.EqCurrent(1), sound1)
        assertEquals(OpoProtocol.SoundEffect.BALANCED, OpoProtocol.SoundEffect.fromId((sound1 as Msg.EqCurrent).id))

        val (_, sound2) = decodeSingle("AA 09 04 00 0F 81 BB 02 00 00 02")
        assertEquals(Msg.EqCurrent(2), sound2)
        assertEquals(OpoProtocol.SoundEffect.BRIGHT, OpoProtocol.SoundEffect.fromId((sound2 as Msg.EqCurrent).id))
    }

    @Test fun gameModeFiveRoundTripsConfirmBudsReportedValueWithT01Seq() {
        val sync = FeatureSync(matchSeq = false)
        sync.init(mapOf(Feature.GAME_MODE to 0))
        var now = 1000L

        val (_, ackMsg) = decodeSingle("AA 08 04 00 03 84 D8 01 00 00")
        val ackStatus = (ackMsg as Msg.Ack).status
        val (onWire, onMsg) = decodeSingle("AA 0B 04 00 0D 81 D9 04 00 00 01 06 01")
        val onMap = (onMsg as Msg.Features).list.toMap()
        val (offWire, offMsg) = decodeSingle("AA 0B 04 00 0D 81 F5 04 00 00 01 06 00")
        val offMap = (offMsg as Msg.Features).list.toMap()

        repeat(5) {
            // Toggle ON
            val tapOn = sync.tap(
                FeatureSync.Operation(Feature.GAME_MODE, listOf(Feature.GAME_MODE to 1), FeatureSync.Confirm.READ),
                now
            )
            assertTrue(tapOn.any { it is FeatureSync.Out.Send })
            now += 100
            val ackOn = sync.ack(seq = 0xD8, status = ackStatus, now = now)
            assertTrue(ackOn.any { it is FeatureSync.Out.Read })
            now += 50
            val repOn = sync.report(onMap, seq = onWire.seq, now = now, push = false)
            assertTrue(repOn.contains(FeatureSync.Out.Changed(Feature.GAME_MODE, 1)))
            assertTrue(repOn.contains(FeatureSync.Out.Busy(Feature.GAME_MODE, false)))
            assertEquals(1, sync.value(Feature.GAME_MODE))

            // Toggle OFF
            now += 100
            val tapOff = sync.tap(
                FeatureSync.Operation(Feature.GAME_MODE, listOf(Feature.GAME_MODE to 0), FeatureSync.Confirm.READ),
                now
            )
            assertTrue(tapOff.any { it is FeatureSync.Out.Send })
            now += 100
            val ackOff = sync.ack(seq = 0xD8, status = ackStatus, now = now)
            assertTrue(ackOff.any { it is FeatureSync.Out.Read })
            now += 50
            val repOff = sync.report(offMap, seq = offWire.seq, now = now, push = false)
            assertTrue(repOff.contains(FeatureSync.Out.Changed(Feature.GAME_MODE, 0)))
            assertTrue(repOff.contains(FeatureSync.Out.Busy(Feature.GAME_MODE, false)))
            assertEquals(0, sync.value(Feature.GAME_MODE))
        }
    }

    @Test fun t01CapabilitiesAreGatedToT01Only() {
        val caps = setOf(OpoProtocol.CMD_SET_EQ, OpoProtocol.CMD_QUERY_EQ, OpoProtocol.CMD_SET_VOLUME_ENHANCER)
        val reply = mapOf(Feature.GAME_MODE to 0)

        val t01Rows = RowRules.rows(t01Model, reply, caps)
        assertTrue(RowRules.SOUND_EFFECTS in t01Rows)
        assertTrue(RowRules.VOLUME_ENHANCER in t01Rows)
        assertTrue(RowRules.GAME in t01Rows)

        val otherRows = RowRules.rows(otherModel, reply, caps)
        assertFalse(RowRules.SOUND_EFFECTS in otherRows)
        assertFalse(RowRules.VOLUME_ENHANCER in otherRows)
    }
}
