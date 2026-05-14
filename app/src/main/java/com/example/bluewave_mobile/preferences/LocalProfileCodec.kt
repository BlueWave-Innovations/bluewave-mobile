package com.example.bluewave_mobile.preferences

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * On-the-wire JSON shape used for the
 * [com.example.bluewave_mobile.network.BlueWaveFrame.Type.PROFILE_METADATA]
 * inner payload.
 *
 * Keeping a separate DTO from [LocalProfile] gives us:
 *  * a stable wire format that survives field additions on the
 *    domain model (`@SerialName` decouples on-wire keys from Kotlin
 *    property names);
 *  * forward / backward compatibility — `Json { ignoreUnknownKeys
 *    = true }` means an older receiver simply drops fields it does
 *    not understand, instead of throwing a parse error;
 *  * a shape we can extend without touching the persistence model
 *    (e.g. a future `pronouns` or `language` field).
 */
@Serializable
internal data class ProfileMetadataDto(
    val displayName: String = "",
    val handle: String = "",
    val bio: String = "",
    val avatarUri: String = "",
)

/**
 * Pure codec that converts a [LocalProfile] to / from the JSON
 * payload that crosses the wire inside a `PROFILE_METADATA` frame.
 *
 * Implemented with `kotlinx.serialization.json.Json` (already on
 * the classpath via `libs.kotlinx.serialization.json`) so we get
 * structured parsing rather than hand-rolled string concatenation.
 *
 * The codec is intentionally tolerant: unknown JSON keys are
 * dropped, missing keys default to empty strings, and arbitrary
 * malformed payloads return `null` from [decode] rather than
 * throwing — the repository turns `null` into "drop the frame".
 */
object LocalProfileCodec {

    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    /** Serialize [profile] into UTF-8 JSON bytes. */
    fun encode(profile: LocalProfile): ByteArray {
        val dto = ProfileMetadataDto(
            displayName = profile.displayName,
            handle = profile.handle,
            bio = profile.bio,
            avatarUri = profile.avatarUri,
        )
        return json.encodeToString(ProfileMetadataDto.serializer(), dto)
            .toByteArray(Charsets.UTF_8)
    }

    /**
     * Parse [bytes] into a [LocalProfile]. Returns `null` if the
     * payload is empty, not valid UTF-8 or not a JSON object the
     * codec can deserialize.
     */
    fun decode(bytes: ByteArray): LocalProfile? {
        if (bytes.isEmpty()) return null
        val text = runCatching { bytes.toString(Charsets.UTF_8) }.getOrNull() ?: return null
        val dto = runCatching {
            json.decodeFromString(ProfileMetadataDto.serializer(), text)
        }.getOrNull() ?: return null
        return LocalProfile(
            displayName = dto.displayName,
            handle = dto.handle,
            bio = dto.bio,
            avatarUri = dto.avatarUri,
        )
    }
}
