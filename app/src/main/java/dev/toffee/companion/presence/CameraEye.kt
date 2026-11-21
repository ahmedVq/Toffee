package dev.toffee.companion.presence

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The front camera, used for one bit of information.
 *
 * Face **detection**, never recognition — the spec is explicit, and it is
 * what lets the whole feature run on-device with nothing but a boolean
 * leaving the tablet. No frame, no bitmap, no descriptor is stored, logged or
 * sent anywhere; the only thing that escapes this class is a count.
 *
 * Sampling is throttled rather than the camera being opened and closed round
 * each sample. "Camera samples every few seconds, not every frame.
 * Continuous capture would cook a 2016 SoC and the battery with it." —
 * but re-opening a camera costs the better part of a second on this hardware
 * and wears the mechanism, so instead the stream stays bound and frames that
 * arrive too soon are closed without ever being looked at. Between samples
 * the detector does nothing at all.
 */
class CameraEye(
    context: Context,
    private val owner: LifecycleOwner,
    private val everyMs: Long = SAMPLE_EVERY_MS,
    private val onFaces: (faces: Int, atMs: Long) -> Unit,
    /**
     * Called when the camera cannot be used at all.
     *
     * Found in review, and it was the worst defect in the branch: both
     * failure paths below used to end in a `Log.w` that this ROM does not
     * surface, while the caller had already been told the eye was running.
     * A tablet whose camera would not bind then greeted nobody, ever —
     * not on arrival, not on launch — and said nothing about why.
     */
    private val onUnavailable: (String) -> Unit = {},
) {

    private val app = context.applicationContext
    private var provider: ProcessCameraProvider? = null
    private var worker: ExecutorService? = null
    private var detector: FaceDetector? = null

    @Volatile private var lastSampleMs = 0L
    @Volatile private var busy = false

    fun start() {
        if (worker != null) return
        val executor = Executors.newSingleThreadExecutor()
        worker = executor

        detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                // Everything off that is not needed. No landmarks, no
                // contours, no classification and no tracking: each of those
                // is both slower and closer to identifying him than a count
                // of faces has any business being.
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                // A face at desk distance fills far more than a fifth of the
                // frame. Anything smaller is someone across the room.
                .setMinFaceSize(0.2f)
                .build(),
        )

        val future = ProcessCameraProvider.getInstance(app)
        future.addListener({
            val cameraProvider = runCatching { future.get() }.getOrNull()
            if (cameraProvider == null) {
                Log.w(TAG, "no camera provider; presence is unknown")
                onUnavailable("The camera would not start.")
                return@addListener
            }
            provider = cameraProvider

            val analysis = ImageAnalysis.Builder()
                // Small on purpose: a face big enough to sit at a desk is
                // still unmistakable at QVGA, and every pixel past that is
                // heat on a 2016 SoC.
                .setTargetResolution(Size(320, 240))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor, ::analyse)

            runCatching {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    owner,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    analysis,
                )
            }.onFailure {
                Log.w(TAG, "could not bind the front camera: $it")
                onUnavailable("I can't see out of the front camera.")
            }
        }, ContextCompat.getMainExecutor(app))
    }

    @ExperimentalGetImage
    private fun analyse(proxy: ImageProxy) {
        // elapsedRealtime, never the wall clock. BootReceiver relaunches this
        // app after a power cut with a drifted RTC; once Wi-Fi associates the
        // clock is corrected, and a forward jump made Presence announce
        // "still here, 400 minutes" to a man who had just walked up, while a
        // backward jump froze sampling for the width of the correction.
        val now = SystemClock.elapsedRealtime()
        val image = proxy.image

        // Too soon, already working on one, or a frame with no image in it.
        // Closed without being looked at, which is what makes this cheap.
        if (busy || image == null || now - lastSampleMs < everyMs) {
            proxy.close()
            return
        }

        val face = detector
        if (face == null) {
            proxy.close()
            return
        }

        busy = true
        lastSampleMs = now
        runCatching {
            face.process(InputImage.fromMediaImage(image, proxy.imageInfo.rotationDegrees))
                .addOnSuccessListener { faces -> onFaces(faces.size, now) }
                .addOnFailureListener { Log.w(TAG, "detection failed: $it") }
                .addOnCompleteListener {
                    // Both, on every path. An unclosed ImageProxy stalls the
                    // whole analysis pipeline after a handful of frames and
                    // presence silently stops updating.
                    busy = false
                    proxy.close()
                }
        }.onFailure {
            busy = false
            proxy.close()
        }
    }

    fun stop() {
        runCatching { provider?.unbindAll() }
        provider = null
        runCatching { detector?.close() }
        detector = null
        worker?.shutdown()
        worker = null
    }

    companion object {
        private const val TAG = "Toffee"

        /** Every four seconds. Fast enough that arriving is noticed while he
         *  is still sitting down, slow enough to be invisible in the heat. */
        const val SAMPLE_EVERY_MS = 4_000L
    }
}
