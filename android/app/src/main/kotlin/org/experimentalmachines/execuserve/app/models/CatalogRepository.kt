package org.experimentalmachines.execuserve.app.models

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.experimentalmachines.execuserve.catalog.CatalogVariant
import org.experimentalmachines.execuserve.catalog.HfCatalog
import org.experimentalmachines.execuserve.catalog.Uncensored
import java.net.HttpURLConnection
import java.net.URL

/** A repository and the exports it offers this runtime. */
data class CatalogRepo(val repo: String, val variants: List<CatalogVariant>)

/**
 * The `experimentalmachines` exports, read from Hugging Face on request. The listing and one
 * `config.json` per repository; nothing is fetched until the Models screen asks.
 *
 * Derivatives with their refusals removed are listed only when [includeUncensored]: Play's
 * policy on generated content asks apps to guard against it, so the store build leaves them
 * out (`-PcatalogUncensored=true` builds one that lists them).
 */
class CatalogRepository(private val includeUncensored: Boolean = false) {

    suspend fun load(): List<CatalogRepo> = withContext(Dispatchers.IO) {
        val repos = HfCatalog.parseRepos(get(HfCatalog.listUrl())).filter { includeUncensored || !Uncensored.isUncensored(it.id) }
        coroutineScope {
            repos.map { repo ->
                async {
                    val variants = HfCatalog.configPaths(repo).flatMap { path ->
                        val revision = repo.sha ?: return@flatMap emptyList()
                        runCatching {
                            HfCatalog.variants(repo, path, HfCatalog.parseConfig(get(HfCatalog.fileUrl(repo.id, revision, path))))
                        }.getOrDefault(emptyList())
                    }
                    CatalogRepo(repo.id, variants.sortedBy { it.context ?: 0 })
                }
            }.awaitAll()
        }.filter { it.variants.isNotEmpty() }.sortedBy { it.repo.lowercase() }
    }

    /**
     * One export by repository and file name, pinned to the repository's current commit:
     * what `tools/execuserve pull` asks for, read the same way the catalog screen reads it.
     */
    suspend fun variant(repo: String, file: String): CatalogVariant = withContext(Dispatchers.IO) {
        val info = HfCatalog.parseRepos("[" + get(HfCatalog.modelUrl(repo)) + "]").single()
        val revision = info.sha ?: error("$repo has no commit")
        HfCatalog.configPaths(info).flatMap { path ->
            HfCatalog.variants(info, path, HfCatalog.parseConfig(get(HfCatalog.fileUrl(repo, revision, path))))
        }.firstOrNull { it.path.substringAfterLast('/') == file.substringAfterLast('/') }
            ?: error("$repo has no XNNPACK export named $file")
    }

    private fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.setRequestProperty("User-Agent", "ExecuServe")
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) error("HTTP ${connection.responseCode} from ${URL(url).host}")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 20_000
    }
}
