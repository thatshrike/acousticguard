package com.acousticguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AcousticModelContractTest {

    @Test
    fun verifyTaxonomyContract() {
        val helpNormalized = DistressLabelTaxonomy.normalize("help")
        assertNotNull(helpNormalized)
        assertEquals(DistressEvent.EXPLICIT_DISTRESS, helpNormalized?.event)

        val madadNormalized = DistressLabelTaxonomy.normalize("मदद")
        assertNotNull(madadNormalized)
        assertEquals(DistressEvent.EXPLICIT_DISTRESS, madadNormalized?.event)

        val screamNormalized = DistressLabelTaxonomy.normalize("screaming_vocal_friction")
        assertNotNull(screamNormalized)
        assertEquals(DistressEvent.STRONG_NON_VERBAL_DISTRESS, screamNormalized?.event)

        val thudNormalized = DistressLabelTaxonomy.normalize("physical_struggle_thud")
        assertNotNull(thudNormalized)
        assertEquals(DistressEvent.IMPACT, thudNormalized?.event)

        val conversationNormalized = DistressLabelTaxonomy.normalize("speech")
        assertNotNull(conversationNormalized)
        assertEquals(DistressEvent.BENIGN_SPEECH, conversationNormalized?.event)
    }

    @Test
    fun verifyIndianLanguageTaxonomy() {
        // Bengali
        val bengali = DistressLabelTaxonomy.normalize("সাহায্য")
        assertNotNull(bengali)
        assertEquals(DistressEvent.EXPLICIT_DISTRESS, bengali?.event)
        assertEquals(IndianLanguage.BENGALI, bengali?.language)

        // Tamil
        val tamil = DistressLabelTaxonomy.normalize("உதவி")
        assertNotNull(tamil)
        assertEquals(DistressEvent.EXPLICIT_DISTRESS, tamil?.event)
        assertEquals(IndianLanguage.TAMIL, tamil?.language)

        // Kannada
        val kannada = DistressLabelTaxonomy.normalize("ಸಹಾಯ")
        assertNotNull(kannada)
        assertEquals(DistressEvent.EXPLICIT_DISTRESS, kannada?.event)
        assertEquals(IndianLanguage.KANNADA, kannada?.language)

        // Hindi
        val hindi = DistressLabelTaxonomy.normalize("बचाओ")
        assertNotNull(hindi)
        assertEquals(DistressEvent.EXPLICIT_DISTRESS, hindi?.event)
        assertEquals(IndianLanguage.HINDI, hindi?.language)
    }

    @Test
    fun verifyPcmResamplerIntegrity() {
        // 48 kHz to 16 kHz downsampling
        val sampleCount48k = 48_000
        val pcm48k = ShortArray(sampleCount48k) { (it % 1000).toShort() }
        val resampled16k = PcmResampler.to16Khz(pcm48k, 48_000)

        assertEquals(16_000, resampled16k.size)

        // Already 16 kHz passthrough
        val pcm16k = ShortArray(16_000) { 100.toShort() }
        val passthrough = PcmResampler.to16Khz(pcm16k, 16_000)
        assertEquals(16_000, passthrough.size)
        assertEquals(100.toShort(), passthrough[0])
    }

    @Test
    fun verifyCircularPcmBufferIntegrity() {
        val buffer = CircularPcmBuffer(capacityBytes = 8_000)
        assertEquals(0, buffer.sizeBytes())

        val pcm = ShortArray(1_000) { 50.toShort() }
        buffer.writePcm16(pcm)
        assertEquals(2_000, buffer.sizeBytes())

        val snapshot = buffer.snapshotPcm16()
        assertEquals(1_000, snapshot.size)
        assertEquals(50.toShort(), snapshot[0])

        buffer.clear()
        assertEquals(0, buffer.sizeBytes())
    }
}
