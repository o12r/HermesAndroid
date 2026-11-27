package dev.oscar.hermes

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.runtime.Composable

/** Optional feature: browse / upload / download files on the PC (page "files") and the project-folder picker. */
class PcFilesFeature : UiFeature {
    override val id = "pcfiles"
    override fun hasPage(kind: String) = kind == "files"
    override fun pageTitleKey(kind: String) = if (kind == "files") Str.pf_title else null
    override fun pageIcon(kind: String) = if (kind == "files") Icons.Default.Folder else null
    @Composable override fun Page(vm: ChatViewModel, kind: String) =
        PcFileBrowser(vm, pick = false, start = null, onPick = {}, onClose = { vm.c.leavePage() }, asPage = true)
    override val hasFolderPicker get() = true
    @Composable override fun FolderPicker(vm: ChatViewModel, start: String?, onPick: (String) -> Unit, onClose: () -> Unit) =
        PcFileBrowser(vm, pick = true, start = start, onPick = onPick, onClose = onClose)
}
