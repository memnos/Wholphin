package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.data.model.HomeRowConfig
import com.github.damontecres.wholphin.data.model.HomeRowViewOptions
import com.github.damontecres.wholphin.ui.AspectRatio
import com.github.damontecres.wholphin.ui.Cards
import com.github.damontecres.wholphin.ui.components.ViewOptionImageType
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpMethod
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the KefinTweaks home layout stored in the JavaScript Injector plugin
 * and turns the enabled sections into Wholphin rows.
 *
 * Discovery, spotlight carousels and the IMDb chart are left out: they scan
 * large parts of the library or depend on data that exists only in the browser.
 */
@Singleton
class KefinHomeService
    @Inject
    constructor(
        private val api: ApiClient,
    ) {
        private val json =
            Json {
                ignoreUnknownKeys = true
                isLenient = true
            }

        /**
         * @return extra home rows, or null when KefinTweaks config cannot be read
         */
        suspend fun loadExtraRows(): List<HomeRowConfig>? {
            val home = loadHomeScreen() ?: return null
            val rows = mutableListOf<Pair<Int, HomeRowConfig>>()
            rows += recentlyAdded(home)
            rows += recentlyReleased(home)
            rows += customSections(home)
            rows += seasonal(home)
            Timber.i("KefinTweaks home rows: %s", rows.size)
            return rows.sortedBy { it.first }.map { it.second }
        }

        private suspend fun loadHomeScreen(): JsonObject? {
            val plugins =
                getJson("/Plugins") as? JsonArray ?: return null
            val pluginId =
                plugins
                    .mapNotNull { it as? JsonObject }
                    .firstOrNull { plugin ->
                        val name = plugin.text("name") ?: plugin.text("Name") ?: ""
                        name.contains("injector", ignoreCase = true)
                    }?.let { it.text("id") ?: it.text("Id") }
                    ?: return null
            val config =
                getJson(
                    "/Plugins/{pluginId}/Configuration",
                    mapOf("pluginId" to pluginId),
                ) as? JsonObject ?: return null
            val scripts =
                config["customJavaScripts"]?.jsonArray
                    ?: config["CustomJavaScripts"]?.jsonArray
                    ?: return null
            val script =
                scripts
                    .mapNotNull { it as? JsonObject }
                    .firstOrNull { (it.text("name") ?: it.text("Name")) == "KefinTweaks-Config" }
                    ?.let { it.text("script") ?: it.text("Script") }
                    ?: return null
            val match = CONFIG_REGEX.find(script) ?: return null
            val root = json.parseToJsonElement(match.groupValues[1]) as? JsonObject ?: return null
            return root["homeScreen"] as? JsonObject
        }

        private fun recentlyAdded(home: JsonObject): List<Pair<Int, HomeRowConfig>> {
            val config = home["recentlyAddedInLibrary"] as? JsonObject ?: return emptyList()
            return config.mapNotNull { (libraryId, value) ->
                val section = value as? JsonObject ?: return@mapNotNull null
                if (section.bool("enabled") == false) return@mapNotNull null
                val parentId = libraryId.toUuidOrNull() ?: return@mapNotNull null
                val order = section.int("order") ?: 11
                order to
                    HomeRowConfig.RecentlyAdded(
                        parentId = parentId,
                        viewOptions = viewOptions(section.text("cardFormat")),
                    )
            }
        }

        private fun recentlyReleased(home: JsonObject): List<Pair<Int, HomeRowConfig>> {
            val config = home["recentlyReleased"] as? JsonObject ?: return emptyList()
            if (config.bool("enabled") == false) return emptyList()
            return listOfNotNull(
                releasedRow(config["movies"] as? JsonObject, BaseItemKind.MOVIE, "Recently Released Movies"),
                releasedRow(config["episodes"] as? JsonObject, BaseItemKind.EPISODE, "Recently Aired Episodes"),
            )
        }

        private fun releasedRow(
            section: JsonObject?,
            kind: BaseItemKind,
            fallbackName: String,
        ): Pair<Int, HomeRowConfig>? {
            if (section == null || section.bool("enabled") == false) return null
            val name = section.text("name") ?: fallbackName
            val maxAge = section.int("maxAgeInDays")
            val now = LocalDateTime.now()
            return (section.int("order") ?: 30) to
                HomeRowConfig.GetItems(
                    name = name,
                    getItems =
                        GetItemsRequest(
                            recursive = true,
                            includeItemTypes = listOf(kind),
                            sortBy = listOf(sortBy(section.text("sortOrder"))),
                            sortOrder = listOf(sortOrder(section.text("sortOrderDirection"))),
                            limit = rowLimit(section),
                            maxPremiereDate = now,
                            minPremiereDate = maxAge?.let { now.minusDays(it.toLong()) },
                        ),
                    viewOptions = viewOptions(section.text("cardFormat")),
                )
        }

        private fun customSections(home: JsonObject): List<Pair<Int, HomeRowConfig>> {
            val sections = home["customSections"] as? JsonArray ?: return emptyList()
            return sections.mapNotNull { element ->
                val section = element as? JsonObject ?: return@mapNotNull null
                if (section.bool("enabled") == false || section.bool("discoveryEnabled") == true) {
                    return@mapNotNull null
                }
                val row = sectionRow(section) ?: return@mapNotNull null
                (section.int("order") ?: 40) to row
            }
        }

        private fun seasonal(home: JsonObject): List<Pair<Int, HomeRowConfig>> {
            val seasonal = home["seasonal"] as? JsonObject ?: return emptyList()
            if (seasonal.bool("enabled") == false) return emptyList()
            val today = LocalDate.now()
            val seasons = seasonal["seasons"] as? JsonArray ?: return emptyList()
            return seasons.flatMap { element ->
                val season = element as? JsonObject ?: return@flatMap emptyList()
                if (season.bool("enabled") == false) return@flatMap emptyList()
                if (!inSeason(season.text("startDate"), season.text("endDate"), today)) {
                    return@flatMap emptyList()
                }
                val sections = season["sections"] as? JsonArray ?: return@flatMap emptyList()
                sections.mapNotNull { sectionElement ->
                    val section = sectionElement as? JsonObject ?: return@mapNotNull null
                    if (section.bool("enabled") == false || section.bool("discoveryEnabled") == true) {
                        return@mapNotNull null
                    }
                    val row = sectionRow(section) ?: return@mapNotNull null
                    (section.int("order") ?: 50) to row
                }
            }
        }

        private fun sectionRow(section: JsonObject): HomeRowConfig? {
            val name = section.text("name") ?: return null
            val type = section.text("type") ?: "Collection"
            val sources =
                section.text("source").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val includeTypes = itemKinds(section["includeItemTypes"])
            val searchTerm = section.text("searchTerm")?.takeIf { it.isNotBlank() }
            val limit = rowLimit(section)
            val sortBy = sortBy(section.text("sortOrder"))
            val sortOrder = sortOrder(section.text("sortOrderDirection"))
            val options = viewOptions(section.text("cardFormat"))
            val request =
                when (type) {
                    "Genre" ->
                        itemsRequest(
                            nameSources = sources,
                            includeTypes = includeTypes,
                            searchTerm = searchTerm,
                            limit = limit,
                            sortBy = sortBy,
                            sortOrder = sortOrder,
                            genres = sources,
                        )

                    "Tag" ->
                        itemsRequest(
                            nameSources = sources,
                            includeTypes = includeTypes,
                            searchTerm = searchTerm,
                            limit = limit,
                            sortBy = sortBy,
                            sortOrder = sortOrder,
                            tags = sources,
                        )

                    "Collection", "Playlist", "Parent" -> {
                        val parentId = sources.firstOrNull()?.toUuidOrNull()
                        if (type != "Parent" && parentId == null) return null
                        itemsRequest(
                            nameSources = sources,
                            includeTypes = includeTypes,
                            searchTerm = searchTerm,
                            limit = limit,
                            sortBy = sortBy,
                            sortOrder = sortOrder,
                            parentId = parentId,
                        )
                    }

                    else -> return null
                }
            return HomeRowConfig.GetItems(
                name = name,
                getItems = request,
                viewOptions = options,
            )
        }

        private fun itemsRequest(
            nameSources: List<String>,
            includeTypes: List<BaseItemKind>?,
            searchTerm: String?,
            limit: Int,
            sortBy: ItemSortBy,
            sortOrder: SortOrder,
            parentId: UUID? = null,
            genres: List<String>? = null,
            tags: List<String>? = null,
        ): GetItemsRequest {
            if (nameSources.isEmpty() && parentId == null && genres == null && tags == null && searchTerm == null) {
                Timber.v("KefinTweaks section has no source")
            }
            return GetItemsRequest(
                parentId = parentId,
                recursive = true,
                includeItemTypes = includeTypes,
                genres = genres,
                tags = tags,
                searchTerm = searchTerm,
                sortBy = listOf(sortBy),
                sortOrder = listOf(sortOrder),
                limit = limit,
            )
        }

        private suspend fun getJson(
            path: String,
            pathParameters: Map<String, Any?> = emptyMap(),
        ): kotlinx.serialization.json.JsonElement? {
            val response =
                api.request(
                    method = HttpMethod.GET,
                    pathTemplate = path,
                    pathParameters = pathParameters,
                    queryParameters = emptyMap(),
                    requestBody = null,
                )
            if (response.status !in 200..299) {
                Timber.w("KefinTweaks %s returned %s", path, response.status)
                return null
            }
            return json.parseToJsonElement(response.body.decodeToString())
        }

        private fun viewOptions(cardFormat: String?): HomeRowViewOptions {
            val wide = cardFormat.equals("Thumb", ignoreCase = true) || cardFormat.equals("Backdrop", ignoreCase = true)
            return if (wide) {
                HomeRowViewOptions(
                    heightDp = Cards.HEIGHT_EPISODE,
                    aspectRatio = AspectRatio.WIDE,
                    imageType = ViewOptionImageType.THUMB,
                    episodeAspectRatio = AspectRatio.WIDE,
                    episodeImageType = ViewOptionImageType.THUMB,
                )
            } else {
                HomeRowViewOptions()
            }
        }

        private fun sortBy(name: String?): ItemSortBy =
            when (name?.lowercase()) {
                "random" -> ItemSortBy.RANDOM
                "releasedate", "premieredate" -> ItemSortBy.PREMIERE_DATE
                "dateadded", "datecreated" -> ItemSortBy.DATE_CREATED
                "communityrating" -> ItemSortBy.COMMUNITY_RATING
                "productionyear" -> ItemSortBy.PRODUCTION_YEAR
                "datelastcontentadded" -> ItemSortBy.DATE_LAST_CONTENT_ADDED
                else -> ItemSortBy.SORT_NAME
            }

        private fun sortOrder(name: String?): SortOrder =
            if (name.equals("Descending", ignoreCase = true)) {
                SortOrder.DESCENDING
            } else {
                SortOrder.ASCENDING
            }

        private fun itemKinds(element: kotlinx.serialization.json.JsonElement?): List<BaseItemKind>? {
            val raw =
                when (element) {
                    is JsonArray -> element.mapNotNull { it.jsonPrimitive.contentOrNull }
                    is kotlinx.serialization.json.JsonPrimitive ->
                        element.contentOrNull?.split(',')?.map { it.trim() }.orEmpty()
                    else -> return null
                }
            val kinds =
                raw.mapNotNull {
                    when (it.lowercase()) {
                        "movie" -> BaseItemKind.MOVIE
                        "episode" -> BaseItemKind.EPISODE
                        "series", "tvshow" -> BaseItemKind.SERIES
                        else -> null
                    }
                }
            return kinds.ifEmpty { null }
        }

        private fun inSeason(
            start: String?,
            end: String?,
            today: LocalDate,
        ): Boolean {
            val startDate = monthDay(start, today.year) ?: return false
            var endDate = monthDay(end, today.year) ?: return false
            if (endDate.isBefore(startDate)) endDate = endDate.plusYears(1)
            val check = if (today.isBefore(startDate) && endDate.year > today.year) today.plusYears(1) else today
            return !check.isBefore(startDate) && !check.isAfter(endDate)
        }

        private fun monthDay(
            value: String?,
            year: Int,
        ): LocalDate? {
            val parts = value?.split('-') ?: return null
            if (parts.size != 2) return null
            val month = parts[0].toIntOrNull() ?: return null
            val day = parts[1].toIntOrNull() ?: return null
            return runCatching { LocalDate.of(year, month, day) }.getOrNull()
        }

        private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

        private fun JsonObject.bool(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull

        private fun rowLimit(section: JsonObject): Int = (section.int("itemLimit") ?: 16).coerceIn(1, 16)

        private fun JsonObject.int(key: String): Int? =
            this[key]?.jsonPrimitive?.intOrNull
                ?: this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()

        private fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()

        companion object {
            private val CONFIG_REGEX = Regex("""window\.KefinTweaksConfig\s*=\s*(\{[\s\S]*\})\s*;""")
        }
    }
