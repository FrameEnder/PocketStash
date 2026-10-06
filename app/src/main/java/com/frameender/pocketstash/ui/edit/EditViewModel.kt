package com.frameender.pocketstash.ui.edit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.frameender.pocketstash.data.Connection
import com.frameender.pocketstash.data.EditForms
import com.frameender.pocketstash.data.EditKind
import com.frameender.pocketstash.data.EditSpec
import com.frameender.pocketstash.data.EditSpecs
import com.frameender.pocketstash.data.FormValue
import com.frameender.pocketstash.data.Ref
import com.frameender.pocketstash.data.StashRepository
import com.frameender.pocketstash.data.secondsToClock
import com.frameender.pocketstash.ui.common.friendly
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * State for one edit / create form.
 * @param id entity to edit, or null to create a new one.
 * @param sceneId / [startSeconds] pre-fill a new marker.
 */
class EditViewModel(
    private val repo: StashRepository,
    private val connection: Connection,
    val kind: EditKind,
    val id: String?,
    private val sceneId: String? = null,
    private val sceneTitle: String? = null,
    private val startSeconds: Double? = null,
) : ViewModel() {

    val spec: EditSpec = EditSpecs.of(kind)
    val creating: Boolean get() = id == null

    val values = mutableStateMapOf<String, FormValue>()
    private var initial: Map<String, FormValue> = emptyMap()

    var loading by mutableStateOf(true)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set
    var saving by mutableStateOf(false)
        private set
    var errors by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var message by mutableStateOf<String?>(null)

    val dirty: Boolean get() = values.toMap() != initial

    init { load() }

    fun load() {
        loading = true
        loadError = null
        viewModelScope.launch {
            try {
                val json = id?.let { repo.loadForEdit(spec, it) }
                val v = EditForms.valuesFrom(spec, json, connection::media).toMutableMap()
                if (id == null && kind == EditKind.MARKER) {
                    startSeconds?.let { v["seconds"] = FormValue.Text(secondsToClock(it)) }
                    sceneId?.let { v["scene_id"] = FormValue.Refs(listOf(Ref(it, sceneTitle ?: "Scene $it"))) }
                }
                values.clear()
                values.putAll(v)
                initial = v.toMap()
            } catch (e: Exception) {
                loadError = e.friendly()
            } finally {
                loading = false
            }
        }
    }

    fun set(key: String, value: FormValue) {
        values[key] = value
        if (key in errors) errors = errors - key
    }

    /** Validates and saves. Calls [onSaved] with the entity id on success. */
    fun save(onSaved: (String) -> Unit) {
        val problems = EditForms.validate(spec, values)
        if (problems.isNotEmpty()) {
            errors = problems
            message = "Fix the highlighted fields"
            return
        }
        saving = true
        viewModelScope.launch {
            try {
                val extra: Map<String, JsonElement> =
                    if (creating && kind == EditKind.MARKER && sceneId != null) mapOf("scene_id" to JsonPrimitive(sceneId))
                    else emptyMap()
                val input = EditForms.toInput(spec, values, id, extra)
                val savedId = repo.saveEdit(spec, input, creating)
                initial = values.toMap()
                onSaved(savedId)
            } catch (e: Exception) {
                message = e.friendly()
            } finally {
                saving = false
            }
        }
    }

    fun delete(deleteFile: Boolean, deleteGenerated: Boolean, onDeleted: () -> Unit) {
        val target = id ?: return
        saving = true
        viewModelScope.launch {
            try {
                repo.destroy(spec, target, deleteFile, deleteGenerated)
                onDeleted()
            } catch (e: Exception) {
                message = e.friendly()
            } finally {
                saving = false
            }
        }
    }

    suspend fun search(kind: com.frameender.pocketstash.data.EntityKind, text: String): List<Ref> =
        repo.searchRefs(kind, text)

    suspend fun quickCreate(kind: com.frameender.pocketstash.data.EntityKind, name: String): Ref =
        repo.quickCreate(kind, name)
}
