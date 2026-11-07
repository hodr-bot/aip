package fr.hodr.aip

import pia.core.ServerEntry

/**
 * One-line human-readable server labels. Hostnames and IPs never appear:
 * rows show flag, display title and badge icons (cloud for virtual
 * locations, clapper for streaming-optimized regions).
 */
object ServerLabels {
    const val VIRTUAL_BADGE = "☁️"
    const val STREAMING_BADGE = "🎬"
    const val GLOBE = "\uD83C\uDF10"

    fun label(entry: ServerEntry): String {
        val badges = buildString {
            if (entry.virtual) {
                append(" ")
                append(VIRTUAL_BADGE)
            }
            if (entry.streaming) {
                append(" ")
                append(STREAMING_BADGE)
            }
        }
        return "${flagFor(entry.id)} ${entry.displayTitle()}$badges"
    }

    /**
     * Flag emoji for a region id: the first two ASCII letters uppercased
     * map to regional indicator symbols; anything else gets the globe.
     */
    fun flagFor(id: String): String {
        if (id.length < 2) {
            return GLOBE
        }
        val first = id[0].uppercaseChar()
        val second = id[1].uppercaseChar()
        if (first !in 'A'..'Z' || second !in 'A'..'Z') {
            return GLOBE
        }
        val base = 0x1F1E6
        val firstPoint = base + (first - 'A')
        val secondPoint = base + (second - 'A')
        return String(Character.toChars(firstPoint)) + String(Character.toChars(secondPoint))
    }
}
