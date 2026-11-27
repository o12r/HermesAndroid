package dev.oscar.hermes

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Android shell around [ChatController] (all chat logic lives in :shared): lifecycle, Uri-based file access, launcher icon. */
class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SecureStore(app)
    val c = ChatController(store, AndroidPlatform(app, store), viewModelScope, Dispatchers.Main)
    /** Scroll state of the tab row, shared by every screen so switching pages does not rebuild it. */
    val tabsState = LazyListState()

    /** Launcher icon: "dark" (black, white H) or "light" (white, black H); switches which activity-alias is enabled. */
    var appIcon by mutableStateOf(run {
        val pm = getApplication<android.app.Application>().packageManager
        val light = android.content.ComponentName(getApplication<android.app.Application>(), "dev.oscar.hermes.IconLight")
        if (pm.getComponentEnabledSetting(light) == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED) "light" else "dark"
    })
    fun chooseIcon(v: String) {
        if (v == appIcon) return
        val app = getApplication<android.app.Application>(); val pm = app.packageManager
        fun set(n: String, on: Boolean) = pm.setComponentEnabledSetting(android.content.ComponentName(app, "dev.oscar.hermes.$n"),
            if (on) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            android.content.pm.PackageManager.DONT_KILL_APP)
        set(if (v == "light") "IconLight" else "IconDark", true); set(if (v == "light") "IconDark" else "IconLight", false)
        appIcon = v
    }

    fun addAttachments(uris: List<Uri>) = c.addAttachments(uris.map { u -> { UriAttachment(getApplication(), u) } })

    suspend fun fsDownload(pcPath: String, name: String): Uri = Uri.parse(c.fsDownload(pcPath, name))
    suspend fun saveInboxNow(name: String, session: String?, anchor: Long = 0L): Uri = Uri.parse(c.saveInboxNow(name, session, anchor))
    suspend fun fsUpload(uri: Uri, folder: String, overwrite: Boolean) {
        val cr = getApplication<Application>().contentResolver
        var name = "file"
        withContext(Dispatchers.IO) { cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) name = it.getString(0) ?: name } }
        c.fsUpload(name, { cr.openInputStream(uri)!! }, folder, overwrite)
    }
    /** JPEG of one PC screen, decoded. */
    suspend fun screenShot(name: String): Bitmap = withContext(Dispatchers.IO) {
        val b = c.screenShotBytes(name)
        BitmapFactory.decodeByteArray(b, 0, b.size) ?: throw ApiException("bad image")
    }

    override fun onCleared() { c.close() }
}
