package android.print

/**
 * Shims for the two print callbacks.
 *
 * `PrintDocumentAdapter.LayoutResultCallback` and `WriteResultCallback` are public
 * abstract classes whose constructors are package-private:
 *
 *     android.print.PrintDocumentAdapter$LayoutResultCallback();   // no modifier
 *
 * so they cannot be subclassed from application code. Declaring these two tiny
 * subclasses in `android.print` is the only way to drive a PrintDocumentAdapter
 * directly — which is what lets the app write a real, vector, correctly paginated
 * PDF from a WebView without going through the system print dialog.
 *
 * Nothing else lives in this package.
 */
class LayoutCallback(
    private val finished: (PrintDocumentInfo) -> Unit,
    private val failed: (CharSequence?) -> Unit,
) : PrintDocumentAdapter.LayoutResultCallback() {

    override fun onLayoutFinished(info: PrintDocumentInfo, changed: Boolean) = finished(info)

    override fun onLayoutFailed(error: CharSequence?) = failed(error)

    override fun onLayoutCancelled() = failed("Layout cancelled")
}

class WriteCallback(
    private val finished: (Array<out PageRange>?) -> Unit,
    private val failed: (CharSequence?) -> Unit,
) : PrintDocumentAdapter.WriteResultCallback() {

    override fun onWriteFinished(pages: Array<out PageRange>?) = finished(pages)

    override fun onWriteFailed(error: CharSequence?) = failed(error)

    override fun onWriteCancelled() = failed("Write cancelled")
}
