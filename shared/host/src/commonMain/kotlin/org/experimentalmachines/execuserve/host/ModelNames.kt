package org.experimentalmachines.execuserve.host

import org.experimentalmachines.execuserve.engine.ModelEntry

/** How a console names and orders installed models. */
object ModelNames {
    /**
     * The short alias, unless another installed model shares it; then the full id. Two exports
     * of one model at different windows carry the same alias, and must not read as one model.
     */
    fun shown(entry: ModelEntry, installed: List<ModelEntry>): String {
        val alias = entry.aliases.firstOrNull() ?: return entry.id
        val shared = installed.any { it.id != entry.id && it.aliases.firstOrNull() == alias }
        return if (shared) entry.id else alias
    }

    /**
     * The same rule for bare ids, such as the models in the request history, some of which may
     * no longer be installed: each id's [aliasOf], unless another of [ids] has that alias too.
     */
    fun shown(ids: Collection<String>, aliasOf: (String) -> String?): Map<String, String> {
        val aliases = ids.associateWith(aliasOf)
        return aliases.mapValues { (id, alias) ->
            if (alias == null || aliases.any { (other, its) -> other != id && its == alias }) id else alias
        }
    }

    /** Models in memory first, then the default, then the rest in the order given. */
    fun hostedOrder(installed: List<ModelEntry>, resident: Set<String>, defaultModel: String?): List<ModelEntry> =
        installed.sortedWith(compareByDescending<ModelEntry> { it.id in resident }.thenByDescending { it.id == defaultModel })
}
