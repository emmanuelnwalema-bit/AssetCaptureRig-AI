package com.example.assetcapturerig

import android.content.Context
import android.graphics.*
import android.media.AudioManager
import android.media.Image
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
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.*

class MainActivity : AppCompatActivity() {

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

    private val httpClient = OkHttpClient()
    private val modalEndpoint = "https://emmanuelnwalema--mobile-6dof-capture-ui.modal.run/upload_plate"

    private val debugLog = StringBuilder("App Initialized (3D Encapsulating Ring Engine)\n")
    private var toneGen: ToneGenerator? = null

    private var currentStepIdx = 0
    private var isBoxPlaced = false
    private var isCapturing = false
    private var isBatchUploading = false
    private var lastFrame: Frame? = null
    private var prismAnchor: Anchor? = null
    private var initialAzimuth = 0f
    private var alignStartTime: Long? = null
    private var cooldownUntil: Long = 0L

    private var prismWidth = 0.10f
    private var prismDepth = 0.10f
    private var prismHeight = 0.05f

    // =========================================================
    // 3D ENCAPSULATING RING LIVE STATE
    // =========================================================
    private var hasLiveCling = false
    private var liveClingConfidence = 0
    private var liveWorldX = 0f
    private var liveWorldY = 0f
    private var liveWorldZ = 0f
    private var liveWidth = 0.10f
    private var liveDepth = 0.10f
    private var liveHeight = 0.045f
    private var liveAzimuth = 0f
    private var liveCos2A = 1f
    private var liveSin2A = 0f
    private var liveClingScreenX = 0f
    private var liveClingScreenY = 0f
    private var frameCounter = 0

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

        // Keep the status banner lifted above the center aiming zone
        (distIndicator.parent as? View)?.let { hudBanner ->
            hudBanner.post {
                val liftPx = -resources.displayMetrics.heightPixels * 0.22f
                hudBanner.translationY = liftPx
                hudBanner.alpha = 0.88f
            }
        }

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

        scaleReadout.setOnClickListener {
            initialAzimuth += Math.toRadians(15.0).toFloat()
            overlayView.postInvalidate()
            Toast.makeText(this, "Rotated +15° (Long-press for 90°)", Toast.LENGTH_SHORT).show()
        }
        valScale.setOnClickListener { showDebugDialog() }
        targetBadge.setOnClickListener { showDebugDialog() }

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
                .setTitle("3D Encapsulating Ring Diagnostics")
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
        prismWidth = (prismWidth * factor).coerceIn(0.04f, 0.50f)
        prismDepth = (prismDepth * factor).coerceIn(0.04f, 0.50f)
        prismHeight = (prismHeight * factor).coerceIn(0.02f, 0.40f)
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

    // =========================================================================
    // OUTSIDE-IN SILHOUETTE DETECTOR & 3D ENCAPSULATING RING ENGINE
    // =========================================================================
    /**
     * Captures the ENTIRE outer silhouette of the asset (never getting trapped inside
     * specular reflections or internal box graphics) by:
     * 1. Determining the 1D background stripe orientation from the outer border frame.
     * 2. Predicting the background color at every interior cell by projecting along the
     *    stripe axis to the outer border ring.
     * 3. Combining along-stripe residual + cross-stripe edge damming + outer-border flood fill
     *    so any internal hole (glare, logos, camera bars) is filled solid inside the outer boundary.
     * 4. Unprojecting the 2D silhouette at the asset's 3D mid-height plane (planeY + H/2) so the
     *    3D encapsulating ring wraps around the entire asset in perspective.
     */
    private fun updateLiveClingReticle(frame: Frame, planeHitPose: Pose) {
        val sWidth = sceneView.width.toFloat()
        val sHeight = sceneView.height.toFloat()
        if (sWidth <= 0f || sHeight <= 0f) return

        var cameraImage: Image? = null
        try {
            cameraImage = frame.acquireCameraImage()
            val imgW = cameraImage.width
            val imgH = cameraImage.height

            val yPlane = cameraImage.planes[0]
            val uPlane = cameraImage.planes[1]
            val vPlane = cameraImage.planes[2]

            val yBuf = yPlane.buffer
            val uBuf = uPlane.buffer
            val vBuf = vPlane.buffer

            val yRowStride = yPlane.rowStride
            val yPixStride = yPlane.pixelStride
            val uvRowStride = uPlane.rowStride
            val uvPixStride = uPlane.pixelStride

            val minDim = min(sWidth, sHeight)
            val roiHalf = minDim * 0.34f

            val searchCX = if (hasLiveCling) {
                (sWidth * 0.5f) * 0.72f + liveClingScreenX * 0.28f
            } else {
                sWidth * 0.5f
            }
            val searchCY = if (hasLiveCling) {
                (sHeight * 0.5f) * 0.72f + liveClingScreenY * 0.28f
            } else {
                sHeight * 0.5f
            }

            val gridN = 31
            val numGrid = gridN * gridN
            val viewPts = FloatArray(numGrid * 2)
            val imgPts = FloatArray(numGrid * 2)

            var idx = 0
            for (gy in 0 until gridN) {
                val fy = (gy.toFloat() / (gridN - 1)) * 2f - 1f
                val sy = (searchCY + fy * roiHalf).coerceIn(4f, sHeight - 5f)
                for (gx in 0 until gridN) {
                    val fx = (gx.toFloat() / (gridN - 1)) * 2f - 1f
                    val sx = (searchCX + fx * roiHalf).coerceIn(4f, sWidth - 5f)
                    viewPts[idx++] = sx
                    viewPts[idx++] = sy
                }
            }

            frame.transformCoordinates2d(
                Coordinates2d.VIEW, viewPts,
                Coordinates2d.IMAGE_PIXELS, imgPts
            )

            val cellY = IntArray(numGrid)
            val cellU = IntArray(numGrid)
            val cellV = IntArray(numGrid)

            for (g in 0 until numGrid) {
                val ix = imgPts[g * 2].toInt().coerceIn(1, imgW - 2)
                val iy = imgPts[g * 2 + 1].toInt().coerceIn(1, imgH - 2)

                val y00 = yBuf.get(iy * yRowStride + ix * yPixStride).toInt() and 0xFF
                val y10 = yBuf.get(iy * yRowStride + (ix + 1) * yPixStride).toInt() and 0xFF
                val y01 = yBuf.get((iy + 1) * yRowStride + ix * yPixStride).toInt() and 0xFF
                val y11 = yBuf.get((iy + 1) * yRowStride + (ix + 1) * yPixStride).toInt() and 0xFF
                cellY[g] = (y00 + y10 + y01 + y11) shr 2

                val uvOff = (iy / 2) * uvRowStride + (ix / 2) * uvPixStride
                cellU[g] = uBuf.get(uvOff).toInt() and 0xFF
                cellV[g] = vBuf.get(uvOff).toInt() and 0xFF
            }

            // 1. Estimate dominant 1D background stripe direction from outer border structure tensor
            var jxx = 0f; var jyy = 0f; var jxy = 0f
            for (gy in 1 until gridN - 1) {
                for (gx in 1 until gridN - 1) {
                    if (gx <= 2 || gx >= gridN - 3 || gy <= 2 || gy >= gridN - 3) {
                        val g = gy * gridN + gx
                        val dx = (cellY[g + 1] - cellY[g - 1]).toFloat()
                        val dy = (cellY[g + gridN] - cellY[g - gridN]).toFloat()
                        jxx += dx * dx
                        jyy += dy * dy
                        jxy += dx * dy
                    }
                }
            }

            // Gradient angle is perpendicular to stripes; stripe direction is gradAngle + PI/2
            val gradAngle = 0.5f * atan2(2f * jxy, jxx - jyy)
            val stripeAngle = gradAngle + (Math.PI / 2.0).toFloat()
            val sCos = cos(stripeAngle)
            val sSin = sin(stripeAngle)

            // 2. For every interior cell (gx, gy), trace along +s and -s to the outer border ring
            //    to predict the exact stripe color passing behind (gx, gy).
            val rawFg = BooleanArray(numGrid)

            for (gy in 2 until gridN - 2) {
                for (gx in 2 until gridN - 2) {
                    val g = gy * gridN + gx
                    val py = cellY[g]
                    val pu = cellU[g]
                    val pv = cellV[g]

                    // Ray-march to border in +s and -s directions
                    var tPos = 1f
                    var b1x = gx; var b1y = gy
                    while (tPos < gridN) {
                        val nx = (gx + tPos * sCos).roundToInt()
                        val ny = (gy + tPos * sSin).roundToInt()
                        if (nx <= 1 || nx >= gridN - 2 || ny <= 1 || ny >= gridN - 2) {
                            b1x = nx.coerceIn(1, gridN - 2)
                            b1y = ny.coerceIn(1, gridN - 2)
                            break
                        }
                        tPos += 1f
                    }

                    var tNeg = 1f
                    var b2x = gx; var b2y = gy
                    while (tNeg < gridN) {
                        val nx = (gx - tNeg * sCos).roundToInt()
                        val ny = (gy - tNeg * sSin).roundToInt()
                        if (nx <= 1 || nx >= gridN - 2 || ny <= 1 || ny >= gridN - 2) {
                            b2x = nx.coerceIn(1, gridN - 2)
                            b2y = ny.coerceIn(1, gridN - 2)
                            break
                        }
                        tNeg += 1f
                    }

                    // Compare (py, pu, pv) against a 3x3 patch around both border stripe endpoints
                    var minStripeDiff = Int.MAX_VALUE
                    for (oy in -1..1) {
                        for (ox in -1..1) {
                            val idx1 = (b1y + oy).coerceIn(0, gridN - 1) * gridN + (b1x + ox).coerceIn(0, gridN - 1)
                            val idx2 = (b2y + oy).coerceIn(0, gridN - 1) * gridN + (b2x + ox).coerceIn(0, gridN - 1)

                            val predY = (cellY[idx1] + cellY[idx2]) shr 1
                            val predU = (cellU[idx1] + cellU[idx2]) shr 1
                            val predV = (cellV[idx1] + cellV[idx2]) shr 1

                            val dy = py - predY
                            val du = (pu - predU) * 2
                            val dv = (pv - predV) * 2
                            val isSoftShadow = (dy in -45..-8) && (du * du + dv * dv < 140)
                            val dSq = if (isSoftShadow) 0 else (dy * dy + du * du + dv * dv)
                            if (dSq < minStripeDiff) minStripeDiff = dSq

                            // Also check single-ended border match in case object touches one side
                            val dy1 = py - cellY[idx1]
                            val du1 = (pu - cellU[idx1]) * 2
                            val dv1 = (pv - cellV[idx1]) * 2
                            val dSq1 = dy1 * dy1 + du1 * du1 + dv1 * dv1
                            if (dSq1 < minStripeDiff) minStripeDiff = dSq1
                        }
                    }

                    // Derivative ALONG the stripe direction (zero on background stripes, high on object edges)
                    val sxStep = sCos.roundToInt().coerceIn(-1, 1)
                    val syStep = sSin.roundToInt().coerceIn(-1, 1)
                    val gFwd = (gy + syStep) * gridN + (gx + sxStep)
                    val gBwd = (gy - syStep) * gridN + (gx - sxStep)
                    val alongStripeGrad = abs(cellY[gFwd] - cellY[gBwd]) +
                        (abs(cellU[gFwd] - cellU[gBwd]) + abs(cellV[gFwd] - cellV[gBwd])) * 2

                    rawFg[g] = (minStripeDiff > 320) || (alongStripeGrad > 36)
                }
            }

            // 3. Morphological Closing (3x3 Dilation) + Outer-Border Background Flood Fill
            //    so any internal patches inside the asset (glare, logos, matching stripe tone) are filled solid!
            val dilatedFg = BooleanArray(numGrid)
            for (gy in 2 until gridN - 2) {
                for (gx in 2 until gridN - 2) {
                    var countN = 0
                    for (ky in -1..1) {
                        for (kx in -1..1) {
                            if (rawFg[(gy + ky) * gridN + (gx + kx)]) countN++
                        }
                    }
                    dilatedFg[gy * gridN + gx] = countN >= 3
                }
            }

            // Flood-fill "Outside Background" starting from all perimeter cells (gx <= 1 or gy <= 1 etc.)
            val outsideBg = BooleanArray(numGrid)
            val bgQueue = ArrayDeque<Int>()
            for (gy in 0 until gridN) {
                for (gx in 0 until gridN) {
                    if (gx <= 1 || gx >= gridN - 2 || gy <= 1 || gy >= gridN - 2) {
                        val bIdx = gy * gridN + gx
                        outsideBg[bIdx] = true
                        bgQueue.add(bIdx)
                    }
                }
            }

            while (!bgQueue.isEmpty()) {
                val curr = bgQueue.removeFirst()
                val cx = curr % gridN
                val cy = curr / gridN
                val edges = intArrayOf(
                    cy * gridN + (cx - 1).coerceAtLeast(0),
                    cy * gridN + (cx + 1).coerceAtMost(gridN - 1),
                    (cy - 1).coerceAtLeast(0) * gridN + cx,
                    (cy + 1).coerceAtMost(gridN - 1) * gridN + cx
                )
                for (nIdx in edges) {
                    if (!outsideBg[nIdx] && !dilatedFg[nIdx]) {
                        outsideBg[nIdx] = true
                        bgQueue.add(nIdx)
                    }
                }
            }

            // Any cell NOT reached by the outside background flood is part of the solid closed object silhouette!
            val solidSilhouette = BooleanArray(numGrid)
            for (gy in 2 until gridN - 2) {
                for (gx in 2 until gridN - 2) {
                    var nonBgNeighbors = 0
                    for (ky in -1..1) {
                        for (kx in -1..1) {
                            if (!outsideBg[(gy + ky) * gridN + (gx + kx)]) nonBgNeighbors++
                        }
                    }
                    // Erode 1px back to undo the dilation margin and prune thin 1D noise
                    solidSilhouette[gy * gridN + gx] = nonBgNeighbors >= 5
                }
            }

            // 4. Extract the connected object component closest to the aiming center
            val centerG = gridN / 2
            var seedIdx = -1
            var bestDist = Int.MAX_VALUE
            for (gy in 3 until gridN - 3) {
                for (gx in 3 until gridN - 3) {
                    val g = gy * gridN + gx
                    if (solidSilhouette[g]) {
                        val d = (gx - centerG) * (gx - centerG) + (gy - centerG) * (gy - centerG)
                        if (d < bestDist) {
                            bestDist = d
                            seedIdx = g
                        }
                    }
                }
            }

            if (seedIdx == -1 || bestDist > 45) {
                liveClingConfidence = (liveClingConfidence - 1).coerceAtLeast(0)
                if (liveClingConfidence == 0) hasLiveCling = false
                return
            }

            val compVisited = BooleanArray(numGrid)
            val compQueue = ArrayDeque<Int>()
            compQueue.add(seedIdx)
            compVisited[seedIdx] = true

            val compX = mutableListOf<Float>()
            val compY = mutableListOf<Float>()
            var sumX = 0f
            var sumY = 0f

            while (!compQueue.isEmpty()) {
                val curr = compQueue.removeFirst()
                val cx = curr % gridN
                val cy = curr / gridN

                val sx = viewPts[curr * 2]
                val sy = viewPts[curr * 2 + 1]
                compX.add(sx)
                compY.add(sy)
                sumX += sx
                sumY += sy

                for (ny in (cy - 1)..(cy + 1)) {
                    for (nx in (cx - 1)..(cx + 1)) {
                        if (nx in 2 until (gridN - 2) && ny in 2 until (gridN - 2)) {
                            val nIdx = ny * gridN + nx
                            if (solidSilhouette[nIdx] && !compVisited[nIdx]) {
                                compVisited[nIdx] = true
                                compQueue.add(nIdx)
                            }
                        }
                    }
                }
            }

            val count = compX.size
            if (count < 12 || count > (numGrid * 0.72f).toInt()) {
                liveClingConfidence = (liveClingConfidence - 1).coerceAtLeast(0)
                if (liveClingConfidence == 0) hasLiveCling = false
                return
            }

            val barX = sumX / count
            val barY = sumY / count

            // 5. Rotating Calipers Minimum-Area Bounding Box in screen space
            var bestArea = Float.MAX_VALUE
            var bestRad = 0f
            for (deg in 0 until 90 step 3) {
                val rad = Math.toRadians(deg.toDouble()).toFloat()
                val c = cos(rad)
                val s = sin(rad)
                var minU = Float.MAX_VALUE; var maxU = -Float.MAX_VALUE
                var minV = Float.MAX_VALUE; var maxV = -Float.MAX_VALUE

                for (i in 0 until count) {
                    val dx = compX[i] - barX
                    val dy = compY[i] - barY
                    val pu = dx * c + dy * s
                    val pv = -dx * s + dy * c
                    if (pu < minU) minU = pu
                    if (pu > maxU) maxU = pu
                    if (pv < minV) minV = pv
                    if (pv > maxV) maxV = pv
                }
                val area = (maxU - minU).coerceAtLeast(12f) * (maxV - minV).coerceAtLeast(12f)
                if (area < bestArea) {
                    bestArea = area
                    bestRad = rad
                }
            }

            val uX = cos(bestRad)
            val uY = sin(bestRad)
            val vX = -uY
            val vY = uX

            var minU = Float.MAX_VALUE; var maxU = -Float.MAX_VALUE
            var minV = Float.MAX_VALUE; var maxV = -Float.MAX_VALUE
            for (i in 0 until count) {
                val dx = compX[i] - barX
                val dy = compY[i] - barY
                val pu = dx * uX + dy * uY
                val pv = dx * vX + dy * vY
                if (pu < minU) minU = pu
                if (pu > maxU) maxU = pu
                if (pv < minV) minV = pv
                if (pv > maxV) maxV = pv
            }

            val cellStepPx = (roiHalf * 2f) / (gridN - 1)
            val padPx = cellStepPx * 0.85f
            val midU = (minU + maxU) * 0.5f
            val midV = (minV + maxV) * 0.5f
            val halfSpan1Px = ((maxU - minU) * 0.5f + padPx).coerceAtLeast(18f)
            val halfSpan2Px = ((maxV - minV) * 0.5f + padPx).coerceAtLeast(18f)

            val boxScreenCX = barX + midU * uX + midV * vX
            val boxScreenCY = barY + midU * uY + midV * vY

            // 6. Unproject the 2D silhouette at the asset's 3D MID-HEIGHT plane (planeY + H/2)
            //    so the 3D encapsulating ring is centered directly over the asset in perspective!
            val vMat = FloatArray(16)
            val pMat = FloatArray(16)
            val vpMat = FloatArray(16)
            val invVpMat = FloatArray(16)

            frame.camera.getViewMatrix(vMat, 0)
            frame.camera.getProjectionMatrix(pMat, 0, 0.05f, 50.0f)
            Matrix.multiplyMM(vpMat, 0, pMat, 0, vMat, 0)
            Matrix.invertM(invVpMat, 0, vpMat, 0)

            val planeY = planeHitPose.ty()
            val estMidY = planeY + liveHeight * 0.5f

            val pCenterMid = intersectScreenRayWithHorizontalPlane(boxScreenCX, boxScreenCY, sWidth, sHeight, invVpMat, estMidY)
            val p1AMid = intersectScreenRayWithHorizontalPlane(boxScreenCX + halfSpan1Px * uX, boxScreenCY + halfSpan1Px * uY, sWidth, sHeight, invVpMat, estMidY)
            val p1BMid = intersectScreenRayWithHorizontalPlane(boxScreenCX - halfSpan1Px * uX, boxScreenCY - halfSpan1Px * uY, sWidth, sHeight, invVpMat, estMidY)
            val p2AMid = intersectScreenRayWithHorizontalPlane(boxScreenCX + halfSpan2Px * vX, boxScreenCY + halfSpan2Px * vY, sWidth, sHeight, invVpMat, estMidY)
            val p2BMid = intersectScreenRayWithHorizontalPlane(boxScreenCX - halfSpan2Px * vX, boxScreenCY - halfSpan2Px * vY, sWidth, sHeight, invVpMat, estMidY)

            if (pCenterMid == null || p1AMid == null || p1BMid == null || p2AMid == null || p2BMid == null) return

            val v1X = p1AMid[0] - p1BMid[0]
            val v1Z = p1AMid[2] - p1BMid[2]
            val span1 = sqrt(v1X * v1X + v1Z * v1Z).coerceIn(0.045f, 0.40f)
            val u1X = v1X / span1
            val u1Z = v1Z / span1

            val v2X = p2AMid[0] - p2BMid[0]
            val v2Z = p2AMid[2] - p2BMid[2]
            val span2 = sqrt(v2X * v2X + v2Z * v2Z).coerceIn(0.045f, 0.40f)
            val u2X = v2X / span2
            val u2Z = v2Z / span2

            val camPose = frame.camera.pose
            val camAngle = atan2(camPose.tx() - pCenterMid[0], camPose.tz() - pCenterMid[2])
            val camDirX = sin(camAngle)
            val camDirZ = cos(camAngle)

            val dot0 = u1X * camDirX + u1Z * camDirZ
            val dot1 = -dot0
            val dot2 = u2X * camDirX + u2Z * camDirZ
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

            val margin = 1.06f
            val targetW: Float
            val targetD: Float
            val targetHeading: Float

            when (bestIdx) {
                0 -> {
                    targetD = (span1 * margin).coerceIn(0.045f, 0.40f)
                    targetW = (span2 * margin).coerceIn(0.045f, 0.40f)
                    targetHeading = atan2(u1X, u1Z)
                }
                1 -> {
                    targetD = (span1 * margin).coerceIn(0.045f, 0.40f)
                    targetW = (span2 * margin).coerceIn(0.045f, 0.40f)
                    targetHeading = atan2(-u1X, -u1Z)
                }
                2 -> {
                    targetD = (span2 * margin).coerceIn(0.045f, 0.40f)
                    targetW = (span1 * margin).coerceIn(0.045f, 0.40f)
                    targetHeading = atan2(u2X, u2Z)
                }
                else -> {
                    targetD = (span2 * margin).coerceIn(0.045f, 0.40f)
                    targetW = (span1 * margin).coerceIn(0.045f, 0.40f)
                    targetHeading = atan2(-u2X, -u2Z)
                }
            }

            var targetH = (min(targetW, targetD) * 0.44f).coerceIn(0.032f, 0.14f)
            try {
                val pc = frame.acquirePointCloud()
                val pBuf = pc.points
                val pCount = pBuf.remaining() / 4
                var maxY = 0f
                var validPts = 0
                val radiusLimit = max(targetW, targetD) * 0.65f
                for (p in 0 until pCount) {
                    if (pBuf.get(p * 4 + 3) < 0.2f) continue
                    val px = pBuf.get(p * 4) - pCenterMid[0]
                    val py = pBuf.get(p * 4 + 1) - planeY
                    val pz = pBuf.get(p * 4 + 2) - pCenterMid[2]
                    if (py in 0.008f..0.28f && sqrt(px * px + pz * pz) <= radiusLimit) {
                        if (py > maxY) maxY = py
                        validPts++
                    }
                }
                pc.close()
                if (validPts >= 3 && maxY > 0.015f) {
                    targetH = (maxY * 1.15f).coerceIn(0.032f, 0.22f)
                }
            } catch (e: Exception) {}

            if (!hasLiveCling) {
                liveWorldX = pCenterMid[0]
                liveWorldY = planeY
                liveWorldZ = pCenterMid[2]
                liveWidth = targetW
                liveDepth = targetD
                liveHeight = targetH
                liveAzimuth = targetHeading
                liveCos2A = cos(targetHeading * 2f)
                liveSin2A = sin(targetHeading * 2f)
                liveClingScreenX = boxScreenCX
                liveClingScreenY = boxScreenCY
                hasLiveCling = true
            } else {
                val alphaPos = 0.35f
                val alphaDim = 0.25f
                liveWorldX += alphaPos * (pCenterMid[0] - liveWorldX)
                liveWorldY = planeY
                liveWorldZ += alphaPos * (pCenterMid[2] - liveWorldZ)
                liveWidth += alphaDim * (targetW - liveWidth)
                liveDepth += alphaDim * (targetD - liveDepth)
                liveHeight += alphaDim * (targetH - liveHeight)

                liveCos2A = liveCos2A * 0.75f + cos(targetHeading * 2f) * 0.25f
                liveSin2A = liveSin2A * 0.75f + sin(targetHeading * 2f) * 0.25f
                var smoothedAxis = 0.5f * atan2(liveSin2A, liveCos2A)
                if (sin(smoothedAxis) * camDirX + cos(smoothedAxis) * camDirZ < 0f) {
                    smoothedAxis += Math.PI.toFloat()
                }
                liveAzimuth = smoothedAxis

                liveClingScreenX += alphaPos * (boxScreenCX - liveClingScreenX)
                liveClingScreenY += alphaPos * (boxScreenCY - liveClingScreenY)
            }
            liveClingConfidence = (liveClingConfidence + 1).coerceAtMost(6)

        } catch (e: Exception) {
        } finally {
            cameraImage?.close()
        }
    }

    private fun placePrismAtHitTest() {
        val frame = lastFrame ?: return
        val session = sceneView.session ?: return
        val hitResults = frame.hitTest(sceneView.width / 2f, sceneView.height / 2f)
        val planeHit = hitResults.firstOrNull {
            val t = it.trackable
            t is Plane && t.trackingState == TrackingState.TRACKING && t.type == Plane.Type.HORIZONTAL_UPWARD_FACING
        }
        val firstHit = planeHit ?: hitResults.firstOrNull()

        if (firstHit != null) {
            val hitPose = firstHit.hitPose

            if (hasLiveCling) {
                val clingPose = Pose(
                    floatArrayOf(liveWorldX, liveWorldY, liveWorldZ),
                    hitPose.rotationQuaternion
                )
                prismAnchor = session.createAnchor(clingPose)
                prismWidth = liveWidth
                prismDepth = liveDepth
                prismHeight = liveHeight
                initialAzimuth = liveAzimuth

                val wCm = (prismWidth * 100).roundToInt()
                val dCm = (prismDepth * 100).roundToInt()
                val hCm = (prismHeight * 100).roundToInt()
                logDebug("✓ Locked 3D Encapsulating Ring Fit: ${wCm}x${dCm}x${hCm}cm")
            } else {
                val anchor = firstHit.createAnchor()
                prismAnchor = anchor
                val camPose = frame.camera.pose
                val anchorPose = anchor.pose
                val camInAnchor = anchorPose.inverse().transformPoint(floatArrayOf(camPose.tx(), camPose.ty(), camPose.tz()))
                initialAzimuth = atan2(camInAnchor[0], camInAnchor[2])
            }

            isBoxPlaced = true

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
        } else {
            Toast.makeText(this, "Aim reticle at table surface first", Toast.LENGTH_SHORT).show()
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

    private fun onTrackingFrame(frame: Frame) {
        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) return

        if (!isBoxPlaced) {
            val hitResults = frame.hitTest(sceneView.width / 2f, sceneView.height / 2f)
            val planeHit = hitResults.firstOrNull {
                val t = it.trackable
                t is Plane && t.trackingState == TrackingState.TRACKING && t.type == Plane.Type.HORIZONTAL_UPWARD_FACING
            }
            val hit = planeHit ?: hitResults.firstOrNull()

            if (hit != null) {
                frameCounter++
                if (frameCounter % 2 == 0) {
                    updateLiveClingReticle(frame, hit.hitPose)
                }
            } else {
                hasLiveCling = false
                liveClingConfidence = 0
            }

            runOnUiThread {
                if (hasLiveCling) {
                    val wCm = (liveWidth * 100).roundToInt()
                    val dCm = (liveDepth * 100).roundToInt()
                    val hCm = (liveHeight * 100).roundToInt()
                    distIndicator.text = "◎ 3D RING ENCAPSULATED: ${wCm}×${dCm}×${hCm}cm — TAP LOCK"
                    distIndicator.setTextColor(Color.parseColor("#3FB950"))
                    valScale.text = "RING: ${wCm}×${dCm}"
                } else if (hit != null) {
                    distIndicator.text = "SURFACE LOCKED: AIM 3D RING AT ASSET"
                    distIndicator.setTextColor(Color.parseColor("#58A6FF"))
                } else {
                    distIndicator.text = "SCANNING FOR TABLE..."
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
        val anchorPose = anchor.pose
        val camPose = camera.pose
        val step = sequence[currentStepIdx]

        val desc = getFacetDescriptor(currentStepIdx)
        val faceCenterWorld = localToWorld(desc.centerX, desc.centerY, desc.centerZ, anchorPose)
        val faceNormalWorld = normalToWorld(desc.normX, desc.normY, desc.normZ, anchorPose)

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
        val isDistanceOptimal = distCm in 20.0f..38.0f

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

            if (distCm < 20f) {
                distIndicator.text = "⚠️ TOO CLOSE (${distCm.roundToInt()}cm) — STEP BACK"
                distIndicator.setTextColor(Color.parseColor("#F85149"))
            } else if (distCm > 38f) {
                distIndicator.text = "MOVE CLOSER (${distCm.roundToInt()}cm) — AIM 25-32cm"
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
            PixelCopy.OnPixelCopyFinishedListener { copyResult ->
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

    private fun rotateByAzimuth(x: Float, y: Float, z: Float, azimuth: Float = initialAzimuth): FloatArray {
        val c = cos(azimuth)
        val s = sin(azimuth)
        return floatArrayOf(x * c + z * s, y, -x * s + z * c)
    }

    private fun localToWorld(lx: Float, ly: Float, lz: Float, anchorPose: Pose): FloatArray {
        val rotated = rotateByAzimuth(lx, ly, lz, initialAzimuth)
        return anchorPose.transformPoint(rotated)
    }

    private fun liveLocalToWorld(lx: Float, ly: Float, lz: Float): FloatArray {
        val rotated = rotateByAzimuth(lx, ly, lz, liveAzimuth)
        return floatArrayOf(liveWorldX + rotated[0], liveWorldY + rotated[1], liveWorldZ + rotated[2])
    }

    private fun normalToWorld(nx: Float, ny: Float, nz: Float, anchorPose: Pose): FloatArray {
        val rotated = rotateByAzimuth(nx, ny, nz, initialAzimuth)
        return anchorPose.rotateVector(rotated)
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
            color = Color.argb(40, 88, 166, 255)
            style = Paint.Style.FILL
        }

        private val webXrWallBandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(42, 63, 185, 80)
            style = Paint.Style.FILL
        }

        private val webXrRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#3FB950")
            style = Paint.Style.STROKE
            strokeWidth = 5.0f
        }

        private val webXrTopRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(230, 88, 235, 110)
            style = Paint.Style.STROKE
            strokeWidth = 3.8f
        }

        private val webXrInnerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(180, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = 2.2f
        }

        private val webXrRibPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(150, 63, 185, 80)
            style = Paint.Style.STROKE
            strokeWidth = 2.4f
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

            // =========================================================================
            // PRE-LOCK PHASE: 3D VOLUMETRIC ENCAPSULATING RING AROUND THE ENTIRE ASSET
            // =========================================================================
            if (!isBoxPlaced) {
                if (hasLiveCling) {
                    // Circumscribing semi-axes (0.68x width/depth with superellipse contour)
                    // so all 4 corners of rectangular boxes sit INSIDE the 3D ring!
                    val rx = (liveWidth * 0.68f).coerceIn(0.032f, 0.26f)
                    val rz = (liveDepth * 0.68f).coerceIn(0.032f, 0.26f)
                    val ringH = liveHeight.coerceIn(0.032f, 0.22f)

                    val basePts = mutableListOf<PointF?>()
                    val midPts = mutableListOf<PointF?>()
                    val topPts = mutableListOf<PointF?>()
                    val innerBasePts = mutableListOf<PointF?>()
                    val steps = 36

                    for (i in 0 until steps) {
                        val ang = (2.0 * Math.PI * i / steps)
                        val c = cos(ang)
                        val s = sin(ang)
                        // Gentle superellipse (exponent 0.82) rounds out corners while encapsulating boxes & cases
                        val lx = (rx * c.sign * abs(c).pow(0.82)).toFloat()
                        val lz = (rz * s.sign * abs(s).pow(0.82)).toFloat()

                        basePts.add(projectPoint(liveLocalToWorld(lx, 0f, lz), screenW, screenH))
                        midPts.add(projectPoint(liveLocalToWorld(lx, ringH * 0.5f, lz), screenW, screenH))
                        topPts.add(projectPoint(liveLocalToWorld(lx, ringH, lz), screenW, screenH))
                        innerBasePts.add(projectPoint(liveLocalToWorld(lx * 0.86f, 0f, lz * 0.86f), screenW, screenH))
                    }

                    if (basePts.all { it != null } && topPts.all { it != null } && midPts.all { it != null }) {
                        // 1. Translucent 3D Cylindrical Encapsulating Wall (connecting Base Ring y=0 to Top Ring y=H)
                        for (i in 0 until steps) {
                            val next = (i + 1) % steps
                            val quadPath = Path().apply {
                                moveTo(basePts[i]!!.x, basePts[i]!!.y)
                                lineTo(basePts[next]!!.x, basePts[next]!!.y)
                                lineTo(topPts[next]!!.x, topPts[next]!!.y)
                                lineTo(topPts[i]!!.x, topPts[i]!!.y)
                                close()
                            }
                            canvas.drawPath(quadPath, webXrWallBandPaint)
                        }

                        // 2. Vertical 3D Ring Ribs around the perimeter (every 20 degrees)
                        for (i in 0 until steps step 2) {
                            canvas.drawLine(
                                basePts[i]!!.x, basePts[i]!!.y,
                                topPts[i]!!.x, topPts[i]!!.y,
                                if (i % 9 == 0) webXrRingPaint else webXrRibPaint
                            )
                        }

                        // 3. Mid-Height Equatorial Track Ring (y = H/2)
                        for (i in 0 until steps step 2) {
                            val next = (i + 1) % steps
                            canvas.drawLine(midPts[i]!!.x, midPts[i]!!.y, midPts[next]!!.x, midPts[next]!!.y, webXrInnerRingPaint)
                        }

                        // 4. Base Ring on the Table (y = 0) + Inner Gauge Ring
                        webXrRingPaint.color = Color.parseColor("#3FB950")
                        for (i in 0 until steps) {
                            val next = (i + 1) % steps
                            canvas.drawLine(basePts[i]!!.x, basePts[i]!!.y, basePts[next]!!.x, basePts[next]!!.y, webXrRingPaint)
                            if (innerBasePts[i] != null && innerBasePts[next] != null && i % 2 == 0) {
                                canvas.drawLine(innerBasePts[i]!!.x, innerBasePts[i]!!.y, innerBasePts[next]!!.x, innerBasePts[next]!!.y, webXrInnerRingPaint)
                            }
                        }

                        // 5. Top Crown Ring (y = H) encapsulating the upper face of the asset
                        for (i in 0 until steps) {
                            val next = (i + 1) % steps
                            canvas.drawLine(topPts[i]!!.x, topPts[i]!!.y, topPts[next]!!.x, topPts[next]!!.y, webXrTopRingPaint)
                        }
                    }
                    return
                }

                // Default 3D ring when scanning empty table surface
                val hitResults = frame.hitTest(screenW / 2f, screenH / 2f)
                val hit = hitResults.firstOrNull()
                if (hit != null) {
                    val hp = hit.hitPose
                    val diskRadius = 0.075f
                    val ringH = 0.035f
                    val bPts = mutableListOf<PointF?>()
                    val tPts = mutableListOf<PointF?>()

                    webXrDiskPaint.color = Color.argb(35, 88, 166, 255)
                    webXrRingPaint.color = Color.parseColor("#58A6FF")

                    for (i in 0 until 32) {
                        val ang = (2.0 * Math.PI * i / 32).toFloat()
                        val wx = hp.tx() + diskRadius * cos(ang)
                        val wz = hp.tz() + diskRadius * sin(ang)
                        bPts.add(projectPoint(wx, hp.ty(), wz, screenW, screenH))
                        tPts.add(projectPoint(wx, hp.ty() + ringH, wz, screenW, screenH))
                    }

                    if (bPts.all { it != null } && tPts.all { it != null }) {
                        for (i in bPts.indices) {
                            val next = (i + 1) % bPts.size
                            canvas.drawLine(bPts[i]!!.x, bPts[i]!!.y, bPts[next]!!.x, bPts[next]!!.y, webXrRingPaint)
                            canvas.drawLine(tPts[i]!!.x, tPts[i]!!.y, tPts[next]!!.x, tPts[next]!!.y, webXrInnerRingPaint)
                            if (i % 4 == 0) {
                                canvas.drawLine(bPts[i]!!.x, bPts[i]!!.y, tPts[i]!!.x, tPts[i]!!.y, webXrInnerRingPaint)
                            }
                        }
                    }
                }
                return
            }

            val anchor = prismAnchor ?: return
            val anchorPose = anchor.pose

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
                drawFilletedWallSector(canvas, sector, baseLoop, midLoop, 0f, hMid, anchorPose, screenW, screenH, wallColor, true)
                drawFilletedCornerSector(canvas, sector, baseLoop, midLoop, 0f, hMid, anchorPose, screenW, screenH, greyColor, false)
            }

            for (sector in 0 until 4) {
                drawFilletedWallSector(canvas, sector, midLoop, topLoop, hMid, prismHeight, anchorPose, screenW, screenH, greyColor, false)

                val facetIdx = sector * 2 + 1
                val isCaptured = capturedThumbnails.containsKey(facetIdx)
                val isActiveStep = currentStepIdx == facetIdx
                val chamferColor = when {
                    isCaptured -> capturedGreen
                    isActiveStep && isAligned -> capturedGreen
                    else -> activeRed
                }
                drawFilletedCornerSector(canvas, sector, midLoop, topLoop, hMid, prismHeight, anchorPose, screenW, screenH, chamferColor, true)
            }

            val isTopCaptured = capturedThumbnails.containsKey(8)
            val isTopActiveStep = currentStepIdx == 8
            val topColor = when {
                isTopCaptured -> capturedGreen
                isTopActiveStep && isAligned -> capturedGreen
                else -> activeRed
            }
            drawTopCap(canvas, topLoop, prismHeight, anchorPose, screenW, screenH, topColor, true)

            if (currentStepIdx < sequence.size) {
                val desc = getFacetDescriptor(currentStepIdx)
                val faceCenterWorld = localToWorld(desc.centerX, desc.centerY, desc.centerZ, anchorPose)
                val faceNormalWorld = normalToWorld(desc.normX, desc.normY, desc.normZ, anchorPose)

                val camPose = camera.pose
                val camToFaceX = camPose.tx() - faceCenterWorld[0]
                val camToFaceY = camPose.ty() - faceCenterWorld[1]
                val camToFaceZ = camPose.tz() - faceCenterWorld[2]
                val facingDot = camToFaceX * faceNormalWorld[0] + camToFaceY * faceNormalWorld[1] + camToFaceZ * faceNormalWorld[2]

                if (facingDot > 0.001f) {
                    drawPlanar3DReticle(canvas, desc, anchorPose, screenW, screenH)
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
            anchorPose: Pose,
            w: Float, h: Float, color: Int, isActive: Boolean
        ) {
            val i0 = sector * 6
            val i1 = (i0 + 1) % base.size

            val p0 = projectPoint(localToWorld(base[i0][0], y0, base[i0][2], anchorPose), w, h)
            val p1 = projectPoint(localToWorld(base[i1][0], y0, base[i1][2], anchorPose), w, h)
            val p2 = projectPoint(localToWorld(top[i1][0], y1, top[i1][2], anchorPose), w, h)
            val p3 = projectPoint(localToWorld(top[i0][0], y1, top[i0][2], anchorPose), w, h)

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
            anchorPose: Pose,
            w: Float, h: Float, color: Int, isActive: Boolean
        ) {
            val startIdx = corner * 6 + 1
            perforatedMeshPaint.shader = getMeshShaderForColor(color)

            wirePaint.color = color
            wirePaint.strokeWidth = if (isActive) 3.0f else 2.0f

            for (step in 0 until 5) {
                val iA = (startIdx + step) % base.size
                val iB = (startIdx + step + 1) % base.size

                val p0 = projectPoint(localToWorld(base[iA][0], y0, base[iA][2], anchorPose), w, h)
                val p1 = projectPoint(localToWorld(base[iB][0], y0, base[iB][2], anchorPose), w, h)
                val p2 = projectPoint(localToWorld(top[iB][0], y1, top[iB][2], anchorPose), w, h)
                val p3 = projectPoint(localToWorld(top[iA][0], y1, top[iA][2], anchorPose), w, h)

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
            anchorPose: Pose,
            w: Float, h: Float, color: Int, fill: Boolean
        ) {
            val pts = top.map { projectPoint(localToWorld(it[0], y, it[2], anchorPose), w, h) }
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

        private fun drawPlanar3DReticle(canvas: Canvas, desc: FacetDesc, anchorPose: Pose, w: Float, h: Float) {
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
                ringPts.add(projectPoint(localToWorld(px, py, pz, anchorPose), w, h))
            }

            for (i in 0 until 16) {
                val next = (i + 1) % 16
                if (ringPts[i] != null && ringPts[next] != null) {
                    canvas.drawLine(ringPts[i]!!.x, ringPts[i]!!.y, ringPts[next]!!.x, ringPts[next]!!.y, reticlePaint)
                }
            }

            val ch = radius * 1.4f
            val h1 = projectPoint(localToWorld(desc.centerX - ux * ch + nx * 0.003f, desc.centerY - uy * ch + ny * 0.003f, desc.centerZ - uz * ch + nz * 0.003f, anchorPose), w, h)
            val h2 = projectPoint(localToWorld(desc.centerX + ux * ch + nx * 0.003f, desc.centerY + uy * ch + ny * 0.003f, desc.centerZ + uz * ch + nz * 0.003f, anchorPose), w, h)
            if (h1 != null && h2 != null) canvas.drawLine(h1.x, h1.y, h2.x, h2.y, reticlePaint)

            val centerPt = projectPoint(localToWorld(desc.centerX + nx * 0.003f, desc.centerY + ny * 0.003f, desc.centerZ + nz * 0.003f, anchorPose), w, h)
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
