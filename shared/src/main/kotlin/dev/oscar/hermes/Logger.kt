package dev.oscar.hermes

/**
 * Diagnostic sink for the protocol layer, so :shared never touches android.util.Log.
 * Contract (unchanged from the original call site): method + reason + code only — never params or content.
 */
fun interface Logger {
    fun w(tag: String, msg: String)

    companion object {
        /** Default sink: stderr, no-op-ish on a phone until the platform installs its own. */
        var default: Logger = Logger { tag, msg -> System.err.println("W/$tag: $msg") }
    }
}
