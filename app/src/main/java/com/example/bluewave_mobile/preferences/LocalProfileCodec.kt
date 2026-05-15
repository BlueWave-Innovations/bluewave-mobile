package com.example.bluewave_mobile.preferences

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.example.bluewave_mobile.utils.BlueWaveLogger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * On-the-wire JSON shape used for the
 * [com.example.bluewave_mobile.network.BlueWaveFrame.Type.PROFILE_METADATA]
 * inner payload.
 */
@Serializable
internal data class ProfileMetadataDto(
    val displayName: String = "",
    val handle: String = "",
    val bio: String = "",
    val avatarUri: String = "",
    val avatarBase64: String = "",
    val profileVersion: Long = 0L,
)

/**
 * Pure codec that converts a [LocalProfile] to / from the JSON
 * payload that crosses the wire inside a `PROFILE_METADATA` frame.
 *
 * Avatar images are embedded as Base64 JPEG thumbnails (max 256 px,
 * quality 70 %) so the receiver can reconstruct the file locally
 * without an extra round-trip.
 */
object LocalProfileCodec {

    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    /** Long-edge limit for avatar thumbnails. */
    private const val AVATAR_MAX_EDGE = 256
    /** JPEG quality for embedded avatars. */
    private const val AVATAR_JPEG_QUALITY = 70
    /** Avatars larger than this are compressed. */
    private const val AVATAR_COMPRESS_THRESHOLD = 30_000L

    /**
     * Serialize [profile] into UTF-8 JSON bytes.
     *
     * @param context Android [Context] used to resolve `content://` URIs
     *        from the photo picker. May be `null` in tests.
     * @param version Monotonic version stamp included in the payload so
     *        the receiver can ACK the exact revision they received.
     */
    fun encode(profile: LocalProfile, context: Context? = null, version: Long = 0L): ByteArray {
        val avatarB64 = profile.avatarUri?.let { uriString ->
            compressAvatarToBase64(uriString, context)
        } ?: ""
        val dto = ProfileMetadataDto(
            displayName = profile.displayName,
            handle = profile.handle,
            bio = profile.bio,
            avatarUri = "", // local path is useless to the peer; we send Base64 instead
            avatarBase64 = avatarB64,
            profileVersion = version,
        )
        return json.encodeToString(ProfileMetadataDto.serializer(), dto)
            .toByteArray(Charsets.UTF_8)
    }

    /**
     * Parse [bytes] into a [LocalProfile] and the embedded
     * [profileVersion]. If an embedded Base64 avatar is present it is
     * decoded and written to [destAvatarFile]; the returned
     * [LocalProfile.avatarUri] points at that local file.
     */
    fun decode(bytes: ByteArray, destAvatarFile: File? = null): Pair<LocalProfile, Long>? {
        if (bytes.isEmpty()) return null
        val text = runCatching { bytes.toString(Charsets.UTF_8) }.getOrNull() ?: return null
        val dto = runCatching {
            json.decodeFromString(ProfileMetadataDto.serializer(), text)
        }.getOrNull() ?: return null

        val localAvatarPath = if (!dto.avatarBase64.isNullOrBlank() && destAvatarFile != null) {
            runCatching {
                val raw = Base64.decode(dto.avatarBase64, Base64.DEFAULT)
                destAvatarFile.parentFile?.mkdirs()
                destAvatarFile.writeBytes(raw)
                destAvatarFile.absolutePath
            }.getOrNull()
        } else null

        val profile = LocalProfile(
            displayName = dto.displayName,
            handle = dto.handle,
            bio = dto.bio,
            avatarUri = localAvatarPath ?: dto.avatarUri,
        )
        return profile to dto.profileVersion
    }

    private fun compressAvatarToBase64(uriString: String, context: Context?): String {
        return runCatching {
            val bmp = when {
                uriString.startsWith("content://") -> {
                    context?.let { ctx ->
                        ctx.contentResolver.openInputStream(Uri.parse(uriString))?.use { stream ->
                            BitmapFactory.decodeStream(stream)
                        }
                    }
                }
                uriString.startsWith("file://") -> {
                    BitmapFactory.decodeFile(Uri.parse(uriString).path ?: return@runCatching "")
                }
                else -> {
                    BitmapFactory.decodeFile(uriString)
                }
            } ?: return@runCatching ""

            val scale = min(
                AVATAR_MAX_EDGE.toFloat() / max(bmp.width, bmp.height),
                1.0f,
            )
            val scaled = if (scale < 1.0f) {
                Bitmap.createScaledBitmap(
                    bmp,
                    (bmp.width * scale).toInt(),
                    (bmp.height * scale).toInt(),
                    true,
                ).also { bmp.recycle() }
            } else bmp

            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, AVATAR_JPEG_QUALITY, out)
            scaled.recycle()
            val bytes = out.toByteArray()
            BlueWaveLogger.i("LocalProfileCodec", "Avatar compressed to ${bytes.size} bytes")
            Base64.encodeToString(bytes, Base64.DEFAULT)
        }.getOrDefault("")
    }
}
