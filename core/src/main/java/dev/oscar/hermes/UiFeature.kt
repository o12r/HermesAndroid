package dev.oscar.hermes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.vector.ImageVector

/** UI half of an optional feature module (see [ChatFeature]); every hook defaults to "nothing". */
interface UiFeature : ChatFeature {
    /** True if this feature draws the page tab [kind]. */
    fun hasPage(kind: String): Boolean = false
    @Composable fun Page(vm: ChatViewModel, kind: String) {}
    fun pageIcon(kind: String): ImageVector? = null
    /** Composed once at the app root (dialogs, background polling). */
    @Composable fun Overlay(vm: ChatViewModel) {}
    /** Content injected into a named spot of another screen, e.g. "pc.top". */
    @Composable fun Slot(vm: ChatViewModel, name: String) {}
    val hasFolderPicker: Boolean get() = false
    @Composable fun FolderPicker(vm: ChatViewModel, start: String?, onPick: (String) -> Unit, onClose: () -> Unit) {}
}

val ChatViewModel.uiFeatures: List<UiFeature> get() = c.features.filterIsInstance<UiFeature>()

/** PC folder picker if a feature provides one; otherwise closes at once so callers fall back to typing the path. */
@Composable
fun FolderPicker(vm: ChatViewModel, start: String?, onPick: (String) -> Unit, onClose: () -> Unit) {
    val f = vm.uiFeatures.firstOrNull { it.hasFolderPicker }
    if (f != null) f.FolderPicker(vm, start, onPick, onClose) else LaunchedEffect(Unit) { onClose() }
}
