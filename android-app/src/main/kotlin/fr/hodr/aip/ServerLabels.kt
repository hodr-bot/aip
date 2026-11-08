package fr.hodr.aip

import pia.core.ServerEntry

/**
 * One-line human-readable server labels, text only: display title plus
 * comma tags. No flag emoji (region ids are not ISO codes: denmark is DK,
 * sanjose is CR) and no badge icons.
 */
object ServerLabels {
    fun label(entry: ServerEntry): String {
        val title = entry.displayTitle()
        val tags = buildList {
            if (entry.virtual) {
                add("virtual")
            }
            if (entry.streaming && !title.contains("stream", ignoreCase = true)) {
                add("streaming")
            }
        }
        return if (tags.isEmpty()) title else title + ", " + tags.joinToString(", ")
    }
}
