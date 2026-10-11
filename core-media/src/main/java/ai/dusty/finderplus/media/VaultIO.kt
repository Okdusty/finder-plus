package ai.dusty.finderplus.media

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream

/**
 * The one way to open media bytes in this app. Gallery media opens through the resolver; vaulted
 * `.fpv` files decrypt transparently. Callers never learn which world a URI lives in.
 */
object VaultIO {

    fun openInputStream(context: Context, uri: String): InputStream? {
        VaultCrypto.init(context)
        return openMedia(context.contentResolver, uri)
    }
}

/** Resolver-level variant for call sites that already hold one. [VaultCrypto.init] must have run. */
fun openMedia(resolver: ContentResolver, uri: String): InputStream? = runCatching {
    val parsed = Uri.parse(uri)
    val path = parsed.path
    if (parsed.scheme == "file" && VaultCrypto.isVaultFile(path)) VaultCrypto.openDecrypting(File(path!!))
    else resolver.openInputStream(parsed)
}.getOrNull()
