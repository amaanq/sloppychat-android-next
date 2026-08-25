package io.element.android.libraries.androidutils.file

import android.content.Context
import android.net.Uri
import io.element.android.libraries.core.mimetype.MimeTypes
import io.element.android.libraries.core.mimetype.MimeTypes.isMimeTypeAnimatedImage

private const val MAX_ANIMATED_AVATAR_SIZE = 8L * 1024 * 1024

/**
 * AndroidMediaPreProcessor only skips compression for [MimeTypes.Gif] and [MimeTypes.WebP], so
 * those are the only types that survive an upload with their animation intact. They also skip the
 * size reduction that comes with compression, hence the cap above which we still re-encode.
 */
fun Context.avatarUploadMimeType(uri: Uri): String {
    val mimeType = getMimeType(uri)
    return if (mimeType.isMimeTypeAnimatedImage() && getFileSize(uri) <= MAX_ANIMATED_AVATAR_SIZE) {
        mimeType!!
    } else {
        MimeTypes.Jpeg
    }
}
