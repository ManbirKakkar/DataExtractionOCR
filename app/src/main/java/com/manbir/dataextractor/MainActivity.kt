package com.manbir.dataextractor

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var captureButton: Button
    private lateinit var resultText: TextView
    private var imageUri: Uri? = null
    private var croppedImageUri: Uri? = null
    private val timestamp = LogUtils.getTimestamp()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (!permissions.entries.all { it.value }) {
            Toast.makeText(this, "Permissions not granted", Toast.LENGTH_SHORT).show()
        }
    }

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            imageUri?.let { startCrop(it) }
        }
    }

    private val cropImage = registerForActivityResult(CropImageContract()) { result ->
        if (result.isSuccessful) {
            result.uriContent?.let { uri ->
                croppedImageUri = uri
                LogUtils.logDebug("Cropped image saved to: $uri")
                LogUtils.logDebug("Cropped image size: ${getFileSize(uri)}")
                processImage(uri)
            }
        } else {
            resultText.text = "Crop error: ${result.error?.message ?: "Unknown error"}"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        captureButton = findViewById(R.id.captureButton)
        resultText = findViewById(R.id.resultText)

        if (!allPermissionsGranted()) {
            requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
        }

        captureButton.setOnClickListener {
            captureImage()
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun captureImage() {
        val name = "capture_$timestamp.jpg"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DataExtractor")
            }
        }

        imageUri = contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        )

        imageUri?.let {
            LogUtils.logDebug("Original image URI: $it")
            takePicture.launch(it)
        }
    }

    private fun startCrop(uri: Uri) {
        val options = CropImageContractOptions(uri, CropImageOptions().apply {
            outputCompressQuality = 90 // 90% quality compression
            fixAspectRatio = false
            allowFlipping = false
            allowRotation = true
            activityTitle = "Crop Table Area"
        })
        cropImage.launch(options)
    }

    private fun getFileSize(uri: Uri): String {
        return try {
            val file = File(uri.path)
            val sizeBytes = file.length()
            val sizeKB = sizeBytes / 1024
            "$sizeKB KB"
        } catch (e: Exception) {
            "Unknown size"
        }
    }

    private fun processImage(uri: Uri) {
        try {
            val image = InputImage.fromFilePath(this, uri)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    // Save raw OCR text for debugging
                    val rawText = visionText.text
                    resultText.text = "RAW OCR OUTPUT:\n\n$rawText"

                    // Save raw OCR to file
                    val savedRawPaths = LogUtils.saveLogToFile(
                        this,
                        "ocr_raw_$timestamp.txt",
                        rawText
                    )
                    LogUtils.logDebug("Raw OCR saved to: $savedRawPaths")

                    // Process the text to extract table data
                    val tableData = parseTableFromVisionText(visionText)

                    if (tableData.isEmpty()) {
                        resultText.append("\n\nNO TABLE DATA FOUND")
                        val analysisReport = analyzeFailure(visionText)
                        resultText.append("\n\nANALYSIS REPORT:\n\n$analysisReport")

                        // Save analysis report
                        val savedReportPaths = LogUtils.saveLogToFile(
                            this,
                            "analysis_report_$timestamp.txt",
                            analysisReport
                        )
                        LogUtils.logDebug("Analysis report saved to: $savedReportPaths")
                    } else {
                        // Log and save parsed data
                        val jsonOutput = convertToJson(tableData)
                        LogUtils.logDebug("PARSED JSON DATA:\n$jsonOutput")

                        val savedJsonPaths = LogUtils.saveLogToFile(
                            this,
                            "parsed_data_$timestamp.json",
                            jsonOutput
                        )
                        LogUtils.logDebug("Parsed JSON saved to: $savedJsonPaths")

                        displayResults(tableData)
                    }
                }
                .addOnFailureListener { e ->
                    val errorMsg = "Processing failed: ${e.message}"
                    resultText.text = errorMsg
                    LogUtils.logError(errorMsg)
                }
        } catch (e: Exception) {
            val errorMsg = "Error loading image: ${e.message}"
            resultText.text = errorMsg
            LogUtils.logError(errorMsg)
        }
    }

    private fun parseTableFromVisionText(visionText: Text): List<TableRow> {
        val lines = visionText.text.split("\n")
        val rows = mutableListOf<TableRow>()

        // Find the header position to locate the table
        val headerIndex = lines.indexOfFirst {
            it.contains("Res Item", ignoreCase = true) &&
                    it.contains("Component", ignoreCase = true)
        }

        LogUtils.logDebug("Header found at index: $headerIndex")

        if (headerIndex == -1) {
            LogUtils.logDebug("No header found in OCR text")
            return emptyList()
        }

        // Collect all potential data points
        val resItems = mutableListOf<String>()
        val components = mutableListOf<String>()
        val quantities = mutableListOf<String>()
        val storageBins = mutableListOf<String>()

        // Scan through lines after header
        for (i in headerIndex + 1 until lines.size) {
            val line = lines[i].trim()

            // Identify Res Items (0001, 0002, etc.)
            if (line.matches(Regex("""^(\d{4})$|^\|?\s*(\d{4})\.?"""))) {
                val cleanLine = line.removePrefix("|").trim()
                resItems.add(cleanLine)
                LogUtils.logDebug("Found Res Item: $cleanLine at line $i")
            }
            // Identify Components (4022.678.06504, etc.)
            else if (line.matches(Regex("""^(\d{4}\.\d{3}\.\d{5})$|^\|?\s*(\d{4}\.\d{3}\.\d{5})"""))) {
                val cleanLine = line.removePrefix("|").trim()
                components.add(cleanLine)
                LogUtils.logDebug("Found Component: $cleanLine at line $i")
            }
            // Identify Quantities (1.000, 4.000, etc.)
            else if (line.matches(Regex("""^\d+\.\d{3}$"""))) {
                quantities.add(line)
                LogUtils.logDebug("Found Quantity: $line at line $i")
            }
            // Identify Storage Bins (S1-S30-B1, V1-S01-A1, etc.)
            else if (line.contains(Regex("""[A-Z]\d+-[A-Z]\d+-[A-Z]\d+"""))) {
                storageBins.add(line)
                LogUtils.logDebug("Found Storage Bin: $line at line $i")
            }
        }

        LogUtils.logDebug("Res Items found: ${resItems.size}")
        LogUtils.logDebug("Components found: ${components.size}")
        LogUtils.logDebug("Quantities found: ${quantities.size}")
        LogUtils.logDebug("Storage Bins found: ${storageBins.size}")

        // Pair components with quantities and storage bins
        for (i in resItems.indices) {
            if (i < components.size) {
                val resItem = resItems[i]
                val component = components[i]

                // Find corresponding quantity
                val reqQty = if (i < quantities.size) quantities[i] else ""

                // Find storage bin
                val storageBin = if (i < storageBins.size) storageBins[i] else ""

                // Only add valid rows
                if (resItem.matches(Regex("""^\d{4}$""")) &&
                    component.matches(Regex("""^\d{4}\.\d{3}\.\d{5}$"""))) {
                    rows.add(TableRow(
                        resItem = resItem,
                        component = component,
                        description = "",
                        reqQty = reqQty,
                        commQty = reqQty, // Comm Qty same as Req Qty in sample
                        pickQty = "",
                        uom = "",
                        cd = "",
                        storageBin = storageBin,
                        barcode = ""
                    ))
                    LogUtils.logDebug("Added row: $resItem, $component, $reqQty, $storageBin")
                }
            }
        }

        LogUtils.logDebug("Total rows parsed: ${rows.size}")
        return rows
    }

    private fun analyzeFailure(visionText: Text): String {
        val lines = visionText.text.split("\n")
        val report = StringBuilder("=== OCR ANALYSIS REPORT ===\n\n")

        report.append("Total lines: ${lines.size}\n")
        report.append("Header found: ${lines.any { it.contains("Res Item", ignoreCase = true) && it.contains("Component", ignoreCase = true)}}\n")

        val headerIndex = lines.indexOfFirst {
            it.contains("Res Item", ignoreCase = true) &&
                    it.contains("Component", ignoreCase = true)
        }
        report.append("Header location: ${if (headerIndex >= 0) "Line $headerIndex" else "Not found"}\n\n")

        if (headerIndex >= 0) {
            report.append("=== CONTEXT AROUND HEADER ===\n")
            val start = (headerIndex - 3).coerceAtLeast(0)
            val end = (headerIndex + 5).coerceAtMost(lines.size - 1)

            for (i in start..end) {
                report.append("${if (i == headerIndex) "-> " else "   "}$i: ${lines[i]}\n")
            }
        }

        report.append("\n=== PATTERN COUNTS ===\n")
        report.append("Res Items found: ${lines.count { it.matches(Regex("""^(\d{4})$""")) }}\n")
        report.append("Components found: ${lines.count { it.matches(Regex("""^(\d{4}\.\d{3}\.\d{5})$""")) }}\n")
        report.append("Quantities found: ${lines.count { it.matches(Regex("""^\d+\.\d{3}$""")) }}\n")
        report.append("Storage Bins found: ${lines.count { it.contains(Regex("""[A-Z]\d+-[A-Z]\d+-[A-Z]\d+""")) }}\n")

        return report.toString()
    }

    private fun convertToJson(tableData: List<TableRow>): String {
        val jsonArray = JSONArray()
        for (row in tableData) {
            val jsonObject = JSONObject().apply {
                put("Res Item", row.resItem)
                put("Component", row.component)
                put("Req Qty", row.reqQty)
                put("Comm Qty", row.commQty)
                put("Storage Bin", row.storageBin)
            }
            jsonArray.put(jsonObject)
        }
        return jsonArray.toString(2)
    }

    private fun displayResults(tableData: List<TableRow>) {
        if (tableData.isEmpty()) {
            resultText.append("\n\nNO TABLE DATA FOUND AFTER PARSING")
            return
        }

        val formattedJson = convertToJson(tableData)
        resultText.append("\n\nPARSED JSON:\n\n$formattedJson")
    }

    data class TableRow(
        val resItem: String,
        val component: String,
        val description: String,
        val reqQty: String,
        val commQty: String,
        val pickQty: String,
        val uom: String,
        val cd: String,
        val storageBin: String,
        val barcode: String
    )

    companion object {
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ).apply {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }
}