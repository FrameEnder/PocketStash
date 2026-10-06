package com.frameender.pocketstash.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * Generic edit engine. Every editable Stash entity is described by an [EditSpec]:
 * how to load it, which fields exist (mirroring the *UpdateInput types in the
 * Stash schema), and which mutations save / create / delete it. One form UI
 * renders any spec.
 */
enum class EditKind(val label: String, val browse: EntityKind) {
    SCENE("Scene", EntityKind.SCENES),
    PERFORMER("Performer", EntityKind.PERFORMERS),
    STUDIO("Studio", EntityKind.STUDIOS),
    TAG("Tag", EntityKind.TAGS),
    GROUP("Group", EntityKind.GROUPS),
    GALLERY("Gallery", EntityKind.GALLERIES),
    IMAGE("Image", EntityKind.IMAGES),
    MARKER("Marker", EntityKind.MARKERS),
}

enum class FType {
    TEXT, MULTILINE, INT, FLOAT, DATE, BOOL, RATING, ENUM, STRINGS,
    /** Single linked entity (studio, parent studio, primary tag…). */
    REF,
    /** Many linked entities (performers, tags, galleries…). */
    REFS,
    /** Linked entities carrying an extra value: scene groups (scene_index) or group links (description). */
    LINKS,
    /** Seconds, typed as 1:23:45 or plain seconds. */
    TIME,
    /** Image upload: URL or picked file (sent as a base64 data URL). */
    IMAGE,
}

data class FieldSpec(
    /** Key in the mutation input. */
    val key: String,
    val label: String,
    val type: FType,
    /** Dot path in the loaded entity JSON (defaults to [key]). */
    val read: String = key,
    /** Entity kind to pick from, for REF / REFS / LINKS. */
    val refKind: EntityKind? = null,
    /** ENUM options: value to label. */
    val options: List<Pair<String, String>> = emptyList(),
    /** LINKS: the extra key in the response element and input element. */
    val linkExtra: String? = null,
    /** LINKS: key that holds the linked entity in each response element. */
    val linkObject: String = "group",
    /** LINKS: id key in the input element. */
    val linkIdKey: String = "group_id",
    /** TIME fields whose input type is Int rather than Float. */
    val intSeconds: Boolean = false,
    val required: Boolean = false,
    val hint: String? = null,
    /** Shown but never edited (e.g. a marker's scene). */
    val readOnly: Boolean = false,
)

data class Ref(val id: String, val name: String, val image: String? = null, val extra: String = "")

/** Current value of one form field. */
sealed interface FormValue {
    data class Text(val value: String) : FormValue
    data class Flag(val value: Boolean) : FormValue
    data class Rating(val value: Int?) : FormValue
    data class Strings(val value: List<String>) : FormValue
    data class Refs(val value: List<Ref>) : FormValue
    /** IMAGE: [preview] is the current image; [upload] is what will be sent (blank = unchanged). */
    data class Image(val preview: String?, val upload: String = "") : FormValue
}

data class EditSpec(
    val kind: EditKind,
    val loadQuery: String,
    /** Pulls the entity object out of the query's data. */
    val extract: (JsonObject) -> JsonObject?,
    val updateMutation: String,
    val createMutation: String? = null,
    val createField: String? = null,
    val destroyMutation: String? = null,
    /** Destroy options offered as checkboxes (delete_file, delete_generated). */
    val destroyFileOptions: Boolean = false,
    val fields: List<FieldSpec>,
)

// ------------------------------------------------------------ JSON helpers

private fun JsonObject.path(dotted: String): JsonElement? {
    var cur: JsonElement? = this
    for (part in dotted.split('.')) {
        cur = (cur as? JsonObject)?.get(part) ?: return null
    }
    return cur
}

private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull

private fun JsonElement?.toRef(media: (String?) -> String?): Ref? {
    val o = this as? JsonObject ?: return null
    val id = o["id"].str() ?: return null
    val name = o["name"].str()?.ifBlank { null }
        ?: o["title"].str()?.ifBlank { null }
        ?: (o["files"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonObject)?.get("basename").str() }
        ?: "#$id"
    val img = o["image_path"].str() ?: (o["paths"] as? JsonObject)?.let { it["screenshot"].str() ?: it["cover"].str() }
        ?: o["front_image_path"].str()
    return Ref(id, name, media(img))
}

/** Seconds → "1:23:45" / "3:07". */
fun secondsToClock(seconds: Double): String {
    val whole = seconds.toLong()
    val frac = seconds - whole
    val base = formatDuration(seconds)
    return if (frac >= 0.05) base + ".%d".format((frac * 10).toInt()) else base
}

/** "1:23:45", "3:07.5" or "187.5" → seconds; null if unparseable. */
fun clockToSeconds(text: String): Double? {
    val t = text.trim()
    if (t.isEmpty()) return null
    if (!t.contains(':')) return t.toDoubleOrNull()
    val parts = t.split(':')
    if (parts.size > 3) return null
    var total = 0.0
    for (p in parts) {
        val v = p.trim().toDoubleOrNull() ?: return null
        total = total * 60 + v
    }
    return total
}

object EditForms {

    /** Loaded entity JSON → initial form values. */
    fun valuesFrom(spec: EditSpec, entity: JsonObject?, media: (String?) -> String?): Map<String, FormValue> =
        spec.fields.associate { f ->
            val el = entity?.path(f.read)
            f.key to when (f.type) {
                FType.TEXT, FType.MULTILINE, FType.DATE, FType.ENUM, FType.INT, FType.FLOAT ->
                    FormValue.Text(el.str() ?: "")
                FType.TIME -> FormValue.Text((el as? JsonPrimitive)?.doubleOrNull?.let { secondsToClock(it) } ?: "")
                FType.BOOL -> FormValue.Flag((el as? JsonPrimitive)?.booleanOrNull ?: false)
                FType.RATING -> FormValue.Rating((el as? JsonPrimitive)?.intOrNull)
                FType.STRINGS -> FormValue.Strings((el as? JsonArray)?.mapNotNull { it.str() } ?: emptyList())
                FType.REF -> FormValue.Refs(listOfNotNull(el.toRef(media)))
                FType.REFS -> FormValue.Refs((el as? JsonArray)?.mapNotNull { it.toRef(media) } ?: emptyList())
                FType.LINKS -> FormValue.Refs(
                    (el as? JsonArray)?.mapNotNull { item ->
                        val o = item as? JsonObject ?: return@mapNotNull null
                        o[f.linkObject].toRef(media)?.copy(extra = f.linkExtra?.let { o[it].str() } ?: "")
                    } ?: emptyList(),
                )
                FType.IMAGE -> FormValue.Image(media(el.str()))
            }
        }

    /** Problems that block saving, keyed by field. */
    fun validate(spec: EditSpec, values: Map<String, FormValue>): Map<String, String> {
        val errors = mutableMapOf<String, String>()
        for (f in spec.fields) {
            val v = values[f.key]
            val text = (v as? FormValue.Text)?.value?.trim().orEmpty()
            when (f.type) {
                FType.INT -> if (text.isNotEmpty() && text.toIntOrNull() == null) errors[f.key] = "Whole number"
                FType.FLOAT -> if (text.isNotEmpty() && text.toDoubleOrNull() == null) errors[f.key] = "Number"
                FType.TIME -> if (text.isNotEmpty() && clockToSeconds(text) == null) errors[f.key] = "Use 1:23:45 or seconds"
                FType.DATE -> if (text.isNotEmpty() && !Regex("""\d{4}(-\d{2}(-\d{2})?)?""").matches(text)) {
                    errors[f.key] = "YYYY-MM-DD"
                }
                else -> Unit
            }
            if (f.required) {
                val empty = when (v) {
                    is FormValue.Text -> v.value.isBlank()
                    is FormValue.Refs -> v.value.isEmpty()
                    null -> true
                    else -> false
                }
                if (empty) errors[f.key] = "Required"
            }
        }
        return errors
    }

    /**
     * Form values → mutation input. For updates every field is sent (blank = cleared);
     * for creates blank fields are left out.
     */
    fun toInput(spec: EditSpec, values: Map<String, FormValue>, id: String?, extra: Map<String, JsonElement> = emptyMap()): JsonObject =
        buildJsonObject {
            if (id != null) put("id", id)
            val creating = id == null
            for (f in spec.fields) {
                if (f.readOnly) continue
                val v = values[f.key] ?: continue
                val el: JsonElement? = when (f.type) {
                    FType.TEXT, FType.MULTILINE, FType.DATE, FType.ENUM -> {
                        val t = (v as FormValue.Text).value.trim()
                        if (t.isEmpty()) JsonNull else JsonPrimitive(t)
                    }
                    FType.INT -> (v as FormValue.Text).value.trim().toIntOrNull()?.let { JsonPrimitive(it) } ?: JsonNull
                    FType.FLOAT -> (v as FormValue.Text).value.trim().toDoubleOrNull()?.let { JsonPrimitive(it) } ?: JsonNull
                    FType.TIME -> clockToSeconds((v as FormValue.Text).value)?.let {
                        if (f.intSeconds) JsonPrimitive(it.toInt()) else JsonPrimitive(it)
                    } ?: JsonNull
                    FType.BOOL -> JsonPrimitive((v as FormValue.Flag).value)
                    FType.RATING -> (v as FormValue.Rating).value?.let { JsonPrimitive(it) } ?: JsonNull
                    FType.STRINGS -> buildJsonArray {
                        (v as FormValue.Strings).value.map { it.trim() }.filter { it.isNotEmpty() }.forEach { add(JsonPrimitive(it)) }
                    }
                    FType.REF -> (v as FormValue.Refs).value.firstOrNull()?.let { JsonPrimitive(it.id) } ?: JsonNull
                    FType.REFS -> buildJsonArray { (v as FormValue.Refs).value.forEach { add(JsonPrimitive(it.id)) } }
                    FType.LINKS -> buildJsonArray {
                        (v as FormValue.Refs).value.forEach { r ->
                            add(buildJsonObject {
                                put(f.linkIdKey, r.id)
                                val ex = f.linkExtra
                                if (ex != null && r.extra.isNotBlank()) {
                                    // scene_index is an Int, description a String.
                                    val asInt = r.extra.trim().toIntOrNull()
                                    if (ex == "scene_index") {
                                        if (asInt != null) put(ex, asInt)
                                    } else {
                                        put(ex, r.extra.trim())
                                    }
                                }
                            })
                        }
                    }
                    FType.IMAGE -> (v as FormValue.Image).upload.takeIf { it.isNotBlank() }?.let { JsonPrimitive(it) }
                }
                if (el == null) continue
                if (creating && (el is JsonNull || (el is JsonArray && el.isEmpty()))) continue
                put(f.key, el)
            }
            extra.forEach { (k, v) -> put(k, v) }
        }
}

// ------------------------------------------------------------ specs

object EditSpecs {
    private val genders = listOf(
        "" to "—", "FEMALE" to "Female", "MALE" to "Male", "TRANSGENDER_FEMALE" to "Transgender female",
        "TRANSGENDER_MALE" to "Transgender male", "INTERSEX" to "Intersex", "NON_BINARY" to "Non-binary",
    )
    private val circumcised = listOf("" to "—", "CUT" to "Cut", "UNCUT" to "Uncut")

    fun of(kind: EditKind): EditSpec = when (kind) {
        EditKind.SCENE -> EditSpec(
            kind,
            loadQuery = """
query EditScene(${'$'}id: ID!) {
  findScene(id: ${'$'}id) {
    id title code details director urls date rating100 organized
    paths { screenshot }
    studio { id name image_path }
    performers { id name image_path }
    tags { id name }
    galleries { id title paths { cover } }
    groups { scene_index group { id name front_image_path } }
  }
}""",
            extract = { it["findScene"] as? JsonObject },
            updateMutation = """
mutation SaveScene(${'$'}input: SceneUpdateInput!) { result: sceneUpdate(input: ${'$'}input) { id } }""",
            destroyMutation = """
mutation DeleteScene(${'$'}input: SceneDestroyInput!) { sceneDestroy(input: ${'$'}input) }""",
            destroyFileOptions = true,
            fields = listOf(
                FieldSpec("title", "Title", FType.TEXT),
                FieldSpec("code", "Studio code", FType.TEXT),
                FieldSpec("date", "Date", FType.DATE),
                FieldSpec("director", "Director", FType.TEXT),
                FieldSpec("rating100", "Rating", FType.RATING),
                FieldSpec("organized", "Organized", FType.BOOL),
                FieldSpec("studio_id", "Studio", FType.REF, read = "studio", refKind = EntityKind.STUDIOS),
                FieldSpec("performer_ids", "Performers", FType.REFS, read = "performers", refKind = EntityKind.PERFORMERS),
                FieldSpec("tag_ids", "Tags", FType.REFS, read = "tags", refKind = EntityKind.TAGS),
                FieldSpec(
                    "groups", "Groups", FType.LINKS, read = "groups", refKind = EntityKind.GROUPS,
                    linkExtra = "scene_index", hint = "Scene number in the group",
                ),
                FieldSpec("gallery_ids", "Galleries", FType.REFS, read = "galleries", refKind = EntityKind.GALLERIES),
                FieldSpec("urls", "URLs", FType.STRINGS),
                FieldSpec("details", "Details", FType.MULTILINE),
                FieldSpec("cover_image", "Cover image", FType.IMAGE, read = "paths.screenshot"),
            ),
        )

        EditKind.PERFORMER -> EditSpec(
            kind,
            loadQuery = """
query EditPerformer(${'$'}id: ID!) {
  findPerformer(id: ${'$'}id) {
    id name disambiguation alias_list gender birthdate death_date country ethnicity
    hair_color eye_color height_cm weight measurements fake_tits penis_length circumcised
    career_start career_end tattoos piercings urls details rating100 favorite ignore_auto_tag image_path
    tags { id name }
  }
}""",
            extract = { it["findPerformer"] as? JsonObject },
            updateMutation = """
mutation SavePerformer(${'$'}input: PerformerUpdateInput!) { result: performerUpdate(input: ${'$'}input) { id } }""",
            createMutation = """
mutation CreatePerformer(${'$'}input: PerformerCreateInput!) { result: performerCreate(input: ${'$'}input) { id } }""",
            destroyMutation = """
mutation DeletePerformer(${'$'}input: PerformerDestroyInput!) { performerDestroy(input: ${'$'}input) }""",
            fields = listOf(
                FieldSpec("name", "Name", FType.TEXT, required = true),
                FieldSpec("disambiguation", "Disambiguation", FType.TEXT),
                FieldSpec("alias_list", "Aliases", FType.STRINGS),
                FieldSpec("gender", "Gender", FType.ENUM, options = genders),
                FieldSpec("birthdate", "Birthdate", FType.DATE),
                FieldSpec("death_date", "Death date", FType.DATE),
                FieldSpec("country", "Country", FType.TEXT),
                FieldSpec("ethnicity", "Ethnicity", FType.TEXT),
                FieldSpec("hair_color", "Hair color", FType.TEXT),
                FieldSpec("eye_color", "Eye color", FType.TEXT),
                FieldSpec("height_cm", "Height (cm)", FType.INT),
                FieldSpec("weight", "Weight (kg)", FType.INT),
                FieldSpec("measurements", "Measurements", FType.TEXT),
                FieldSpec("fake_tits", "Fake tits", FType.TEXT),
                FieldSpec("penis_length", "Penis length (cm)", FType.FLOAT),
                FieldSpec("circumcised", "Circumcised", FType.ENUM, options = circumcised),
                FieldSpec("career_start", "Career start", FType.TEXT, hint = "Year"),
                FieldSpec("career_end", "Career end", FType.TEXT, hint = "Year"),
                FieldSpec("tattoos", "Tattoos", FType.MULTILINE),
                FieldSpec("piercings", "Piercings", FType.MULTILINE),
                FieldSpec("rating100", "Rating", FType.RATING),
                FieldSpec("favorite", "Favorite", FType.BOOL),
                FieldSpec("tag_ids", "Tags", FType.REFS, read = "tags", refKind = EntityKind.TAGS),
                FieldSpec("urls", "URLs", FType.STRINGS),
                FieldSpec("details", "Details", FType.MULTILINE),
                FieldSpec("ignore_auto_tag", "Ignore auto tag", FType.BOOL),
                FieldSpec("image", "Image", FType.IMAGE, read = "image_path"),
            ),
        )

        EditKind.STUDIO -> EditSpec(
            kind,
            loadQuery = """
query EditStudio(${'$'}id: ID!) {
  findStudio(id: ${'$'}id) {
    id name aliases urls details rating100 favorite ignore_auto_tag image_path
    parent_studio { id name image_path }
    tags { id name }
  }
}""",
            extract = { it["findStudio"] as? JsonObject },
            updateMutation = """
mutation SaveStudio(${'$'}input: StudioUpdateInput!) { result: studioUpdate(input: ${'$'}input) { id } }""",
            createMutation = """
mutation CreateStudio(${'$'}input: StudioCreateInput!) { result: studioCreate(input: ${'$'}input) { id } }""",
            destroyMutation = """
mutation DeleteStudio(${'$'}input: StudioDestroyInput!) { studioDestroy(input: ${'$'}input) }""",
            fields = listOf(
                FieldSpec("name", "Name", FType.TEXT, required = true),
                FieldSpec("aliases", "Aliases", FType.STRINGS),
                FieldSpec("parent_id", "Parent studio", FType.REF, read = "parent_studio", refKind = EntityKind.STUDIOS),
                FieldSpec("rating100", "Rating", FType.RATING),
                FieldSpec("favorite", "Favorite", FType.BOOL),
                FieldSpec("tag_ids", "Tags", FType.REFS, read = "tags", refKind = EntityKind.TAGS),
                FieldSpec("urls", "URLs", FType.STRINGS),
                FieldSpec("details", "Details", FType.MULTILINE),
                FieldSpec("ignore_auto_tag", "Ignore auto tag", FType.BOOL),
                FieldSpec("image", "Image", FType.IMAGE, read = "image_path"),
            ),
        )

        EditKind.TAG -> EditSpec(
            kind,
            loadQuery = """
query EditTag(${'$'}id: ID!) {
  findTag(id: ${'$'}id) {
    id name sort_name description aliases favorite ignore_auto_tag image_path
    parents { id name }
    children { id name }
  }
}""",
            extract = { it["findTag"] as? JsonObject },
            updateMutation = """
mutation SaveTag(${'$'}input: TagUpdateInput!) { result: tagUpdate(input: ${'$'}input) { id } }""",
            createMutation = """
mutation CreateTag(${'$'}input: TagCreateInput!) { result: tagCreate(input: ${'$'}input) { id } }""",
            destroyMutation = """
mutation DeleteTag(${'$'}input: TagDestroyInput!) { tagDestroy(input: ${'$'}input) }""",
            fields = listOf(
                FieldSpec("name", "Name", FType.TEXT, required = true),
                FieldSpec("sort_name", "Sort name", FType.TEXT, hint = "Overrides the name when sorting"),
                FieldSpec("aliases", "Aliases", FType.STRINGS),
                FieldSpec("description", "Description", FType.MULTILINE),
                FieldSpec("parent_ids", "Parent tags", FType.REFS, read = "parents", refKind = EntityKind.TAGS),
                FieldSpec("child_ids", "Sub-tags", FType.REFS, read = "children", refKind = EntityKind.TAGS),
                FieldSpec("favorite", "Favorite", FType.BOOL),
                FieldSpec("ignore_auto_tag", "Ignore auto tag", FType.BOOL),
                FieldSpec("image", "Image", FType.IMAGE, read = "image_path"),
            ),
        )

        EditKind.GROUP -> EditSpec(
            kind,
            loadQuery = """
query EditGroup(${'$'}id: ID!) {
  findGroup(id: ${'$'}id) {
    id name aliases duration date rating100 director synopsis urls front_image_path back_image_path
    studio { id name image_path }
    tags { id name }
    containing_groups { description group { id name front_image_path } }
    sub_groups { description group { id name front_image_path } }
  }
}""",
            extract = { it["findGroup"] as? JsonObject },
            updateMutation = """
mutation SaveGroup(${'$'}input: GroupUpdateInput!) { result: groupUpdate(input: ${'$'}input) { id } }""",
            createMutation = """
mutation CreateGroup(${'$'}input: GroupCreateInput!) { result: groupCreate(input: ${'$'}input) { id } }""",
            destroyMutation = """
mutation DeleteGroup(${'$'}input: GroupDestroyInput!) { groupDestroy(input: ${'$'}input) }""",
            fields = listOf(
                FieldSpec("name", "Name", FType.TEXT, required = true),
                FieldSpec("aliases", "Aliases", FType.TEXT),
                FieldSpec("date", "Date", FType.DATE),
                FieldSpec("duration", "Duration", FType.TIME, intSeconds = true, hint = "1:45:00"),
                FieldSpec("rating100", "Rating", FType.RATING),
                FieldSpec("studio_id", "Studio", FType.REF, read = "studio", refKind = EntityKind.STUDIOS),
                FieldSpec("director", "Director", FType.TEXT),
                FieldSpec("tag_ids", "Tags", FType.REFS, read = "tags", refKind = EntityKind.TAGS),
                FieldSpec(
                    "containing_groups", "Part of groups", FType.LINKS, read = "containing_groups",
                    refKind = EntityKind.GROUPS, linkExtra = "description", hint = "Description",
                ),
                FieldSpec(
                    "sub_groups", "Sub-groups", FType.LINKS, read = "sub_groups",
                    refKind = EntityKind.GROUPS, linkExtra = "description", hint = "Description",
                ),
                FieldSpec("urls", "URLs", FType.STRINGS),
                FieldSpec("synopsis", "Synopsis", FType.MULTILINE),
                FieldSpec("front_image", "Front cover", FType.IMAGE, read = "front_image_path"),
                FieldSpec("back_image", "Back cover", FType.IMAGE, read = "back_image_path"),
            ),
        )

        EditKind.GALLERY -> EditSpec(
            kind,
            loadQuery = """
query EditGallery(${'$'}id: ID!) {
  findGallery(id: ${'$'}id) {
    id title code urls date details photographer rating100 organized
    studio { id name image_path }
    performers { id name image_path }
    tags { id name }
    scenes { id title files { basename } paths { screenshot } }
  }
}""",
            extract = { it["findGallery"] as? JsonObject },
            updateMutation = """
mutation SaveGallery(${'$'}input: GalleryUpdateInput!) { result: galleryUpdate(input: ${'$'}input) { id } }""",
            createMutation = """
mutation CreateGallery(${'$'}input: GalleryCreateInput!) { result: galleryCreate(input: ${'$'}input) { id } }""",
            destroyMutation = """
mutation DeleteGallery(${'$'}input: GalleryDestroyInput!) { galleryDestroy(input: ${'$'}input) }""",
            destroyFileOptions = true,
            fields = listOf(
                FieldSpec("title", "Title", FType.TEXT, required = true),
                FieldSpec("code", "Code", FType.TEXT),
                FieldSpec("date", "Date", FType.DATE),
                FieldSpec("photographer", "Photographer", FType.TEXT),
                FieldSpec("rating100", "Rating", FType.RATING),
                FieldSpec("organized", "Organized", FType.BOOL),
                FieldSpec("studio_id", "Studio", FType.REF, read = "studio", refKind = EntityKind.STUDIOS),
                FieldSpec("performer_ids", "Performers", FType.REFS, read = "performers", refKind = EntityKind.PERFORMERS),
                FieldSpec("tag_ids", "Tags", FType.REFS, read = "tags", refKind = EntityKind.TAGS),
                FieldSpec("scene_ids", "Scenes", FType.REFS, read = "scenes", refKind = EntityKind.SCENES),
                FieldSpec("urls", "URLs", FType.STRINGS),
                FieldSpec("details", "Details", FType.MULTILINE),
            ),
        )

        EditKind.IMAGE -> EditSpec(
            kind,
            loadQuery = """
query EditImage(${'$'}id: ID!) {
  findImage(id: ${'$'}id) {
    id title code urls date details photographer rating100 organized
    studio { id name image_path }
    performers { id name image_path }
    tags { id name }
    galleries { id title paths { cover } }
  }
}""",
            extract = { it["findImage"] as? JsonObject },
            updateMutation = """
mutation SaveImage(${'$'}input: ImageUpdateInput!) { result: imageUpdate(input: ${'$'}input) { id } }""",
            destroyMutation = """
mutation DeleteImage(${'$'}input: ImageDestroyInput!) { imageDestroy(input: ${'$'}input) }""",
            destroyFileOptions = true,
            fields = listOf(
                FieldSpec("title", "Title", FType.TEXT),
                FieldSpec("code", "Code", FType.TEXT),
                FieldSpec("date", "Date", FType.DATE),
                FieldSpec("photographer", "Photographer", FType.TEXT),
                FieldSpec("rating100", "Rating", FType.RATING),
                FieldSpec("organized", "Organized", FType.BOOL),
                FieldSpec("studio_id", "Studio", FType.REF, read = "studio", refKind = EntityKind.STUDIOS),
                FieldSpec("performer_ids", "Performers", FType.REFS, read = "performers", refKind = EntityKind.PERFORMERS),
                FieldSpec("tag_ids", "Tags", FType.REFS, read = "tags", refKind = EntityKind.TAGS),
                FieldSpec("gallery_ids", "Galleries", FType.REFS, read = "galleries", refKind = EntityKind.GALLERIES),
                FieldSpec("urls", "URLs", FType.STRINGS),
                FieldSpec("details", "Details", FType.MULTILINE),
            ),
        )

        EditKind.MARKER -> EditSpec(
            kind,
            loadQuery = """
query EditMarker(${'$'}id: ID!) {
  findSceneMarkers(ids: [${'$'}id]) {
    scene_markers {
      id title seconds end_seconds
      primary_tag { id name }
      tags { id name }
      scene { id title files { basename } paths { screenshot } }
    }
  }
}""",
            extract = { ((it["findSceneMarkers"] as? JsonObject)?.get("scene_markers") as? JsonArray)?.firstOrNull() as? JsonObject },
            updateMutation = """
mutation SaveMarker(${'$'}input: SceneMarkerUpdateInput!) { result: sceneMarkerUpdate(input: ${'$'}input) { id } }""",
            createMutation = """
mutation CreateMarker(${'$'}input: SceneMarkerCreateInput!) { result: sceneMarkerCreate(input: ${'$'}input) { id } }""",
            destroyMutation = """
mutation DeleteMarker(${'$'}id: ID!) { sceneMarkerDestroy(id: ${'$'}id) }""",
            fields = listOf(
                FieldSpec("title", "Title", FType.TEXT, hint = "Optional; shown instead of the primary tag"),
                FieldSpec("seconds", "Start", FType.TIME, required = true, hint = "1:23 or 83.5"),
                FieldSpec("end_seconds", "End", FType.TIME, hint = "Optional"),
                FieldSpec("primary_tag_id", "Primary tag", FType.REF, read = "primary_tag", refKind = EntityKind.TAGS, required = true),
                FieldSpec("tag_ids", "Tags", FType.REFS, read = "tags", refKind = EntityKind.TAGS),
                FieldSpec("scene_id", "Scene", FType.REF, read = "scene", refKind = EntityKind.SCENES, readOnly = true),
            ),
        )
    }

    /** Minimal "create by name" used by pickers (add a tag/performer/studio/group on the fly). */
    fun quickCreate(kind: EntityKind): Pair<String, String>? = when (kind) {
        EntityKind.TAGS -> "tagCreate" to "TagCreateInput"
        EntityKind.PERFORMERS -> "performerCreate" to "PerformerCreateInput"
        EntityKind.STUDIOS -> "studioCreate" to "StudioCreateInput"
        EntityKind.GROUPS -> "groupCreate" to "GroupCreateInput"
        else -> null
    }
}
