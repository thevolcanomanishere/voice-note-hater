package com.watranscribe.data.local

import org.json.JSONArray
import org.json.JSONObject

/**
 * Serializes and parses the [TranscriptionEntity.segmentsJson] column.
 *
 * Forward: always writes JSON (array of `{s, e, t, w?}` objects, where `w` is
 * an array of `{s, e, t}` per-word timings if the engine provided them).
 *
 * Backward: legacy whisper records use one `startMs|endMs|text` line per
 * segment — [parse] falls back to that when the blob doesn't start with `[`.
 */
object SegmentsCodec {
    fun encode(segments: List<TimedSegment>): String {
        val arr = JSONArray()
        for (seg in segments) {
            val obj = JSONObject()
            obj.put("s", seg.startMs)
            obj.put("e", seg.endMs)
            obj.put("t", seg.text)
            if (seg.words.isNotEmpty()) {
                val wordsArr = JSONArray()
                for (w in seg.words) {
                    wordsArr.put(
                        JSONObject()
                            .put("s", w.startMs)
                            .put("e", w.endMs)
                            .put("t", w.text)
                    )
                }
                obj.put("w", wordsArr)
            }
            arr.put(obj)
        }
        return arr.toString()
    }

    fun parse(raw: String): List<TimedSegment> {
        if (raw.isBlank()) return emptyList()
        return if (raw.trimStart().startsWith("[")) parseJson(raw) else parseLegacy(raw)
    }

    private fun parseJson(raw: String): List<TimedSegment> = runCatching {
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val words = o.optJSONArray("w")?.let { warr ->
                    buildList {
                        for (j in 0 until warr.length()) {
                            val w = warr.getJSONObject(j)
                            add(
                                TimedWord(
                                    text = w.optString("t"),
                                    startMs = w.optLong("s"),
                                    endMs = w.optLong("e"),
                                )
                            )
                        }
                    }
                } ?: emptyList()
                add(
                    TimedSegment(
                        text = o.optString("t"),
                        startMs = o.optLong("s"),
                        endMs = o.optLong("e"),
                        words = words,
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun parseLegacy(raw: String): List<TimedSegment> {
        return raw.lines().filter { it.isNotBlank() }.mapNotNull { line ->
            val parts = line.split("|", limit = 3)
            if (parts.size == 3) {
                TimedSegment(
                    text = parts[2],
                    startMs = parts[0].toLongOrNull() ?: 0,
                    endMs = parts[1].toLongOrNull() ?: 0,
                )
            } else null
        }
    }
}
