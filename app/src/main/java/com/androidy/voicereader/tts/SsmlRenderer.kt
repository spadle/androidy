package com.androidy.voicereader.tts

import com.androidy.voicereader.llm.ReadingSegment
import com.androidy.voicereader.llm.SegmentType

/**
 * Converts annotated reading segments into SSML (Speech Synthesis Markup Language)
 * for expressive text-to-speech rendering.
 *
 * Android's Google TTS engine supports SSML when wrapped in a <speak> root element.
 * Supported tags: <prosody> (rate, pitch, volume), <break>, <emphasis>, <say-as>.
 *
 * Falls back to plain text if SSML is not supported by the device's TTS engine.
 */
object SsmlRenderer {

    /**
     * Render a single segment as SSML with voice directives based on segment type.
     */
    fun renderSegment(segment: ReadingSegment, useSsml: Boolean = true): String {
        if (!useSsml) return renderPlainText(segment)

        return when (segment.type) {
            SegmentType.IMPORTANT -> renderImportant(segment.text)
            SegmentType.NORMAL -> renderNormal(segment.text)
            SegmentType.NOTE -> renderNote(segment.text)
            SegmentType.SUMMARY -> renderSummary(segment.text)
            SegmentType.SKIP -> "" // Should never be rendered
        }
    }

    /**
     * Render all readable segments as a list of SSML strings,
     * each meant to be spoken as a separate utterance.
     */
    fun renderAll(segments: List<ReadingSegment>, useSsml: Boolean = true): List<String> {
        return segments
            .filter { it.type != SegmentType.SKIP }
            .map { renderSegment(it, useSsml) }
            .filter { it.isNotBlank() }
    }

    // --- SSML builders per segment type ---

    private fun renderImportant(text: String): String {
        return buildSsml {
            // Slower rate, slightly lower pitch, strong emphasis
            append("""<prosody rate="85%" pitch="-5%">""")
            append("""<emphasis level="strong">""")
            append(escapeXml(text))
            append("</emphasis>")
            append("</prosody>")
        }
    }

    private fun renderNormal(text: String): String {
        return buildSsml {
            append(escapeXml(text))
        }
    }

    private fun renderNote(text: String): String {
        return buildSsml {
            // Brief pause before note, slightly faster and higher pitch
            append("""<break time="300ms"/>""")
            append("""<prosody rate="110%" pitch="+10%">""")
            append("Note: ")
            append(escapeXml(text))
            append("</prosody>")
            append("""<break time="400ms"/>""")
        }
    }

    private fun renderSummary(text: String): String {
        return buildSsml {
            // Pause before summary, moderate pace
            append("""<break time="500ms"/>""")
            append("""<prosody rate="95%">""")
            append("In summary: ")
            append(escapeXml(text))
            append("</prosody>")
            append("""<break time="500ms"/>""")
        }
    }

    // --- Plain text fallback (no SSML) ---

    private fun renderPlainText(segment: ReadingSegment): String {
        return when (segment.type) {
            SegmentType.NOTE -> "Note: ${segment.text}"
            SegmentType.SUMMARY -> "In summary: ${segment.text}"
            SegmentType.SKIP -> ""
            else -> segment.text
        }
    }

    // --- Helpers ---

    private fun buildSsml(block: StringBuilder.() -> Unit): String {
        val sb = StringBuilder()
        sb.append("<speak>")
        sb.block()
        sb.append("</speak>")
        return sb.toString()
    }

    private fun escapeXml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
