package com.parth.index.util

import org.json.JSONArray
import org.json.JSONObject

/** Emoji reactions on chat messages (v1.4 Discord pack). The data is one
 *  map: emoji -> uids of the members who reacted with it. In the entity
 *  (and therefore Room) it travels as a JSON string; in Firestore it is
 *  stored as a real map field and FirebaseRepository converts both ways
 *  through here, so the format lives in exactly one place.
 *
 *  Only [EMOJIS] can ever be picked in the UI, but parsing tolerates any
 *  emoji key so older/newer data never breaks the chat screen. */
object Reactions {

    val EMOJIS: List<String> = listOf("👍", "❤️", "🔥", "😂")

    /** JSON string -> ordered emoji -> uid list map. Never throws. */
    fun parse(json: String): LinkedHashMap<String, List<String>> {
        val out = LinkedHashMap<String, List<String>>()
        if (json.isBlank()) return out
        runCatching {
            val obj = JSONObject(json)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val emoji = keys.next()
                val arr = obj.optJSONArray(emoji) ?: continue
                val uids = ArrayList<String>(arr.length())
                for (i in 0 until arr.length()) {
                    arr.optString(i).takeIf { it.isNotEmpty() }?.let { uids.add(it) }
                }
                if (uids.isNotEmpty()) out[emoji] = uids
            }
        }
        return out
    }

    /** The map -> entity/JSON form. Empty lists are dropped. */
    fun serialize(map: Map<String, List<String>>): String {
        val obj = JSONObject()
        for ((emoji, uids) in map) {
            if (uids.isEmpty()) continue
            obj.put(emoji, JSONArray(uids))
        }
        return obj.toString()
    }

    /** Firestore map (untyped) -> the typed map. Never throws. */
    @Suppress("UNCHECKED_CAST")
    fun fromFirestore(raw: Any?): LinkedHashMap<String, List<String>> {
        val out = LinkedHashMap<String, List<String>>()
        val map = raw as? Map<*, *> ?: return out
        for ((key, value) in map) {
            val emoji = key as? String ?: continue
            val uids = (value as? List<*>)?.filterIsInstance<String>() ?: continue
            if (uids.isNotEmpty()) out[emoji] = uids
        }
        return out
    }

    /** Firestore-ready form of the typed map. */
    fun toFirestore(map: Map<String, List<String>>): Map<String, List<String>> =
        map.filterValues { it.isNotEmpty() }

    /** The map with [uid]'s reaction of [emoji] flipped on/off. */
    fun toggled(
        map: Map<String, List<String>>, emoji: String, uid: String
    ): LinkedHashMap<String, List<String>> {
        val out = LinkedHashMap<String, List<String>>()
        for ((key, uids) in map) out[key] = uids
        val current = out[emoji].orEmpty()
        val next = if (current.contains(uid)) current - uid else current + uid
        if (next.isEmpty()) out.remove(emoji) else out[emoji] = next
        return out
    }
}
