package com.rr.client.qr

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.rr.client.R

class QrScanActivity : CaptureActivity() {
    companion object {
        private const val REQUEST_PICK_IMAGE = 7301
    }

    private lateinit var scannerView: DecoratedBarcodeView
    private var torchEnabled = false

    override fun initializeContent(): DecoratedBarcodeView {
        setContentView(R.layout.activity_qr_scan)
        scannerView = findViewById(R.id.zxing_barcode_scanner)

        findViewById<TextView>(R.id.qr_scan_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.qr_scan_flash).setOnClickListener { view ->
            torchEnabled = !torchEnabled
            if (torchEnabled) {
                scannerView.setTorchOn()
            } else {
                scannerView.setTorchOff()
            }
            (view as TextView).text = if (torchEnabled) "关闭手电筒" else "手电筒"
        }
        findViewById<TextView>(R.id.qr_scan_pick_image).setOnClickListener { openImagePicker() }

        return scannerView
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_PICK_IMAGE) {
            if (resultCode == Activity.RESULT_OK) data?.data?.let(::decodeImageFile)
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    @Suppress("DEPRECATION")
    private fun openImagePicker() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            },
            REQUEST_PICK_IMAGE
        )
    }

    private fun decodeImageFile(uri: Uri) {
        Toast.makeText(this, "正在读取二维码图片…", Toast.LENGTH_SHORT).show()
        Thread {
            val result = runCatching { QrImageDecoder.decode(this, uri) }.getOrNull()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (result.isNullOrBlank()) {
                    Toast.makeText(this, "没有识别到二维码，请选择更清晰的原图", Toast.LENGTH_LONG).show()
                } else {
                    finishWithQrResult(result)
                }
            }
        }.start()
    }

    private fun finishWithQrResult(text: String) {
        setResult(
            Activity.RESULT_OK,
            Intent(Intents.Scan.ACTION).apply {
                putExtra(Intents.Scan.RESULT, text)
                putExtra(Intents.Scan.RESULT_FORMAT, BarcodeFormat.QR_CODE.toString())
            }
        )
        finish()
    }
}
