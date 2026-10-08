package com.frameender.pocketstash.data

/**
 * GraphQL documents, written against the Stash schema in stashapp/stash
 * (graphql/schema). Requires Stash v0.27+ (groups replaced movies).
 */
object Q {

    // ---------- card fragments (lists / grids) ----------

    val SCENE_CARD = """
fragment SceneCard on Scene {
  id title date rating100 o_counter play_count resume_time organized
  files { basename duration width height }
  paths { screenshot preview webp }
  studio { id name }
  performers { id name }
}"""

    val PERFORMER_CARD = """
fragment PerformerCard on Performer {
  id name disambiguation gender favorite rating100 image_path scene_count
}"""

    val STUDIO_CARD = """
fragment StudioCard on Studio {
  id name image_path scene_count favorite rating100
}"""

    val TAG_CARD = """
fragment TagCard on Tag {
  id name image_path scene_count favorite
}"""

    val GALLERY_CARD = """
fragment GalleryCard on Gallery {
  id title date rating100 image_count
  paths { cover }
  files { path }
  folder { path }
  studio { id name }
  performers { id name }
  tags { id name }
}"""

    val IMAGE_CARD = """
fragment ImageCard on Image {
  id title rating100 o_counter
  paths { thumbnail preview image }
  visual_files {
    __typename
    ... on ImageFile { path width height }
    ... on VideoFile { path width height }
  }
  galleries { id title }
  studio { id name }
  performers { id name }
  tags { id name }
}"""

    val GROUP_CARD = """
fragment GroupCard on Group {
  id name date rating100 front_image_path scene_count
  studio { id name }
}"""

    val MARKER_CARD = """
fragment MarkerCard on SceneMarker {
  id title seconds end_seconds
  primary_tag { id name }
  tags { id name }
  preview screenshot stream
  scene { id title files { basename } paths { screenshot } }
}"""

    // ---------- browse queries ----------

    val findScenes = """
query FindScenes(${'$'}filter: FindFilterType, ${'$'}f: SceneFilterType, ${'$'}ids: [ID!]) {
  result: findScenes(filter: ${'$'}filter, scene_filter: ${'$'}f, ids: ${'$'}ids) { count items: scenes { ...SceneCard } }
}$SCENE_CARD"""

    val findPerformers = """
query FindPerformers(${'$'}filter: FindFilterType, ${'$'}f: PerformerFilterType) {
  result: findPerformers(filter: ${'$'}filter, performer_filter: ${'$'}f) { count items: performers { ...PerformerCard } }
}$PERFORMER_CARD"""

    val findStudios = """
query FindStudios(${'$'}filter: FindFilterType, ${'$'}f: StudioFilterType) {
  result: findStudios(filter: ${'$'}filter, studio_filter: ${'$'}f) { count items: studios { ...StudioCard } }
}$STUDIO_CARD"""

    val findTags = """
query FindTags(${'$'}filter: FindFilterType, ${'$'}f: TagFilterType) {
  result: findTags(filter: ${'$'}filter, tag_filter: ${'$'}f) { count items: tags { ...TagCard } }
}$TAG_CARD"""

    val findGalleries = """
query FindGalleries(${'$'}filter: FindFilterType, ${'$'}f: GalleryFilterType) {
  result: findGalleries(filter: ${'$'}filter, gallery_filter: ${'$'}f) { count items: galleries { ...GalleryCard } }
}$GALLERY_CARD"""

    val findImages = """
query FindImages(${'$'}filter: FindFilterType, ${'$'}f: ImageFilterType) {
  result: findImages(filter: ${'$'}filter, image_filter: ${'$'}f) { count items: images { ...ImageCard } }
}$IMAGE_CARD"""

    val findGroups = """
query FindGroups(${'$'}filter: FindFilterType, ${'$'}f: GroupFilterType) {
  result: findGroups(filter: ${'$'}filter, group_filter: ${'$'}f) { count items: groups { ...GroupCard } }
}$GROUP_CARD"""

    val findMarkers = """
query FindMarkers(${'$'}filter: FindFilterType, ${'$'}f: SceneMarkerFilterType) {
  result: findSceneMarkers(filter: ${'$'}filter, scene_marker_filter: ${'$'}f) { count items: scene_markers { ...MarkerCard } }
}$MARKER_CARD"""

    // ---------- detail queries ----------

    val scene = """
query Scene(${'$'}id: ID!) {
  findScene(id: ${'$'}id) {
    id title code details director urls date rating100 organized interactive
    o_counter resume_time play_count play_duration last_played_at created_at updated_at
    files { id path basename size format width height duration video_codec audio_codec frame_rate bit_rate }
    paths { screenshot preview stream webp vtt sprite caption }
    captions { language_code caption_type }
    scene_markers { id title seconds end_seconds created_at primary_tag { id name } tags { id name } preview screenshot stream }
    galleries { id title image_count paths { cover } }
    studio { id name image_path }
    groups { scene_index group { id name front_image_path } }
    tags { id name image_path }
    performers { id name disambiguation gender favorite image_path }
    sceneStreams { url mime_type label }
  }
}"""

    val performer = """
query Performer(${'$'}id: ID!) {
  findPerformer(id: ${'$'}id) {
    id name disambiguation urls gender birthdate death_date ethnicity country
    eye_color hair_color height_cm weight measurements fake_tits career_length
    tattoos piercings alias_list favorite rating100 details image_path
    scene_count image_count gallery_count group_count o_counter created_at updated_at
    tags { id name }
  }
}"""

    val studio = """
query Studio(${'$'}id: ID!) {
  findStudio(id: ${'$'}id) {
    id name urls aliases details rating100 favorite image_path
    scene_count image_count gallery_count performer_count group_count created_at updated_at
    parent_studio { id name image_path }
    child_studios { id name image_path }
    tags { id name }
  }
}"""

    val tag = """
query Tag(${'$'}id: ID!) {
  findTag(id: ${'$'}id) {
    id name description aliases favorite image_path
    scene_count scene_marker_count image_count gallery_count performer_count studio_count group_count
    created_at updated_at sort_name
    parents { id name image_path }
    children { id name image_path }
  }
}"""

    val gallery = """
query Gallery(${'$'}id: ID!) {
  findGallery(id: ${'$'}id) {
    id title code date details photographer urls rating100 organized image_count created_at updated_at
    paths { cover }
    files { path }
    folder { path }
    studio { id name image_path }
    performers { id name image_path favorite }
    tags { id name }
    scenes { id title files { basename } paths { screenshot } }
  }
}"""

    val image = """
query Image(${'$'}id: ID!) {
  findImage(id: ${'$'}id) {
    id title date details rating100 organized o_counter created_at updated_at
    paths { thumbnail preview image }
    visual_files {
      __typename
      ... on ImageFile { path width height }
      ... on VideoFile { path width height }
    }
    galleries { id title }
    studio { id name image_path }
    performers { id name image_path }
    tags { id name }
  }
}"""

    val group = """
query Group(${'$'}id: ID!) {
  findGroup(id: ${'$'}id) {
    id name aliases duration date rating100 director synopsis urls
    front_image_path back_image_path scene_count created_at updated_at
    studio { id name image_path }
    tags { id name }
    containing_groups { description group { id name front_image_path } }
    sub_groups { description group { id name front_image_path } }
  }
}"""

    // ---------- system ----------

    val serverInfo = """
query ServerInfo {
  version { version }
  systemStatus { status }
}"""

    val stats = """
query Stats {
  stats {
    scene_count scenes_size scenes_duration image_count images_size gallery_count
    performer_count studio_count group_count tag_count total_o_count
    total_play_duration total_play_count scenes_played
  }
}"""

    // ---------- mutations ----------

    val sceneUpdate = """
mutation SceneUpdate(${'$'}input: SceneUpdateInput!) { sceneUpdate(input: ${'$'}input) { id rating100 organized } }"""

    val sceneAddO = """
mutation SceneAddO(${'$'}id: ID!) { sceneAddO(id: ${'$'}id) { count } }"""

    val sceneDeleteO = """
mutation SceneDeleteO(${'$'}id: ID!) { sceneDeleteO(id: ${'$'}id) { count } }"""

    val sceneAddPlay = """
mutation SceneAddPlay(${'$'}id: ID!) { sceneAddPlay(id: ${'$'}id) { count } }"""

    val sceneSaveActivity = """
mutation SceneSaveActivity(${'$'}id: ID!, ${'$'}resume: Float, ${'$'}duration: Float) {
  sceneSaveActivity(id: ${'$'}id, resume_time: ${'$'}resume, playDuration: ${'$'}duration)
}"""

    val performerUpdate = """
mutation PerformerUpdate(${'$'}input: PerformerUpdateInput!) { performerUpdate(input: ${'$'}input) { id favorite rating100 } }"""

    val studioUpdate = """
mutation StudioUpdate(${'$'}input: StudioUpdateInput!) { studioUpdate(input: ${'$'}input) { id favorite rating100 } }"""

    val tagUpdate = """
mutation TagUpdate(${'$'}input: TagUpdateInput!) { tagUpdate(input: ${'$'}input) { id favorite } }"""

    val galleryUpdate = """
mutation GalleryUpdate(${'$'}input: GalleryUpdateInput!) { galleryUpdate(input: ${'$'}input) { id rating100 organized } }"""

    val imageUpdate = """
mutation ImageUpdate(${'$'}input: ImageUpdateInput!) { imageUpdate(input: ${'$'}input) { id rating100 organized } }"""

    val groupUpdate = """
mutation GroupUpdate(${'$'}input: GroupUpdateInput!) { groupUpdate(input: ${'$'}input) { id rating100 } }"""

    val imageIncrementO = """
mutation ImageIncrementO(${'$'}id: ID!) { imageIncrementO(id: ${'$'}id) }"""

    val imageDecrementO = """
mutation ImageDecrementO(${'$'}id: ID!) { imageDecrementO(id: ${'$'}id) }"""
}
