package com.example.assetcapturerig

import android.content.Context
import android.graphics.*
import android.media.AudioManager
import android.media.ToneGenerator
import android.opengl.Matrix
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.*

class MainActivity : AppCompatActivity() {

    // ==========================================
    // DEEPSEEK / QWEN CLOUD CONFIGURATION
    // ==========================================
    private val DASHSCOPE_API_KEY = BuildConfig.DASHSCOPE_API_KEY
    private val QWEN_ENDPOINT = "https://ap-southeast-1.maas.qwencloudapi.com/compatible-mode/v1/chat/completions"
    private val VLM_MODEL = "deepseek-v4.1-flash"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val modalEndpoint = "https://emmanuelnwalema--mobile-6dof-capture-ui.modal.run/upload_plate"

    private lateinit var rootLayout: FrameLayout
    private lateinit var sceneView: ARSceneView
    private lateinit var overlayView: PrismOverlayView
    private lateinit var snapFlash: View

    private lateinit var targetBadge: TextView
    private lateinit var chkListLeft: TextView
    private lateinit var chkListRight: TextView
    private lateinit var valDist: TextView
    private lateinit var valAlign: TextView
    private lateinit var valScale: TextView

    private lateinit var distIndicator: TextView
    private lateinit var normalIndicator: TextView
    private lateinit var centerIndicator: TextView

    private lateinit var scaleBar: LinearLayout
    private lateinit var scaleReadout: TextView
    private lateinit var btnScaleDown: Button
    private lateinit var btnScaleUp: Button
    private lateinit var forceSnapBtn: Button
    private lateinit var batchUploadBtn: Button
    private lateinit var actionButton: Button

    private lateinit var previewScrollView: HorizontalScrollView
    private lateinit var previewContainer: LinearLayout

    private lateinit var fullPreviewLayout: FrameLayout
    private lateinit var fullImageView: ImageView
    private lateinit var fullPreviewLabel: TextView
    private lateinit var closePreviewBtn: Button

    private val debugLog = StringBuilder("App Initialized (Plane-Grounded VLM Engine)\n")
    private var toneGen: ToneGenerator? = null

    private var currentStepIdx = 0
    private var isBoxPlaced = false
    private var isCapturing = false
    private var isBatchUploading = false
    private var isVlmRunning = false
    private var lastFrame: Frame? = null
    private var prismAnchor: Anchor? = null
    private var trackedTablePlane: Plane? = null
    private var initialAzimuth = 0f
    private var alignStartTime: Long? = null
    private var cooldownUntil: Long = 0L

    // Frame-locked world origin & object center offset (zero lag filter)
    private var centerOffsetX = 0f
    private var centerOffsetZ = 0f
    private var planeSurfaceOffsetY = 0f
    private var worldOriginX = 0f
    private var worldOriginY = 0f
    private var worldOriginZ = 0f

    private var prismWidth = 0.10f
    private var prismDepth = 0.10f
    private var prismHeight = 0.04f

    private val capturedThumbnails = mutableMapOf<Int, Bitmap>()
    private val previewCardViews = mutableListOf<ImageView>()

    data class SequenceStep(
        val id: String,
        val label: String,
        val hint: String,
        val targetTilt: Int,
        val targetOrbit: Int
    )

    private val sequence = listOf(
        SequenceStep("front", "1. FRONT (0° LEVEL)", "AIM LEVEL AT FRONT", 0, 0),
        SequenceStep("hero_3_4", "2. HERO SEED (45° / 30° TILT)", "ORBIT 45° & TILT 30° DOWN", 30, 45),
        SequenceStep("right", "3. RIGHT PROFILE (0° LEVEL)", "AIM LEVEL AT RIGHT", 0, 90),
        SequenceStep("back_r", "4. REAR-RIGHT (30° TILT)", "ORBIT 135° & TILT 30° DOWN", 30, 135),
        SequenceStep("back", "5. BACK VIEW (0° LEVEL)", "AIM LEVEL AT REAR", 0, 180),
        SequenceStep("back_l", "6. REAR-LEFT (30° TILT)", "ORBIT 225° & TILT 30° DOWN", 30, 225),
        SequenceStep("left", "7. LEFT PROFILE (0° LEVEL)", "AIM LEVEL AT LEFT", 0, 270),
        SequenceStep("front_l", "8. FRONT-LEFT (30° TILT)", "ORBIT 315° & TILT 30° DOWN", 30, 315),
        SequenceStep("top", "9. TOP OVERHEAD (90° DOWN)", "HOLD OVERHEAD LOOKING DOWN", 90, 0),
        SequenceStep("bottom", "10. UNDERSIDE (60° UP)", "AIM UPWARD AT ASSET BASE", -60, 0)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rootLayout = findViewById(R.id.rootLayout)
        sceneView = findViewById(R.id.arSceneView)
        snapFlash = findViewById(R.id.snapFlash)

        targetBadge = findViewById(R.id.targetBadge)
        chkListLeft = findViewById(R.id.chkListLeft)
        chkListRight = findViewById(R.id.chkListRight)
        valDist = findViewById(R.id.valDist)
        valAlign = findViewById(R.id.valAlign)
        valScale = findViewById(R.id.valScale)

        distIndicator = findViewById(R.id.distIndicator)
        normalIndicator = findViewById(R.id.normalIndicator)
        centerIndicator = findViewById(R.id.centerIndicator)

        scaleBar = findViewById(R.id.scaleBar)
        scaleReadout = findViewById(R.id.scaleReadout)
        btnScaleDown = findViewById(R.id.btnScaleDown)
        btnScaleUp = findViewById(R.id.btnScaleUp)
        forceSnapBtn = findViewById(R.id.forceSnapBtn)
        batchUploadBtn = findViewById(R.id.batchUploadBtn)
        actionButton = findViewById(R.id.actionButton)

        previewScrollView = findViewById(R.id.previewScrollView)
        previewContainer = findViewById(R.id.previewContainer)

        fullPreviewLayout = findViewById(R.id.fullPreviewLayout)
        fullImageView = findViewById(R.id.fullImageView)
        fullPreviewLabel = findViewById(R.id.fullPreviewLabel)
        closePreviewBtn = findViewById(R.id.closePreviewBtn)

        try {
            toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
        } catch (e: Exception) {}

        overlayView = PrismOverlayView(this)
        rootLayout.addView(overlayView, 1)

        buildThumbnailStrip()

        sceneView.sessionConfiguration = { _, config ->
            config.focusMode = Config.FocusMode.AUTO
            config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
            config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
        }

        sceneView.planeRenderer.isEnabled = false
        sceneView.planeRenderer.isVisible = false

        sceneView.onSessionUpdated = { _, frame ->
            lastFrame = frame
            onTrackingFrame(frame)
        }

        actionButton.setOnClickListener {
            if (!isBoxPlaced) placePrismAtHitTest()
        }

        forceSnapBtn.setOnClickListener {
            if (!isCapturing && currentStepIdx < sequence.size) {
                triggerLocalCapture()
            }
        }

        batchUploadBtn.setOnClickListener {
            if (!isBatchUploading && capturedThumbnails.isNotEmpty()) {
                startBatchUpload()
            }
        }

        closePreviewBtn.setOnClickListener {
            fullPreviewLayout.visibility = View.GONE
        }

        btnScaleUp.setOnClickListener { scalePrism(1.08f) }
        btnScaleDown.setOnClickListener { scalePrism(0.92f) }

        // Tap top badges to inspect VLM logs
        valScale.setOnClickListener { showDebugDialog() }
        targetBadge.setOnClickListener { showDebugDialog() }

        // Tap PRISM readout to nudge rotation +15°, or long-press to swap 90°
        scaleReadout.setOnClickListener {
            initialAzimuth += Math.toRadians(15.0).toFloat()
            overlayView.postInvalidate()
            Toast.makeText(this, "Rotated +15° (Long-press for 90°)", Toast.LENGTH_SHORT).show()
        }

        scaleReadout.setOnLongClickListener {
            val temp = prismWidth
            prismWidth = prismDepth
            prismDepth = temp
            initialAzimuth += (Math.PI / 2.0).toFloat()
            updateScaleLabels()
            overlayView.postInvalidate()
            Toast.makeText(this, "Rotated 90°", Toast.LENGTH_SHORT).show()
            true
        }

        updateChecklistUI()
        updateScaleLabels()
    }

    private fun logDebug(msg: String) {
        Log.d("RigDebug", msg)
        debugLog.append("[${System.currentTimeMillis() % 100000}] $msg\n")
    }

    private fun showDebugDialog() {
        runOnUiThread {
            val tv = TextView(this).apply {
                text = debugLog.toString()
                textSize = 12f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#0D1117"))
                setPadding(32, 32, 32, 32)
                setTextIsSelectable(true)
            }
            val sv = ScrollView(this).apply { addView(tv) }
            AlertDialog.Builder(this)
                .setTitle("VLM Diagnostics")
                .setView(sv)
                .setPositiveButton("OK", null)
                .setNeutralButton("Clear") { _, _ -> debugLog.clear() }
                .show()
        }
    }

    private fun buildThumbnailStrip() {
        previewContainer.removeAllViews()
        previewCardViews.clear()

        val dpWidth = (64 * resources.displayMetrics.density).toInt()
        val dpHeight = (64 * resources.displayMetrics.density).toInt()
        val dpMargin = (4 * resources.displayMetrics.density).toInt()

        for (i in sequence.indices) {
            val frame = FrameLayout(this)
            val params = LinearLayout.LayoutParams(dpWidth, dpHeight).apply {
                setMargins(dpMargin, 0, dpMargin, 0)
            }
            frame.layoutParams = params
            frame.setBackgroundColor(Color.parseColor("#21262D"))

            val iv = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }

            val badge = TextView(this).apply {
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = android.view.Gravity.BOTTOM
                }
                text = "${i + 1}"
                textSize = 9f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#B30D1117"))
                gravity = android.view.Gravity.CENTER
            }

            frame.addView(iv)
            frame.addView(badge)
            frame.setOnClickListener { showFullPreview(i) }

            previewContainer.addView(frame)
            previewCardViews.add(iv)
        }
    }

    private fun showFullPreview(idx: Int) {
        val file = File(cacheDir, "plate_${sequence[idx].id}.jpg")
        if (file.exists()) {
            val bmp = BitmapFactory.decodeFile(file.absolutePath)
            fullImageView.setImageBitmap(bmp)
            fullPreviewLabel.text = "${sequence[idx].label} (LOCAL FILE SAVED)"
            fullPreviewLayout.visibility = View.VISIBLE
        } else {
            Toast.makeText(this, "Facet ${idx + 1} not captured yet", Toast.LENGTH_SHORT).show()
        }
    }

    private fun scalePrism(factor: Float) {
        prismWidth = (prismWidth * factor).coerceIn(0.03f, 0.50f)
        prismDepth = (prismDepth * factor).coerceIn(0.03f, 0.50f)
        prismHeight = (prismHeight * factor).coerceIn(0.025f, 0.35f)
        updateScaleLabels()
        overlayView.postInvalidate()
    }

    private fun updateScaleLabels() {
        val wCm = (prismWidth * 100).roundToInt()
        val dCm = (prismDepth * 100).roundToInt()
        val hCm = (prismHeight * 100).roundToInt()
        scaleReadout.text = "PRISM: ${wCm}×${dCm}×${hCm}cm"
        valScale.text = "SCALE: ${wCm}×${dCm}"
    }

    private fun updateChecklistUI() {
        val sbL = StringBuilder()
        val sbR = StringBuilder()
        for (i in 0 until 5) {
            val icon = if (capturedThumbnails.containsKey(i)) "🟩" else if (i == currentStepIdx) "▶" else "⬜"
            sbL.append("$icon ${sequence[i].label.substring(3)}\n")
        }
        for (i in 5 until 10) {
            val icon = if (capturedThumbnails.containsKey(i)) "🟩" else if (i == currentStepIdx) "▶" else "⬜"
            sbR.append("$icon ${sequence[i].label.substring(3)}\n")
        }
        chkListLeft.text = sbL.toString().trimEnd()
        chkListRight.text = sbR.toString().trimEnd()

        val capturedCount = capturedThumbnails.size
        batchUploadBtn.text = if (capturedCount == 10) "UPLOAD ALL 10 PLATES TO MODAL" else "UPLOAD PLATES TO MODAL ($capturedCount/10)"
        batchUploadBtn.visibility = if (capturedCount > 0) View.VISIBLE else View.GONE
    }

    /**
     * Finds the true horizontal surface plane elevation so the prism never anchors
     * to a floating feature point on the top of the torch/remote.
     */
    private fun placePrismAtHitTest() {
        val frame = lastFrame ?: return
        val cx = sceneView.width / 2f
        val cy = sceneView.height / 2f
        val hitResults = frame.hitTest(cx, cy)

        // 1. Strongly prefer a tracked HORIZONTAL_UPWARD_FACING Plane hit
        val planeHit = hitResults.firstOrNull { hit ->
            val trackable = hit.trackable
            trackable is Plane &&
                trackable.trackingState == TrackingState.TRACKING &&
                trackable.type == Plane.Type.HORIZONTAL_UPWARD_FACING
        }

        val chosenHit = planeHit ?: hitResults.firstOrNull()
        if (chosenHit == null) {
            Toast.makeText(this, "Scan table surface slightly first", Toast.LENGTH_SHORT).show()
            return
        }

        val anchor = chosenHit.createAnchor()
        prismAnchor = anchor

        // 2. Determine true table surface Y even if center hit landed on a raised Point on top of the object
        var tablePlane: Plane? = (planeHit?.trackable as? Plane)
        if (tablePlane == null) {
            val allPlanes = sceneView.session?.getAllTrackables(Plane::class.java).orEmpty()
            tablePlane = allPlanes
                .filter { it.trackingState == TrackingState.TRACKING && it.type == Plane.Type.HORIZONTAL_UPWARD_FACING }
                .minByOrNull { abs(it.centerPose.ty() - chosenHit.hitPose.ty()) }
        }
        trackedTablePlane = tablePlane

        val anchorPose = anchor.pose
        val trueSurfaceY = tablePlane?.centerPose?.ty() ?: anchorPose.ty()
        // If chosenHit was a feature point on top of the object, record the vertical drop down to the table
        planeSurfaceOffsetY = (trueSurfaceY - anchorPose.ty()).coerceIn(-0.12f, 0.02f)

        centerOffsetX = 0f
        centerOffsetZ = 0f
        worldOriginX = anchorPose.tx()
        worldOriginY = anchorPose.ty() + planeSurfaceOffsetY
        worldOriginZ = anchorPose.tz()

        val camPose = frame.camera.pose
        val camAngle = atan2(camPose.tx() - worldOriginX, camPose.tz() - worldOriginZ)
        initialAzimuth = camAngle

        isBoxPlaced = true

        val vMat = FloatArray(16)
        val pMat = FloatArray(16)
        val vpMat = FloatArray(16)
        val invVpMat = FloatArray(16)

        frame.camera.getViewMatrix(vMat, 0)
        frame.camera.getProjectionMatrix(pMat, 0, 0.05f, 50.0f)
        Matrix.multiplyMM(vpMat, 0, pMat, 0, vMat, 0)
        Matrix.invertM(invVpMat, 0, vpMat, 0)

        // If the user's reticle hit a raised feature point on top of the object, project the reticle ray
        // all the way down to trueSurfaceY so the initial box base sits flat on the table under the reticle
        val pReticleOnTable = intersectScreenRayWithHorizontalPlane(
            cx, cy, sceneView.width.toFloat(), sceneView.height.toFloat(), invVpMat, worldOriginY
        )
        if (pReticleOnTable != null && planeHit == null) {
            centerOffsetX = (pReticleOnTable[0] - anchorPose.tx()).coerceIn(-0.08f, 0.08f)
            centerOffsetZ = (pReticleOnTable[2] - anchorPose.tz()).coerceIn(-0.08f, 0.08f)
            worldOriginX = anchorPose.tx() + centerOffsetX
            worldOriginZ = anchorPose.tz() + centerOffsetZ
        }

        runCloudVlmPipeline(invVpMat, anchorPose.tx(), anchorPose.tz(), worldOriginY, camAngle)

        actionButton.visibility = View.GONE
        scaleBar.visibility = View.VISIBLE
        previewScrollView.visibility = View.VISIBLE
        forceSnapBtn.visibility = View.VISIBLE
        forceSnapBtn.text = "FORCE SNAP [${sequence[currentStepIdx].id.uppercase(Locale.US)}]"
        targetBadge.text = sequence[currentStepIdx].label
        updateChecklistUI()
        updateScaleLabels()
        overlayView.postInvalidate()

        try {
            toneGen?.startTone(ToneGenerator.TONE_PROP_ACK, 140)
        } catch (e: Exception) {}
    }

    private fun runCloudVlmPipeline(
        invVpMatrix: FloatArray,
        anchorStartX: Float,
        anchorStartZ: Float,
        surfacePlaneY: Float,
        camAngle: Float
    ) {
        val sWidth = sceneView.width
        val sHeight = sceneView.height
        if (sWidth <= 0 || sHeight <= 0 || isVlmRunning) return

        isVlmRunning = true
        distIndicator.text = "AI REASONING (DeepSeek v4.1)..."
        distIndicator.setTextColor(Color.parseColor("#E3B341"))

        val bitmap = Bitmap.createBitmap(sWidth, sHeight, Bitmap.Config.ARGB_8888)
        PixelCopy.request(
            sceneView as SurfaceView,
            bitmap,
            PixelCopy.OnPixelCopyFinishedListener { copyResult: Int ->
                if (copyResult == PixelCopy.SUCCESS) {
                    Thread {
                        callDeepSeekVlm(
                            bitmap, invVpMatrix, anchorStartX, anchorStartZ,
                            surfacePlaneY, camAngle, sWidth, sHeight
                        )
                    }.start()
                } else {
                    isVlmRunning = false
                }
            },
            Handler(Looper.getMainLooper())
        )
    }

    /**
     * Queries DeepSeek-v4.1-Flash for:
     * 1. Tight 2D bounding box on a 0..1000 scale (no numeric examples in prompt to prevent echoing)
     * 2. Unambiguous top-end to bottom-end tilt direction + angle from vertical
     * 3. Real physical length, width, and vertical height above the table
     */
    private fun callDeepSeekVlm(
        bitmap: Bitmap,
        invVpMatrix: FloatArray,
        anchorStartX: Float,
        anchorStartZ: Float,
        surfacePlaneY: Float,
        camAngle: Float,
        sWidth: Int,
        sHeight: Int
    ) {
        try {
            val minDim = min(bitmap.width, bitmap.height)
            val cropX = (bitmap.width - minDim) / 2f
            val cropY = (bitmap.height - minDim) / 2f
            val squareBmp = Bitmap.createBitmap(bitmap, cropX.toInt(), cropY.toInt(), minDim, minDim)
            val vlmBmp = Bitmap.createScaledBitmap(squareBmp, 448, 448, true)

            val stream = ByteArrayOutputStream()
            vlmBmp.compress(Bitmap.CompressFormat.JPEG, 75, stream)
            val b64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)

            logDebug("Sending ${stream.size() / 1024} KB frame to $VLM_MODEL...")

            val systemPrompt = """
                /no_think
                Detect the single foreground object resting near the center of the surface (ignore the table/stool underneath).
                Return ONLY a valid JSON object with no markdown:
                {
                  "category": "<object name>",
                  "ymin": <integer 0 to 1000 for top edge of object>,
                  "xmin": <integer 0 to 1000 for left edge of object>,
                  "ymax": <integer 0 to 1000 for bottom edge of object>,
                  "xmax": <integer 0 to 1000 for right edge of object>,
                  "tilt_direction": "<choose one: vertical, horizontal, top_right_to_bottom_left, top_left_to_bottom_right>",
                  "angle_from_vertical_deg": <integer 0 to 90>,
                  "length_cm": <float physical length of longest side>,
                  "width_cm": <float physical width of shorter side>,
                  "height_cm": <float vertical thickness rising off the table>
                }
                Rules for tilt_direction of the object's longest axis on screen:
                - "vertical": longest axis runs straight up and down (angle_from_vertical_deg near 0).
                - "horizontal": longest axis runs straight left and right (angle_from_vertical_deg near 90).
                - "top_right_to_bottom_left": upper end is toward the right, lower end is toward the left (like a forward slash /).
                - "top_left_to_bottom_right": upper end is toward the left, lower end is toward the right (like a backslash \).
            """.trimIndent()

            val contentArray = JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().apply {
                        put("url", "data:image/jpeg;base64,$b64")
                    })
                })
                put(JSONObject().apply {
                    put("type", "text")
                    put("text", systemPrompt)
                })
            }

            val requestBodyJson = JSONObject().apply {
                put("model", VLM_MODEL)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", contentArray)
                    })
                })
                put("enable_thinking", false)
                put("temperature", 0.0)
                put("max_tokens", 350)
            }

            val request = Request.Builder()
                .url(QWEN_ENDPOINT)
                .addHeader("Authorization", "Bearer $DASHSCOPE_API_KEY")
                .addHeader("Content-Type", "application/json")
                .post(requestBodyJson.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val startTime = System.currentTimeMillis()
            val response = httpClient.newCall(request).execute()
            val respStr = response.body?.string().orEmpty()
            val durationMs = System.currentTimeMillis() - startTime
            response.close()

            logDebug("VLM latency: ${durationMs}ms | HTTP ${response.code}")

            val rootJson = JSONObject(respStr)
            if (rootJson.has("error")) {
                val errMsg = rootJson.getJSONObject("error").optString("message", respStr)
                logDebug("❌ API Error: $errMsg")
                showDebugDialog()
                return
            }

            val choices = rootJson.getJSONArray("choices")
            val message = choices.getJSONObject(0).getJSONObject("message")

            var rawContent = message.optString("content", "").trim()
            if (rawContent.isEmpty() || rawContent == "null") {
                rawContent = message.optString("reasoning_content", "").trim()
            }

            val startBrace = rawContent.indexOf('{')
            val endBrace = rawContent.lastIndexOf('}')
            if (startBrace == -1 || endBrace <= startBrace) {
                logDebug("❌ Unparseable output: $rawContent")
                showDebugDialog()
                return
            }
            val cleanJsonStr = rawContent.substring(startBrace, endBrace + 1)
            val resultJson = JSONObject(cleanJsonStr)

            val category = resultJson.optString("category", "object")
            val tiltDir = resultJson.optString("tilt_direction", "vertical").lowercase(Locale.US)
            val angleFromVert = resultJson.optDouble("angle_from_vertical_deg", 0.0).toFloat().coerceIn(0f, 90f)
            val vlmLengthCm = resultJson.optDouble("length_cm", 14.0).toFloat()
            val vlmWidthCm = resultJson.optDouble("width_cm", 5.5).toFloat()
            val vlmHeightCm = resultJson.optDouble("height_cm", 2.5).toFloat()

            val rYmin = resultJson.optDouble("ymin", 300.0).toFloat()
            val rXmin = resultJson.optDouble("xmin", 300.0).toFloat()
            val rYmax = resultJson.optDouble("ymax", 700.0).toFloat()
            val rXmax = resultJson.optDouble("xmax", 700.0).toFloat()

            val maxVal = max(max(rYmin, rXmin), max(rYmax, rXmax))
            val scaleDiv = when {
                maxVal > 1.5f && maxVal <= 100f -> 100f
                maxVal > 100f -> 1000f
                else -> 1f
            }

            val ymin = (min(rYmin, rYmax) / scaleDiv).coerceIn(0.04f, 0.92f)
            val ymax = (max(rYmin, rYmax) / scaleDiv).coerceIn(ymin + 0.05f, 0.96f)
            val xmin = (min(rXmin, rXmax) / scaleDiv).coerceIn(0.04f, 0.92f)
            val xmax = (max(rXmin, rXmax) / scaleDiv).coerceIn(xmin + 0.05f, 0.96f)

            // Convert unambiguous tilt_direction + angle_from_vertical_deg to screen-space radians
            // Screen coordinates: 0 deg = right (+X), 90 deg = down (+Y)
            val vlmScreenDeg = when {
                tiltDir.contains("horizontal") -> 0f
                tiltDir.contains("top_right_to_bottom_left") -> {
                    val a = if (angleFromVert in 5f..85f) angleFromVert else 40f
                    90f + a // 90..180 deg (points from top-right toward bottom-left)
                }
                tiltDir.contains("top_left_to_bottom_right") -> {
                    val a = if (angleFromVert in 5f..85f) angleFromVert else 40f
                    90f - a // 0..90 deg (points from top-left toward bottom-right)
                }
                else -> 90f // "vertical"
            }
            val vlmScreenRad = Math.toRadians(vlmScreenDeg.toDouble()).toFloat()

            // Refine angle & centroid using foreground-segmented PCA strictly inside the VLM bounding box
            val (refinedScreenCX, refinedScreenCY, refinedScreenRad) = refineWithBoundingBoxContour(
                squareBmp, xmin, ymin, xmax, ymax, cropX, cropY, minDim.toFloat(), vlmScreenRad
            )

            logDebug("✓ $category (${durationMs}ms) | Box:[${String.format(Locale.US, "%.2f", xmin)},${String.format(Locale.US, "%.2f", ymin)}..${String.format(Locale.US, "%.2f", xmax)},${String.format(Locale.US, "%.2f", ymax)}] | Dir:$tiltDir (${vlmScreenDeg.roundToInt()}°)")

            applyPlaneGroundedGeometry(
                refinedScreenCX, refinedScreenCY, refinedScreenRad,
                vlmLengthCm, vlmWidthCm, vlmHeightCm, category,
                invVpMatrix, anchorStartX, anchorStartZ, surfacePlaneY, camAngle, sWidth, sHeight
            )

        } catch (e: Exception) {
            logDebug("❌ VLM Inference Failed: ${e.javaClass.simpleName}: ${e.message}")
            showDebugDialog()
        } finally {
            isVlmRunning = false
        }
    }

    /**
     * Samples the table surface color strictly OUTSIDE the VLM bounding box border,
     * isolates the foreground object pixels INSIDE the VLM bounding box, and computes
     * the exact 2D centroid and PCA principal axis angle.
     */
    private fun refineWithBoundingBoxContour(
        squareBmp: Bitmap,
        xmin: Float, ymin: Float, xmax: Float, ymax: Float,
        cropX: Float, cropY: Float, minDim: Float,
        vlmScreenRad: Float
    ): Triple<Float, Float, Float> {
        val w = squareBmp.width
        val h = squareBmp.height

        val x0 = (xmin * w).toInt().coerceIn(6, w - 12)
        val y0 = (ymin * h).toInt().coerceIn(6, h - 12)
        val x1 = (xmax * w).toInt().coerceIn(x0 + 8, w - 6)
        val y1 = (ymax * h).toInt().coerceIn(y0 + 8, h - 6)

        // 1. Sample background wood/table color from the ring immediately outside [x0, y0, x1, y1]
        var bgR = 0L; var bgG = 0L; var bgB = 0L; var bgCount = 0
        for (x in x0..x1 step 4) {
            val pTop = squareBmp.getPixel(x, (y0 - 4).coerceAtLeast(0))
            val pBot = squareBmp.getPixel(x, (y1 + 4).coerceAtMost(h - 1))
            bgR += Color.red(pTop) + Color.red(pBot)
            bgG += Color.green(pTop) + Color.green(pBot)
            bgB += Color.blue(pTop) + Color.blue(pBot)
            bgCount += 2
        }
        for (y in y0..y1 step 4) {
            val pLeft = squareBmp.getPixel((x0 - 4).coerceAtLeast(0), y)
            val pRight = squareBmp.getPixel((x1 + 4).coerceAtMost(w - 1), y)
            bgR += Color.red(pLeft) + Color.red(pRight)
            bgG += Color.green(pLeft) + Color.green(pRight)
            bgB += Color.blue(pLeft) + Color.blue(pRight)
            bgCount += 2
        }

        val meanBgR = if (bgCount > 0) (bgR / bgCount).toInt() else 100
        val meanBgG = if (bgCount > 0) (bgG / bgCount).toInt() else 100
        val meanBgB = if (bgCount > 0) (bgB / bgCount).toInt() else 100

        // 2. Collect foreground object pixels inside the VLM bounding box
        val fgX = mutableListOf<Float>()
        val fgY = mutableListOf<Float>()
        var sumX = 0.0; var sumY = 0.0

        for (y in y0..y1 step 3) {
            for (x in x0..x1 step 3) {
                val px = squareBmp.getPixel(x, y)
                val dr = Color.red(px) - meanBgR
                val dg = Color.green(px) - meanBgG
                val db = Color.blue(px) - meanBgB
                val distSq = dr * dr + dg * dg + db * db
                if (distSq > 1100) { // ~33 RGB Euclidean distance from surrounding wood/fabric
                    fgX.add(x.toFloat())
                    fgY.add(y.toFloat())
                    sumX += x
                    sumY += y
                }
            }
        }

        val boxMidX = (x0 + x1) * 0.5f
        val boxMidY = (y0 + y1) * 0.5f

        val finalSqX: Float
        val finalSqY: Float
        var finalRad = vlmScreenRad

        if (fgX.size >= 25) {
            val meanX = (sumX / fgX.size).toFloat()
            val meanY = (sumY / fgY.size).toFloat()
            // Blend segmented centroid with VLM box midpoint
            finalSqX = meanX * 0.65f + boxMidX * 0.35f
            finalSqY = meanY * 0.65f + boxMidY * 0.35f

            var mu20 = 0.0; var mu02 = 0.0; var mu11 = 0.0
            for (i in fgX.indices) {
                val dx = fgX[i] - meanX
                val dy = fgY[i] - meanY
                mu20 += dx * dx
                mu02 += dy * dy
                mu11 += dx * dy
            }

            // Compute eigenvalue ratio to verify object is elongated before trusting PCA angle
            val trace = mu20 + mu02
            val det = mu20 * mu02 - mu11 * mu11
            val disc = sqrt(max(0.0, trace * trace * 0.25 - det))
            val lambda1 = trace * 0.5 + disc
            val lambda2 = max(1.0, trace * 0.5 - disc)

            if (lambda1 / lambda2 > 1.6) {
                var pcaRad = (0.5 * atan2(2.0 * mu11, mu20 - mu02)).toFloat()
                if (pcaRad < 0f) pcaRad += Math.PI.toFloat()

                // Check angular distance on RP1 (modulo 180 degrees) between PCA and VLM
                val diff = abs(atan2(sin((pcaRad - vlmScreenRad) * 2f), cos((pcaRad - vlmScreenRad) * 2f))) * 0.5f
                if (diff < Math.toRadians(38.0)) {
                    // PCA and VLM agree on quadrant -> use exact pixel-aligned PCA angle
                    finalRad = pcaRad
                }
            }
        } else {
            finalSqX = boxMidX
            finalSqY = boxMidY
        }

        val screenCX = cropX + (finalSqX / w.toFloat()) * minDim
        val screenCY = cropY + (finalSqY / h.toFloat()) * minDim
        return Triple(screenCX, screenCY, finalRad)
    }

    /**
     * Unprojects the object's 2D center onto the 3D mid-height plane (surfacePlaneY + height/2)
     * so the prism snaps directly onto the object, while locking the base strictly to surfacePlaneY.
     */
    private fun applyPlaneGroundedGeometry(
        screenCX: Float,
        screenCY: Float,
        screenAxisRad: Float,
        vlmLengthCm: Float,
        vlmWidthCm: Float,
        vlmHeightCm: Float,
        category: String,
        invVpMatrix: FloatArray,
        anchorStartX: Float,
        anchorStartZ: Float,
        surfacePlaneY: Float,
        camAngle: Float,
        sWidth: Int,
        sHeight: Int
    ) {
        val finalHeight = ((vlmHeightCm / 100f) * 1.15f).coerceIn(0.032f, 0.18f)
        // Intersect the 2D visual center ray at half the object's physical height above the table
        val midHeightY = surfacePlaneY + finalHeight * 0.5f

        val pCenterMid = intersectScreenRayWithHorizontalPlane(
            screenCX, screenCY, sWidth.toFloat(), sHeight.toFloat(), invVpMatrix, midHeightY
        )

        val deltaPx = 70f
        val pAxisA = intersectScreenRayWithHorizontalPlane(
            screenCX - deltaPx * cos(screenAxisRad),
            screenCY - deltaPx * sin(screenAxisRad),
            sWidth.toFloat(), sHeight.toFloat(), invVpMatrix, midHeightY
        )
        val pAxisB = intersectScreenRayWithHorizontalPlane(
            screenCX + deltaPx * cos(screenAxisRad),
            screenCY + deltaPx * sin(screenAxisRad),
            sWidth.toFloat(), sHeight.toFloat(), invVpMatrix, midHeightY
        )

        var uLongX = sin(camAngle)
        var uLongZ = cos(camAngle)

        if (pAxisA != null && pAxisB != null) {
            val dx = pAxisB[0] - pAxisA[0]
            val dz = pAxisB[2] - pAxisA[2]
            val len = sqrt(dx * dx + dz * dz)
            if (len > 1e-4f) {
                uLongX = dx / len
                uLongZ = dz / len
            }
        }

        val uShortX = -uLongZ
        val uShortZ = uLongX

        val longM = (max(vlmLengthCm, vlmWidthCm) / 100f * 1.05f).coerceIn(0.035f, 0.42f)
        val shortM = (min(vlmLengthCm, vlmWidthCm) / 100f * 1.05f).coerceIn(0.035f, 0.42f)

        val camDirX = sin(camAngle)
        val camDirZ = cos(camAngle)

        val dot0 = uLongX * camDirX + uLongZ * camDirZ
        val dot1 = -dot0
        val dot2 = uShortX * camDirX + uShortZ * camDirZ
        val dot3 = -dot2

        val dots = floatArrayOf(dot0, dot1, dot2, dot3)
        var bestIdx = 0
        var maxDot = -Float.MAX_VALUE
        for (i in 0 until 4) {
            if (dots[i] > maxDot) {
                maxDot = dots[i]
                bestIdx = i
            }
        }

        val finalWidth: Float
        val finalDepth: Float
        val headingAngle: Float

        when (bestIdx) {
            0 -> {
                finalDepth = longM
                finalWidth = shortM
                headingAngle = atan2(uLongX, uLongZ)
            }
            1 -> {
                finalDepth = longM
                finalWidth = shortM
                headingAngle = atan2(-uLongX, -uLongZ)
            }
            2 -> {
                finalDepth = shortM
                finalWidth = longM
                headingAngle = atan2(uShortX, uShortZ)
            }
            else -> {
                finalDepth = shortM
                finalWidth = longM
                headingAngle = atan2(-uShortX, -uShortZ)
            }
        }

        runOnUiThread {
            if (pCenterMid != null) {
                // Snap prism center to the VLM-detected object center (clamped within 12cm of reticle)
                centerOffsetX = (pCenterMid[0] - anchorStartX).coerceIn(-0.12f, 0.12f)
                centerOffsetZ = (pCenterMid[2] - anchorStartZ).coerceIn(-0.12f, 0.12f)
            }

            initialAzimuth = headingAngle
            prismWidth = finalWidth
            prismDepth = finalDepth
            prismHeight = finalHeight

            updateScaleLabels()
            overlayView.postInvalidate()

            val wCm = (prismWidth * 100).roundToInt()
            val dCm = (prismDepth * 100).roundToInt()
            val hCm = (prismHeight * 100).roundToInt()

            logDebug("✓ Grounded: $category (${wCm}×${dCm}×${hCm}cm) | Offset:(${(centerOffsetX * 100).roundToInt()},${(centerOffsetZ * 100).roundToInt()})cm")
            Toast.makeText(
                this@MainActivity,
                "✓ $category [${wCm}×${dCm}×${hCm}cm] Grounded",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun intersectScreenRayWithHorizontalPlane(
        screenX: Float, screenY: Float,
        screenWidth: Float, screenHeight: Float,
        invVpMatrix: FloatArray,
        planeY: Float
    ): FloatArray? {
        val ndcX = (screenX / screenWidth) * 2f - 1f
        val ndcY = 1f - (screenY / screenHeight) * 2f

        val nearVec = floatArrayOf(ndcX, ndcY, -1f, 1f)
        val farVec = floatArrayOf(ndcX, ndcY, 1f, 1f)

        val nearWorld = FloatArray(4)
        val farWorld = FloatArray(4)

        Matrix.multiplyMV(nearWorld, 0, invVpMatrix, 0, nearVec, 0)
        Matrix.multiplyMV(farWorld, 0, invVpMatrix, 0, farVec, 0)

        val wNear = nearWorld[3]
        val wFar = farWorld[3]
        if (abs(wNear) < 1e-5f || abs(wFar) < 1e-5f) return null

        val rOx = nearWorld[0] / wNear
        val rOy = nearWorld[1] / wNear
        val rOz = nearWorld[2] / wNear

        val dirX = (farWorld[0] / wFar) - rOx
        val dirY = (farWorld[1] / wFar) - rOy
        val dirZ = (farWorld[2] / wFar) - rOz
        val len = sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ)
        if (len < 1e-4f || abs(dirY / len) < 1e-4f) return null

        val dX = dirX / len
        val dY = dirY / len
        val dZ = dirZ / len

        val t = (planeY - rOy) / dY
        if (t <= 0.05f) return null

        return floatArrayOf(rOx + t * dX, planeY, rOz + t * dZ)
    }

    /**
     * Synchronizes worldOrigin directly with the current frame's anchor pose (ZERO lag filter)
     * so the prism stays glued to the physical table surface during camera movement.
     */
    private fun syncFrameLockedOrigin(anchor: Anchor) {
        val ap = anchor.pose
        worldOriginX = ap.tx() + centerOffsetX
        worldOriginZ = ap.tz() + centerOffsetZ

        val plane = trackedTablePlane
        worldOriginY = if (plane != null && plane.trackingState == TrackingState.TRACKING) {
            plane.centerPose.ty()
        } else {
            ap.ty() + planeSurfaceOffsetY
        }
    }

    private fun onTrackingFrame(frame: Frame) {
        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) return

        if (!isBoxPlaced) {
            val hitResults = frame.hitTest(sceneView.width / 2f, sceneView.height / 2f)
            val hasPlane = hitResults.any { it.trackable is Plane }
            runOnUiThread {
                if (hasPlane) {
                    distIndicator.text = "TABLE LOCKED: AIM AT ASSET & TAP LOCK"
                    distIndicator.setTextColor(Color.parseColor("#3FB950"))
                } else if (hitResults.isNotEmpty()) {
                    distIndicator.text = "SURFACE FOUND: TAP LOCK PRISM"
                    distIndicator.setTextColor(Color.parseColor("#3FB950"))
                } else {
                    distIndicator.text = "SCANNING FOR TABLE SURFACE..."
                    distIndicator.setTextColor(Color.parseColor("#E3B341"))
                }
            }
            overlayView.postInvalidate()
            return
        }

        if (currentStepIdx >= sequence.size) {
            overlayView.postInvalidate()
            return
        }

        val anchor = prismAnchor ?: return
        syncFrameLockedOrigin(anchor)

        val camPose = camera.pose
        val step = sequence[currentStepIdx]

        val desc = getFacetDescriptor(currentStepIdx)
        val faceCenterWorld = localToWorld(desc.centerX, desc.centerY, desc.centerZ)
        val faceNormalWorld = normalToWorld(desc.normX, desc.normY, desc.normZ)

        val camToFaceX = camPose.tx() - faceCenterWorld[0]
        val camToFaceY = camPose.ty() - faceCenterWorld[1]
        val camToFaceZ = camPose.tz() - faceCenterWorld[2]
        val facingDot = camToFaceX * faceNormalWorld[0] + camToFaceY * faceNormalWorld[1] + camToFaceZ * faceNormalWorld[2]
        val isFacingCamera = facingDot > 0.001f

        val toFaceVecX = faceCenterWorld[0] - camPose.tx()
        val toFaceVecY = faceCenterWorld[1] - camPose.ty()
        val toFaceVecZ = faceCenterWorld[2] - camPose.tz()
        val distMeters = sqrt(toFaceVecX * toFaceVecX + toFaceVecY * toFaceVecY + toFaceVecZ * toFaceVecZ)
        val distCm = distMeters * 100f
        val isDistanceOptimal = distCm in 18.0f..38.0f

        val camForward = floatArrayOf(-camPose.zAxis[0], -camPose.zAxis[1], -camPose.zAxis[2])
        val actualTiltDeg = Math.toDegrees(asin((-camForward[1]).coerceIn(-1.0f, 1.0f).toDouble())).roundToInt()

        val dotNormal = -(camForward[0] * faceNormalWorld[0] + camForward[1] * faceNormalWorld[1] + camForward[2] * faceNormalWorld[2])
        val normAngleErr = Math.toDegrees(acos(dotNormal.coerceIn(-1.0f, 1.0f).toDouble())).toFloat()
        val isNormalAligned = isFacingCamera && (normAngleErr <= 10.0f)

        val safeDist = if (distMeters > 0.001f) distMeters else 1.0f
        val toFaceDirX = toFaceVecX / safeDist
        val toFaceDirY = toFaceVecY / safeDist
        val toFaceDirZ = toFaceVecZ / safeDist
        val dotCenter = camForward[0] * toFaceDirX + camForward[1] * toFaceDirY + camForward[2] * toFaceDirZ
        val centAngleErr = Math.toDegrees(acos(dotCenter.coerceIn(-1.0f, 1.0f).toDouble())).toFloat()
        val isCentered = isFacingCamera && (centAngleErr <= 12.0f)

        val isReadyToCapture = isNormalAligned && isCentered && isDistanceOptimal

        runOnUiThread {
            valDist.text = "DIST: ${distCm.roundToInt()}cm"
            valAlign.text = "TILT: ${actualTiltDeg}° [${step.targetTilt}°]"

            if (distCm < 18f) {
                distIndicator.text = "⚠️ TOO CLOSE (${distCm.roundToInt()}cm) — STEP BACK"
                distIndicator.setTextColor(Color.parseColor("#F85149"))
            } else if (distCm > 38f) {
                distIndicator.text = "MOVE CLOSER (${distCm.roundToInt()}cm) — AIM 22-32cm"
                distIndicator.setTextColor(Color.parseColor("#E3B341"))
            } else {
                distIndicator.text = "✓ FOCUS DISTANCE: ${distCm.roundToInt()}cm (SHARP)"
                distIndicator.setTextColor(Color.parseColor("#3FB950"))
            }

            if (!isFacingCamera) {
                normalIndicator.text = "MOVE TO FRONT OF ${step.label}"
                normalIndicator.setTextColor(Color.parseColor("#F85149"))
                centerIndicator.text = "FACET FACING AWAY"
                centerIndicator.setTextColor(Color.parseColor("#8B949E"))
            } else {
                if (isNormalAligned) {
                    normalIndicator.text = "TILT: ${actualTiltDeg}° | ALIGN: ${String.format(Locale.US, "%.1f", normAngleErr)}° (LOCKED)"
                    normalIndicator.setTextColor(Color.parseColor("#3FB950"))
                } else {
                    normalIndicator.text = "TILT: ${actualTiltDeg}° [AIM ${step.targetTilt}°] | DEV: ${String.format(Locale.US, "%.1f", normAngleErr)}°"
                    normalIndicator.setTextColor(Color.parseColor("#F85149"))
                }

                if (isCentered) {
                    centerIndicator.text = "BULLSEYE CENTERED (OFFSET: ${String.format(Locale.US, "%.1f", centAngleErr)}°)"
                    centerIndicator.setTextColor(Color.parseColor("#3FB950"))
                } else {
                    centerIndicator.text = "AIM AT RETICLE (OFFSET: ${String.format(Locale.US, "%.1f", centAngleErr)}°)"
                    centerIndicator.setTextColor(Color.parseColor("#8B949E"))
                }
            }
        }

        val now = System.currentTimeMillis()
        if (isReadyToCapture && !isCapturing && now >= cooldownUntil) {
            overlayView.isAligned = true
            if (alignStartTime == null) {
                alignStartTime = now
            } else if (now - alignStartTime!! >= 450) {
                alignStartTime = null
                triggerLocalCapture()
            }
        } else {
            if (!isCapturing) {
                overlayView.isAligned = false
                alignStartTime = null
            }
        }

        overlayView.postInvalidate()
    }

    private fun triggerLocalCapture() {
        if (isCapturing || currentStepIdx >= sequence.size) return
        isCapturing = true

        val snappedStepIdx = currentStepIdx
        val step = sequence[snappedStepIdx]

        playSnapFeedback()

        val bitmap = Bitmap.createBitmap(sceneView.width, sceneView.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(
            sceneView as SurfaceView,
            bitmap,
            PixelCopy.OnPixelCopyFinishedListener { copyResult: Int ->
                if (copyResult == PixelCopy.SUCCESS) {
                    Thread {
                        try {
                            val file = File(cacheDir, "plate_${step.id}.jpg")
                            val fos = FileOutputStream(file)
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos)
                            fos.flush()
                            fos.close()

                            val thumb = Bitmap.createScaledBitmap(bitmap, 128, 128, false)
                            capturedThumbnails[snappedStepIdx] = thumb

                            runOnUiThread {
                                previewCardViews[snappedStepIdx].setImageBitmap(thumb)

                                currentStepIdx++
                                isCapturing = false
                                cooldownUntil = System.currentTimeMillis() + 800L
                                alignStartTime = null
                                updateChecklistUI()

                                if (currentStepIdx >= sequence.size) {
                                    targetBadge.text = "✓ ALL 10 PLATES STORED LOCALLY"
                                    targetBadge.setTextColor(Color.parseColor("#3FB950"))
                                    distIndicator.text = "CAPTURE COMPLETE"
                                    distIndicator.setTextColor(Color.parseColor("#3FB950"))
                                    normalIndicator.text = "READY FOR MODAL BATCH UPLOAD"
                                    centerIndicator.text = "TAP UPLOAD BELOW"
                                    forceSnapBtn.visibility = View.GONE
                                    scaleBar.visibility = View.GONE
                                } else {
                                    targetBadge.text = sequence[currentStepIdx].label
                                    forceSnapBtn.text = "FORCE SNAP [${sequence[currentStepIdx].id.uppercase(Locale.US)}]"
                                }
                                overlayView.postInvalidate()
                            }
                        } catch (e: Exception) {
                            Log.e("Capture", "Error saving plate locally", e)
                            isCapturing = false
                        }
                    }.start()
                } else {
                    isCapturing = false
                }
            },
            Handler(Looper.getMainLooper())
        )
    }

    private fun startBatchUpload() {
        isBatchUploading = true
        batchUploadBtn.isEnabled = false
        batchUploadBtn.text = "UPLOADING 0/${capturedThumbnails.size}..."

        Thread {
            val total = capturedThumbnails.size
            var uploaded = 0

            for (entry in capturedThumbnails.entries.sortedBy { it.key }) {
                val idx = entry.key
                val step = sequence[idx]
                val file = File(cacheDir, "plate_${step.id}.jpg")
                if (!file.exists()) continue

                val bytes = file.readBytes()
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

                val json = JSONObject().apply {
                    put("target_id", step.id)
                    put("b64", b64)
                    put("dimensions", JSONObject().apply {
                        put("w", prismWidth)
                        put("h", prismHeight)
                        put("d", prismDepth)
                    })
                }

                val body = json.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder().url(modalEndpoint).post(body).build()

                try {
                    val response = httpClient.newCall(request).execute()
                    response.close()
                    uploaded++
                    runOnUiThread {
                        batchUploadBtn.text = "UPLOADING $uploaded/$total..."
                    }
                } catch (e: Exception) {
                    Log.e("BatchUpload", "Failed on ${step.id}", e)
                }
            }

            runOnUiThread {
                isBatchUploading = false
                batchUploadBtn.isEnabled = true
                batchUploadBtn.text = "✓ ALL $uploaded PLATES ON MODAL VOLUME"
                batchUploadBtn.setBackgroundColor(Color.parseColor("#238636"))
                Toast.makeText(this, "Batch upload complete ($uploaded plates)", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun playSnapFeedback() {
        try {
            toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
        } catch (e: Exception) {}

        snapFlash.alpha = 0.5f
        snapFlash.visibility = View.VISIBLE
        snapFlash.animate()
            .alpha(0f)
            .setDuration(180)
            .withEndAction { snapFlash.visibility = View.GONE }
            .start()
    }

    data class FacetDesc(
        val centerX: Float, val centerY: Float, val centerZ: Float,
        val normX: Float, val normY: Float, val normZ: Float,
        val isChamfer: Boolean
    )

    private fun getFacetDescriptor(step: Int): FacetDesc {
        val cos30 = cos(Math.toRadians(30.0)).toFloat()
        val sin30 = sin(Math.toRadians(30.0)).toFloat()
        val sqrt2Inv = 0.7071f
        val hW = prismWidth / 2f
        val hD = prismDepth / 2f
        val hMid = prismHeight * 0.50f
        val hChamferY = hMid + (prismHeight - hMid) * 0.50f

        return when (step) {
            0 -> FacetDesc(0f, hMid * 0.5f, hD, 0f, 0f, 1f, false)
            1 -> FacetDesc(hW * 0.76f, hChamferY, hD * 0.76f, cos30 * sqrt2Inv, sin30, cos30 * sqrt2Inv, true)
            2 -> FacetDesc(hW, hMid * 0.5f, 0f, 1f, 0f, 0f, false)
            3 -> FacetDesc(hW * 0.76f, hChamferY, -hD * 0.76f, cos30 * sqrt2Inv, sin30, -cos30 * sqrt2Inv, true)
            4 -> FacetDesc(0f, hMid * 0.5f, -hD, 0f, 0f, -1f, false)
            5 -> FacetDesc(-hW * 0.76f, hChamferY, -hD * 0.76f, -cos30 * sqrt2Inv, sin30, -cos30 * sqrt2Inv, true)
            6 -> FacetDesc(-hW, hMid * 0.5f, 0f, -1f, 0f, 0f, false)
            7 -> FacetDesc(-hW * 0.76f, hChamferY, hD * 0.76f, -cos30 * sqrt2Inv, sin30, -cos30 * sqrt2Inv, true)
            8 -> FacetDesc(0f, prismHeight, 0f, 0f, 1f, 0f, false)
            else -> FacetDesc(0f, 0f, 0f, 0f, -1f, 0f, false)
        }
    }

    private fun rotateByAzimuth(x: Float, y: Float, z: Float): FloatArray {
        val c = cos(initialAzimuth)
        val s = sin(initialAzimuth)
        return floatArrayOf(x * c + z * s, y, -x * s + z * c)
    }

    private fun localToWorld(lx: Float, ly: Float, lz: Float): FloatArray {
        val rotated = rotateByAzimuth(lx, ly, lz)
        return floatArrayOf(
            worldOriginX + rotated[0],
            worldOriginY + rotated[1],
            worldOriginZ + rotated[2]
        )
    }

    private fun normalToWorld(nx: Float, ny: Float, nz: Float): FloatArray {
        return rotateByAzimuth(nx, ny, nz)
    }

    inner class PrismOverlayView(context: Context) : View(context) {

        var isAligned = false

        private val wirePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
        }

        private val perforatedMeshPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        private val reticlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f
        }

        private val fillReticlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        private val centerGuidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(120, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }

        private val webXrDiskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(45, 88, 166, 255)
            style = Paint.Style.FILL
        }

        private val webXrRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#58A6FF")
            style = Paint.Style.STROKE
            strokeWidth = 4.5f
        }

        private val viewMatrix = FloatArray(16)
        private val projMatrix = FloatArray(16)
        private val vpMatrix = FloatArray(16)

        private val redMeshShader = createPerforatedShader(Color.parseColor("#F85149"))
        private val greenMeshShader = createPerforatedShader(Color.parseColor("#3FB950"))
        private val greyMeshShader = createPerforatedShader(Color.parseColor("#8B949E"))

        private fun createPerforatedShader(baseColor: Int): BitmapShader {
            val tileSize = 24
            val bmp = Bitmap.createBitmap(tileSize, tileSize, Bitmap.Config.ARGB_8888)
            val cv = Canvas(bmp)

            val bodyPaint = Paint().apply {
                color = Color.argb(175, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
                style = Paint.Style.FILL
            }
            cv.drawRect(0f, 0f, tileSize.toFloat(), tileSize.toFloat(), bodyPaint)

            val clearHolePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            }
            val holeR = 5.2f
            cv.drawCircle(tileSize / 2f, tileSize / 2f, holeR, clearHolePaint)
            cv.drawCircle(0f, 0f, holeR, clearHolePaint)
            cv.drawCircle(tileSize.toFloat(), 0f, holeR, clearHolePaint)
            cv.drawCircle(0f, tileSize.toFloat(), holeR, clearHolePaint)
            cv.drawCircle(tileSize.toFloat(), tileSize.toFloat(), holeR, clearHolePaint)

            val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(90, 255, 255, 255)
                style = Paint.Style.STROKE
                strokeWidth = 1.0f
            }
            cv.drawCircle(tileSize / 2f, tileSize / 2f, holeR, rimPaint)
            cv.drawCircle(0f, 0f, holeR, rimPaint)
            cv.drawCircle(tileSize.toFloat(), 0f, holeR, rimPaint)
            cv.drawCircle(0f, tileSize.toFloat(), holeR, rimPaint)
            cv.drawCircle(tileSize.toFloat(), tileSize.toFloat(), holeR, rimPaint)

            return BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }

        private fun getMeshShaderForColor(color: Int): BitmapShader {
            return when (color) {
                Color.parseColor("#3FB950") -> greenMeshShader
                Color.parseColor("#8B949E") -> greyMeshShader
                else -> redMeshShader
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val frame = lastFrame ?: return
            val camera = frame.camera
            if (camera.trackingState != TrackingState.TRACKING) return

            camera.getViewMatrix(viewMatrix, 0)
            camera.getProjectionMatrix(projMatrix, 0, 0.05f, 50.0f)
            Matrix.multiplyMM(vpMatrix, 0, projMatrix, 0, viewMatrix, 0)

            val screenW = width.toFloat()
            val screenH = height.toFloat()

            if (!isBoxPlaced) {
                val hitResults = frame.hitTest(screenW / 2f, screenH / 2f)
                val planeHit = hitResults.firstOrNull {
                    val t = it.trackable
                    t is Plane && t.type == Plane.Type.HORIZONTAL_UPWARD_FACING
                }
                val hit = planeHit ?: hitResults.firstOrNull()
                if (hit != null) {
                    val hp = hit.hitPose
                    val diskRadius = 0.09f
                    val pts = mutableListOf<PointF?>()

                    for (i in 0 until 24) {
                        val ang = (2.0 * Math.PI * i / 24).toFloat()
                        val wx = hp.tx() + diskRadius * cos(ang)
                        val wy = hp.ty()
                        val wz = hp.tz() + diskRadius * sin(ang)
                        pts.add(projectPoint(wx, wy, wz, screenW, screenH))
                    }

                    if (pts.all { it != null }) {
                        val diskPath = Path().apply {
                            moveTo(pts[0]!!.x, pts[0]!!.y)
                            for (i in 1 until pts.size) lineTo(pts[i]!!.x, pts[i]!!.y)
                            close()
                        }
                        canvas.drawPath(diskPath, webXrDiskPaint)
                        for (i in pts.indices) {
                            val next = (i + 1) % pts.size
                            canvas.drawLine(pts[i]!!.x, pts[i]!!.y, pts[next]!!.x, pts[next]!!.y, webXrRingPaint)
                        }
                    }

                    val cp = projectPoint(hp.tx(), hp.ty(), hp.tz(), screenW, screenH)
                    if (cp != null) {
                        canvas.drawCircle(cp.x, cp.y, 8f, webXrRingPaint)
                    }
                }
                return
            }

            if (prismAnchor == null) return

            canvas.drawCircle(screenW / 2f, screenH / 2f, 75f, centerGuidePaint)
            canvas.drawCircle(screenW / 2f, screenH / 2f, 8f, centerGuidePaint)

            val hW = prismWidth / 2f
            val hD = prismDepth / 2f
            val r = min(hW, hD) * 0.36f
            val hMid = prismHeight * 0.50f
            val tan30 = tan(Math.toRadians(30.0)).toFloat()
            val dChamfer = min((prismHeight - hMid) * tan30, r * 0.82f)
            val rTop = max(r - dChamfer * 0.45f, 0.02f)

            val baseLoop = generateFilletedSquircleLoop(hW, hD, r)
            val midLoop = generateFilletedSquircleLoop(hW, hD, r)
            val topLoop = generateFilletedSquircleLoop(hW - dChamfer, hD - dChamfer, rTop)

            val greyColor = Color.parseColor("#8B949E")
            val activeRed = Color.parseColor("#F85149")
            val capturedGreen = Color.parseColor("#3FB950")

            for (sector in 0 until 4) {
                val facetIdx = sector * 2
                val isCaptured = capturedThumbnails.containsKey(facetIdx)
                val isActiveStep = currentStepIdx == facetIdx
                val wallColor = when {
                    isCaptured -> capturedGreen
                    isActiveStep && isAligned -> capturedGreen
                    else -> activeRed
                }
                drawFilletedWallSector(canvas, sector, baseLoop, midLoop, 0f, hMid, screenW, screenH, wallColor, true)
                drawFilletedCornerSector(canvas, sector, baseLoop, midLoop, 0f, hMid, screenW, screenH, greyColor, false)
            }

            for (sector in 0 until 4) {
                drawFilletedWallSector(canvas, sector, midLoop, topLoop, hMid, prismHeight, screenW, screenH, greyColor, false)

                val facetIdx = sector * 2 + 1
                val isCaptured = capturedThumbnails.containsKey(facetIdx)
                val isActiveStep = currentStepIdx == facetIdx
                val chamferColor = when {
                    isCaptured -> capturedGreen
                    isActiveStep && isAligned -> capturedGreen
                    else -> activeRed
                }
                drawFilletedCornerSector(canvas, sector, midLoop, topLoop, hMid, prismHeight, screenW, screenH, chamferColor, true)
            }

            val isTopCaptured = capturedThumbnails.containsKey(8)
            val isTopActiveStep = currentStepIdx == 8
            val topColor = when {
                isTopCaptured -> capturedGreen
                isTopActiveStep && isAligned -> capturedGreen
                else -> activeRed
            }
            drawTopCap(canvas, topLoop, prismHeight, screenW, screenH, topColor, true)

            if (currentStepIdx < sequence.size) {
                val desc = getFacetDescriptor(currentStepIdx)
                val faceCenterWorld = localToWorld(desc.centerX, desc.centerY, desc.centerZ)
                val faceNormalWorld = normalToWorld(desc.normX, desc.normY, desc.normZ)

                val camPose = camera.pose
                val camToFaceX = camPose.tx() - faceCenterWorld[0]
                val camToFaceY = camPose.ty() - faceCenterWorld[1]
                val camToFaceZ = camPose.tz() - faceCenterWorld[2]
                val facingDot = camToFaceX * faceNormalWorld[0] + camToFaceY * faceNormalWorld[1] + camToFaceZ * faceNormalWorld[2]

                if (facingDot > 0.001f) {
                    drawPlanar3DReticle(canvas, desc, screenW, screenH)
                }
            }
        }

        private fun generateFilletedSquircleLoop(hW: Float, hD: Float, r: Float): List<FloatArray> {
            val loop = mutableListOf<FloatArray>()
            val xS = hW - r
            val zS = hD - r

            for (quad in 0 until 4) {
                when (quad) {
                    0 -> { loop.add(floatArrayOf(-xS, 0f, hD)); loop.add(floatArrayOf(xS, 0f, hD)) }
                    1 -> { loop.add(floatArrayOf(hW, 0f, zS)); loop.add(floatArrayOf(hW, 0f, -zS)) }
                    2 -> { loop.add(floatArrayOf(xS, 0f, -hD)); loop.add(floatArrayOf(-xS, 0f, -hD)) }
                    3 -> { loop.add(floatArrayOf(-hW, 0f, -zS)); loop.add(floatArrayOf(-hW, 0f, zS)) }
                }

                val cX = if (quad == 0 || quad == 1) xS else -xS
                val cZ = if (quad == 0 || quad == 3) zS else -zS
                val startAngle = Math.toRadians((90.0 - quad * 90.0))

                for (step in 1..4) {
                    val angle = startAngle - (Math.PI / 2.0) * (step / 5.0)
                    val px = cX + r * cos(angle).toFloat()
                    val pz = cZ + r * sin(angle).toFloat()
                    loop.add(floatArrayOf(px, 0f, pz))
                }
            }
            return loop
        }

        private fun drawFilletedWallSector(
            canvas: Canvas, sector: Int,
            base: List<FloatArray>, top: List<FloatArray>,
            y0: Float, y1: Float,
            w: Float, h: Float, color: Int, isActive: Boolean
        ) {
            val i0 = sector * 6
            val i1 = (i0 + 1) % base.size

            val p0 = projectPoint(localToWorld(base[i0][0], y0, base[i0][2]), w, h)
            val p1 = projectPoint(localToWorld(base[i1][0], y0, base[i1][2]), w, h)
            val p2 = projectPoint(localToWorld(top[i1][0], y1, top[i1][2]), w, h)
            val p3 = projectPoint(localToWorld(top[i0][0], y1, top[i0][2]), w, h)

            if (p0 != null && p1 != null && p2 != null && p3 != null) {
                perforatedMeshPaint.shader = getMeshShaderForColor(color)
                val path = Path().apply {
                    moveTo(p0.x, p0.y); lineTo(p1.x, p1.y); lineTo(p2.x, p2.y); lineTo(p3.x, p3.y); close()
                }
                canvas.drawPath(path, perforatedMeshPaint)

                wirePaint.color = color
                wirePaint.strokeWidth = if (isActive) 3.5f else 2.2f
                canvas.drawLine(p0.x, p0.y, p1.x, p1.y, wirePaint)
                canvas.drawLine(p1.x, p1.y, p2.x, p2.y, wirePaint)
                canvas.drawLine(p2.x, p2.y, p3.x, p3.y, wirePaint)
                canvas.drawLine(p3.x, p3.y, p0.x, p0.y, wirePaint)
            }
        }

        private fun drawFilletedCornerSector(
            canvas: Canvas, corner: Int,
            base: List<FloatArray>, top: List<FloatArray>,
            y0: Float, y1: Float,
            w: Float, h: Float, color: Int, isActive: Boolean
        ) {
            val startIdx = corner * 6 + 1
            perforatedMeshPaint.shader = getMeshShaderForColor(color)

            wirePaint.color = color
            wirePaint.strokeWidth = if (isActive) 3.0f else 2.0f

            for (step in 0 until 5) {
                val iA = (startIdx + step) % base.size
                val iB = (startIdx + step + 1) % base.size

                val p0 = projectPoint(localToWorld(base[iA][0], y0, base[iA][2]), w, h)
                val p1 = projectPoint(localToWorld(base[iB][0], y0, base[iB][2]), w, h)
                val p2 = projectPoint(localToWorld(top[iB][0], y1, top[iB][2]), w, h)
                val p3 = projectPoint(localToWorld(top[iA][0], y1, top[iA][2]), w, h)

                if (p0 != null && p1 != null && p2 != null && p3 != null) {
                    val path = Path().apply {
                        moveTo(p0.x, p0.y); lineTo(p1.x, p1.y); lineTo(p2.x, p2.y); lineTo(p3.x, p3.y); close()
                    }
                    canvas.drawPath(path, perforatedMeshPaint)

                    canvas.drawLine(p0.x, p0.y, p1.x, p1.y, wirePaint)
                    canvas.drawLine(p2.x, p2.y, p3.x, p3.y, wirePaint)
                    if (step == 0) canvas.drawLine(p0.x, p0.y, p3.x, p3.y, wirePaint)
                    if (step == 4) canvas.drawLine(p1.x, p1.y, p2.x, p2.y, wirePaint)
                }
            }
        }

        private fun drawTopCap(
            canvas: Canvas, top: List<FloatArray>, y: Float,
            w: Float, h: Float, color: Int, fill: Boolean
        ) {
            val pts = top.map { projectPoint(localToWorld(it[0], y, it[2]), w, h) }
            if (pts.all { it != null }) {
                val isTopCaptured = capturedThumbnails.containsKey(8)
                val isTopActiveStep = currentStepIdx == 8
                val isActive = isTopCaptured || isTopActiveStep

                if (fill) {
                    perforatedMeshPaint.shader = getMeshShaderForColor(color)
                    val path = Path().apply {
                        moveTo(pts[0]!!.x, pts[0]!!.y)
                        for (i in 1 until pts.size) lineTo(pts[i]!!.x, pts[i]!!.y)
                        close()
                    }
                    canvas.drawPath(path, perforatedMeshPaint)
                }

                wirePaint.color = color
                wirePaint.strokeWidth = if (isActive) 3.5f else 2.5f
                for (i in pts.indices) {
                    val next = (i + 1) % pts.size
                    canvas.drawLine(pts[i]!!.x, pts[i]!!.y, pts[next]!!.x, pts[next]!!.y, wirePaint)
                }
            }
        }

        private fun drawPlanar3DReticle(canvas: Canvas, desc: FacetDesc, w: Float, h: Float) {
            val rColor = if (isAligned) Color.parseColor("#3FB950") else Color.parseColor("#F85149")
            reticlePaint.color = rColor
            fillReticlePaint.color = rColor

            val nx = desc.normX; val ny = desc.normY; val nz = desc.normZ
            val ux: Float; val uy: Float; val uz: Float

            if (abs(ny) > 0.95f) {
                ux = 1f; uy = 0f; uz = 0f
            } else {
                val len = sqrt(nz * nz + nx * nx)
                ux = nz / len; uy = 0f; uz = -nx / len
            }
            val vx = ny * uz - nz * uy
            val vy = nz * ux - nx * uz
            val vz = nx * uy - ny * ux

            val radius = min(prismWidth, prismDepth) * 0.20f

            val ringPts = mutableListOf<PointF?>()
            for (i in 0 until 16) {
                val angle = (2.0 * Math.PI * i / 16).toFloat()
                val px = desc.centerX + radius * (cos(angle) * ux + sin(angle) * vx) + nx * 0.003f
                val py = desc.centerY + radius * (cos(angle) * uy + sin(angle) * vy) + ny * 0.003f
                val pz = desc.centerZ + radius * (cos(angle) * uz + sin(angle) * vz) + nz * 0.003f
                ringPts.add(projectPoint(localToWorld(px, py, pz), w, h))
            }

            for (i in 0 until 16) {
                val next = (i + 1) % 16
                if (ringPts[i] != null && ringPts[next] != null) {
                    canvas.drawLine(ringPts[i]!!.x, ringPts[i]!!.y, ringPts[next]!!.x, ringPts[next]!!.y, reticlePaint)
                }
            }

            val ch = radius * 1.4f
            val h1 = projectPoint(localToWorld(desc.centerX - ux * ch + nx * 0.003f, desc.centerY - uy * ch + ny * 0.003f, desc.centerZ - uz * ch + nz * 0.003f), w, h)
            val h2 = projectPoint(localToWorld(desc.centerX + ux * ch + nx * 0.003f, desc.centerY + uy * ch + ny * 0.003f, desc.centerZ + uz * ch + nz * 0.003f), w, h)
            if (h1 != null && h2 != null) canvas.drawLine(h1.x, h1.y, h2.x, h2.y, reticlePaint)

            val centerPt = projectPoint(localToWorld(desc.centerX + nx * 0.003f, desc.centerY + ny * 0.003f, desc.centerZ + nz * 0.003f), w, h)
            if (centerPt != null) canvas.drawCircle(centerPt.x, centerPt.y, 14f, fillReticlePaint)
        }

        private fun projectPoint(x: Float, y: Float, z: Float, screenW: Float, screenH: Float): PointF? {
            return projectPoint(floatArrayOf(x, y, z), screenW, screenH)
        }

        private fun projectPoint(worldCoords: FloatArray, screenW: Float, screenH: Float): PointF? {
            val clipVec = FloatArray(4)
            Matrix.multiplyMV(clipVec, 0, vpMatrix, 0, floatArrayOf(worldCoords[0], worldCoords[1], worldCoords[2], 1.0f), 0)
            val w = clipVec[3]
            if (w <= 0.01f) return null
            val ndcX = clipVec[0] / w
            val ndcY = clipVec[1] / w
            val sx = (ndcX * 0.5f + 0.5f) * screenW
            val sy = (-ndcY * 0.5f + 0.5f) * screenH
            return PointF(sx, sy)
        }
    }
}
