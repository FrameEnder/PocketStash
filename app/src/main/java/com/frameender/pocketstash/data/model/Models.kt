package com.frameender.pocketstash.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// One class per Stash entity, shared by list and detail queries. List queries
// select fewer fields; everything not selected falls back to its default.

@Serializable
data class TagRef(
    val id: String,
    val name: String,
    @SerialName("image_path") val imagePath: String? = null,
)

@Serializable
data class PerformerRef(
    val id: String,
    val name: String,
    val disambiguation: String? = null,
    val gender: String? = null,
    val favorite: Boolean = false,
    @SerialName("image_path") val imagePath: String? = null,
)

@Serializable
data class StudioRef(
    val id: String,
    val name: String,
    @SerialName("image_path") val imagePath: String? = null,
)

@Serializable
data class GalleryRef(
    val id: String,
    val title: String? = null,
    @SerialName("image_count") val imageCount: Int? = null,
    val paths: GalleryPaths? = null,
)

@Serializable
data class GroupRef(
    val id: String,
    val name: String,
    @SerialName("front_image_path") val frontImagePath: String? = null,
)

@Serializable
data class SceneGroup(
    val group: GroupRef,
    @SerialName("scene_index") val sceneIndex: Int? = null,
)

@Serializable
data class GroupDescription(
    val group: GroupRef,
    val description: String? = null,
)

@Serializable
data class VideoFile(
    val id: String? = null,
    val path: String? = null,
    val basename: String? = null,
    val size: Long? = null,
    val format: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val duration: Double? = null,
    @SerialName("video_codec") val videoCodec: String? = null,
    @SerialName("audio_codec") val audioCodec: String? = null,
    @SerialName("frame_rate") val frameRate: Double? = null,
    @SerialName("bit_rate") val bitRate: Long? = null,
)

@Serializable
data class ScenePaths(
    val screenshot: String? = null,
    val preview: String? = null,
    val stream: String? = null,
    val webp: String? = null,
    val vtt: String? = null,
    val sprite: String? = null,
    val caption: String? = null,
)

@Serializable
data class StreamEndpoint(
    val url: String,
    @SerialName("mime_type") val mimeType: String? = null,
    val label: String? = null,
)

@Serializable
data class VideoCaption(
    @SerialName("language_code") val languageCode: String,
    @SerialName("caption_type") val captionType: String,
)

@Serializable
data class Marker(
    val id: String,
    val title: String = "",
    val seconds: Double = 0.0,
    @SerialName("end_seconds") val endSeconds: Double? = null,
    @SerialName("primary_tag") val primaryTag: TagRef? = null,
    val tags: List<TagRef> = emptyList(),
    val preview: String? = null,
    val screenshot: String? = null,
    val stream: String? = null,
    val scene: Scene? = null,
)

@Serializable
data class Scene(
    val id: String,
    val title: String? = null,
    val code: String? = null,
    val details: String? = null,
    val director: String? = null,
    val urls: List<String> = emptyList(),
    val date: String? = null,
    val rating100: Int? = null,
    val organized: Boolean = false,
    val interactive: Boolean = false,
    @SerialName("o_counter") val oCounter: Int? = null,
    @SerialName("resume_time") val resumeTime: Double? = null,
    @SerialName("play_count") val playCount: Int? = null,
    @SerialName("play_duration") val playDuration: Double? = null,
    @SerialName("last_played_at") val lastPlayedAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val files: List<VideoFile> = emptyList(),
    val paths: ScenePaths = ScenePaths(),
    val captions: List<VideoCaption>? = null,
    @SerialName("scene_markers") val markers: List<Marker> = emptyList(),
    val galleries: List<GalleryRef> = emptyList(),
    val studio: StudioRef? = null,
    val groups: List<SceneGroup> = emptyList(),
    val tags: List<TagRef> = emptyList(),
    val performers: List<PerformerRef> = emptyList(),
    @SerialName("sceneStreams") val streams: List<StreamEndpoint> = emptyList(),
) {
    val displayTitle: String
        get() = title?.takeIf { it.isNotBlank() }
            ?: files.firstOrNull()?.basename
            ?: files.firstOrNull()?.path?.substringAfterLast('/')
            ?: "Scene $id"
    val duration: Double? get() = files.firstOrNull()?.duration
}

@Serializable
data class Performer(
    val id: String,
    val name: String,
    val disambiguation: String? = null,
    val urls: List<String>? = null,
    val gender: String? = null,
    val birthdate: String? = null,
    @SerialName("death_date") val deathDate: String? = null,
    val ethnicity: String? = null,
    val country: String? = null,
    @SerialName("eye_color") val eyeColor: String? = null,
    @SerialName("hair_color") val hairColor: String? = null,
    @SerialName("height_cm") val heightCm: Int? = null,
    val weight: Int? = null,
    val measurements: String? = null,
    @SerialName("fake_tits") val fakeTits: String? = null,
    @SerialName("career_length") val careerLength: String? = null,
    val tattoos: String? = null,
    val piercings: String? = null,
    @SerialName("alias_list") val aliasList: List<String> = emptyList(),
    val favorite: Boolean = false,
    val rating100: Int? = null,
    val details: String? = null,
    @SerialName("image_path") val imagePath: String? = null,
    @SerialName("scene_count") val sceneCount: Int? = null,
    @SerialName("image_count") val imageCount: Int? = null,
    @SerialName("gallery_count") val galleryCount: Int? = null,
    @SerialName("group_count") val groupCount: Int? = null,
    @SerialName("o_counter") val oCounter: Int? = null,
    val tags: List<TagRef> = emptyList(),
)

@Serializable
data class Studio(
    val id: String,
    val name: String,
    val urls: List<String> = emptyList(),
    val aliases: List<String> = emptyList(),
    val details: String? = null,
    val rating100: Int? = null,
    val favorite: Boolean = false,
    @SerialName("image_path") val imagePath: String? = null,
    @SerialName("scene_count") val sceneCount: Int? = null,
    @SerialName("image_count") val imageCount: Int? = null,
    @SerialName("gallery_count") val galleryCount: Int? = null,
    @SerialName("performer_count") val performerCount: Int? = null,
    @SerialName("group_count") val groupCount: Int? = null,
    @SerialName("parent_studio") val parentStudio: StudioRef? = null,
    @SerialName("child_studios") val childStudios: List<StudioRef> = emptyList(),
    val tags: List<TagRef> = emptyList(),
)

@Serializable
data class Tag(
    val id: String,
    val name: String,
    val description: String? = null,
    val aliases: List<String> = emptyList(),
    val favorite: Boolean = false,
    @SerialName("image_path") val imagePath: String? = null,
    @SerialName("scene_count") val sceneCount: Int? = null,
    @SerialName("scene_marker_count") val markerCount: Int? = null,
    @SerialName("image_count") val imageCount: Int? = null,
    @SerialName("gallery_count") val galleryCount: Int? = null,
    @SerialName("performer_count") val performerCount: Int? = null,
    @SerialName("studio_count") val studioCount: Int? = null,
    @SerialName("group_count") val groupCount: Int? = null,
    val parents: List<TagRef> = emptyList(),
    val children: List<TagRef> = emptyList(),
)

@Serializable
data class GalleryPaths(
    val cover: String? = null,
    val preview: String? = null,
)

@Serializable
data class PathOnly(val path: String? = null)

@Serializable
data class Gallery(
    val id: String,
    val title: String? = null,
    val code: String? = null,
    val date: String? = null,
    val details: String? = null,
    val photographer: String? = null,
    val urls: List<String> = emptyList(),
    val rating100: Int? = null,
    val organized: Boolean = false,
    @SerialName("image_count") val imageCount: Int? = null,
    val paths: GalleryPaths = GalleryPaths(),
    val studio: StudioRef? = null,
    val performers: List<PerformerRef> = emptyList(),
    val tags: List<TagRef> = emptyList(),
    val scenes: List<Scene> = emptyList(),
    val files: List<PathOnly> = emptyList(),
    val folder: PathOnly? = null,
) {
    val displayTitle: String
        get() = title?.takeIf { it.isNotBlank() }
            ?: files.firstOrNull()?.path?.substringAfterLast('/')
            ?: folder?.path?.substringAfterLast('/')
            ?: "Gallery $id"
}

@Serializable
data class ImagePaths(
    val thumbnail: String? = null,
    val preview: String? = null,
    val image: String? = null,
)

@Serializable
data class VisualFile(
    @SerialName("__typename") val typename: String? = null,
    val path: String? = null,
    val width: Int? = null,
    val height: Int? = null,
)

@Serializable
data class StashImage(
    val id: String,
    val title: String? = null,
    val date: String? = null,
    val details: String? = null,
    val rating100: Int? = null,
    val organized: Boolean = false,
    @SerialName("o_counter") val oCounter: Int? = null,
    val paths: ImagePaths = ImagePaths(),
    @SerialName("visual_files") val visualFiles: List<VisualFile> = emptyList(),
    val galleries: List<GalleryRef> = emptyList(),
    val studio: StudioRef? = null,
    val performers: List<PerformerRef> = emptyList(),
    val tags: List<TagRef> = emptyList(),
) {
    val isVideo: Boolean get() = visualFiles.firstOrNull()?.typename == "VideoFile"
    val displayTitle: String
        get() = title?.takeIf { it.isNotBlank() }
            ?: visualFiles.firstOrNull()?.path?.substringAfterLast('/')
            ?: "Image $id"
}

@Serializable
data class Group(
    val id: String,
    val name: String,
    val aliases: String? = null,
    val duration: Int? = null,
    val date: String? = null,
    val rating100: Int? = null,
    val director: String? = null,
    val synopsis: String? = null,
    val urls: List<String> = emptyList(),
    @SerialName("front_image_path") val frontImagePath: String? = null,
    @SerialName("back_image_path") val backImagePath: String? = null,
    @SerialName("scene_count") val sceneCount: Int? = null,
    val studio: StudioRef? = null,
    val tags: List<TagRef> = emptyList(),
    @SerialName("containing_groups") val containingGroups: List<GroupDescription> = emptyList(),
    @SerialName("sub_groups") val subGroups: List<GroupDescription> = emptyList(),
)

@Serializable
data class Stats(
    @SerialName("scene_count") val sceneCount: Int = 0,
    @SerialName("scenes_size") val scenesSize: Double = 0.0,
    @SerialName("scenes_duration") val scenesDuration: Double = 0.0,
    @SerialName("image_count") val imageCount: Int = 0,
    @SerialName("images_size") val imagesSize: Double = 0.0,
    @SerialName("gallery_count") val galleryCount: Int = 0,
    @SerialName("performer_count") val performerCount: Int = 0,
    @SerialName("studio_count") val studioCount: Int = 0,
    @SerialName("group_count") val groupCount: Int = 0,
    @SerialName("tag_count") val tagCount: Int = 0,
    @SerialName("total_o_count") val totalOCount: Int = 0,
    @SerialName("total_play_duration") val totalPlayDuration: Double = 0.0,
    @SerialName("total_play_count") val totalPlayCount: Int = 0,
    @SerialName("scenes_played") val scenesPlayed: Int = 0,
)

@Serializable
data class ServerInfo(
    val version: String? = null,
    val status: String? = null,
)
