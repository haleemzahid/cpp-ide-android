package dev.cppide.ide.screens.about

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Wires the stateless [AboutScreen] to Android intents for opening
 * external URLs and the mail composer. Stateless — no VM needed since
 * About has no mutable state.
 */
@Composable
fun AboutRoute(
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    AboutScreen(
        onBack = onBack,
        onOpenWebsite = {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://shahidkhan.dev"))
            )
        },
        onOpenDawlify = {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://dawlify.com"))
            )
        },
        onEmail = {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:shahidkhan.dev88@gmail.com")
                putExtra(Intent.EXTRA_SUBJECT, "C++ IDE")
            }
            context.startActivity(intent)
        },
    )
}
