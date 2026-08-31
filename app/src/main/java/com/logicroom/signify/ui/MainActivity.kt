package com.logicroom.signify.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.logicroom.signify.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    // =========================================================
    // Main Components
    // =========================================================

    private lateinit var cameraManager: CameraManager
    private lateinit var landmarkProcessor: LandmarkProcessor
    private lateinit var signRecognizer: SignRecognizer

    private lateinit var landmarkOverlay: LandmarkOverlayView
    private lateinit var previewView: PreviewView

    private lateinit var recognizedLabelText: TextView
    private lateinit var recognizedSentenceText: TextView


    // =========================================================
    // Gesture Frame Structure
    // =========================================================

    /*
     * එක් එක් frame එකට:
     *
     * timestamp
     * +
     * 225 landmarks
     *
     * save කරනවා.
     */
    private data class TimedFrame(
        val timestampMs: Long,
        val landmarks: FloatArray
    )


    // =========================================================
    // Buffers
    // =========================================================

    /*
     * Current gesture එකේ සියලු frames
     */
    private val gestureBuffer =
        ArrayList<TimedFrame>()


    /*
     * Sign එක පටන් ගන්න කලින් frames කිහිපයක්
     * temporary තියාගන්නවා.
     *
     * එතකොට sign එකේ beginning එක miss වෙන්නේ නෑ.
     */
    private val preRoll =
        ArrayDeque<TimedFrame>()


    // =========================================================
    // Recognition State
    // =========================================================

    private enum class State {

        /*
         * Next sign එක බලාගෙන ඉන්නවා
         */
        IDLE,

        /*
         * Current sign එක collect කරනවා
         */
        COLLECTING,

        /*
         * Result එක screen එකේ display කරනවා.
         *
         * මේ කාලයේ new prediction කරන්නෙ නෑ.
         */
        DISPLAYING
    }


    @Volatile
    private var state =
        State.IDLE


    // =========================================================
    // Previous frame information
    // =========================================================

    private var previousLandmarks: FloatArray? =
        null

    private var previousHasHand =
        false


    // =========================================================
    // Gesture timing
    // =========================================================

    private var gestureStartMs =
        0L

    private var lastMotionMs =
        0L

    private var noHandFrames =
        0


    // =========================================================
    // UI timing
    // =========================================================

    private var lastUiUpdateMs =
        0L


    // =========================================================
    // Coroutine Scope
    // =========================================================

    private val scope =
        CoroutineScope(
            Dispatchers.Main +
                    SupervisorJob()
        )


    // =========================================================
    // Configuration
    // =========================================================

    companion object {

        /*
         * Gesture පටන් ගන්න කලින්
         * save කරන frames ගණන.
         */
        private const val PRE_ROLL_FRAMES =
            3


        /*
         * Gesture එකකට අවශ්‍ය minimum frames.
         *
         * Fast sign එකක් වුණත් detect කරන්න
         * වැඩිපුර frames require කරන්නේ නෑ.
         */
        private const val MIN_GESTURE_FRAMES =
            5


        /*
         * Sign එකක් කියලා consider කරන්න
         * අවම duration.
         *
         * 120ms = 0.12 seconds
         */
        private const val MIN_GESTURE_MS =
            120L


        /*
         * Hand එක තියෙනවා,
         * නමුත් movement එක නතර වෙලා
         * 350ms ගියාම sign එක ඉවරයි
         * කියලා consider කරනවා.
         */
        private const val STILL_END_MS =
            350L


        /*
         * Hand detect නොවන frames 3ක්
         * consecutive ආවොත් sign end.
         */
        private const val NO_HAND_END_FRAMES =
            3


        /*
         * එක sign එකක් maximum
         * තත්පර 3ක් collect කරනවා.
         */
        private const val MAX_GESTURE_MS =
            3000L


        /*
         * Frame buffer එක uncontrolled
         * විදිහට ලොකු වෙන එක නවත්වනවා.
         */
        private const val MAX_GESTURE_FRAMES =
            120


        /*
         * Sign START detect කරන
         * movement threshold.
         */
        private const val START_MOTION_THRESHOLD =
            0.004f


        /*
         * Gesture එක active කියලා
         * consider කරන movement threshold.
         */
        private const val ACTIVE_MOTION_THRESHOLD =
            0.0018f


        /*
         * Gesture movement එක නවතිලා පසුවත්
         * final hand pose එකේ frames ටිකක්
         * තියාගන්නවා.
         */
        private const val END_TAIL_MS =
            120L


        /*
         * =====================================================
         * RESULT DISPLAY TIME
         * =====================================================
         *
         * 2500ms = 2.5 seconds
         *
         * 2 seconds ඕන නම්:
         * 2000L
         *
         * 3 seconds ඕන නම්:
         * 3000L
         */
        private const val RESULT_DISPLAY_MS =
            2500L
    }


    // =========================================================
    // Result Clear Runnable
    // =========================================================

    /*
     * Prediction result එක තත්පර 2.5කට පස්සේ
     * clear කරන function එක.
     */
    private val clearResultRunnable =
        Runnable {

            clearResultAndResume()
        }


    // =========================================================
    // Camera Permission
    // =========================================================

    private val requestPermission =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->

            if (granted) {

                startCameraAndProcessing()

            } else {

                recognizedSentenceText.text =
                    "—"

                recognizedLabelText.text =
                    "කැමරා අවසරය අවශ්‍යයි"

                recognizedLabelText.setTextColor(
                    Color.parseColor("#C62828")
                )
            }
        }


    // =========================================================
    // onCreate
    // =========================================================

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        setContentView(
            R.layout.activity_main
        )


        // -----------------------------------------------------
        // UI
        // -----------------------------------------------------

        previewView =
            findViewById(
                R.id.cameraPreview
            )

        landmarkOverlay =
            findViewById(
                R.id.landmarkOverlay
            )

        recognizedLabelText =
            findViewById(
                R.id.recognizedLabelText
            )

        recognizedSentenceText =
            findViewById(
                R.id.recognizedSentenceText
            )


        // -----------------------------------------------------
        // Scanning animation
        // -----------------------------------------------------

        findViewById<View>(
            R.id.scanningBadge
        )?.startAnimation(

            AnimationUtils.loadAnimation(
                this,
                R.anim.blink
            )
        )


        setupSettingsButton()

        applySystemBarPadding()


        // -----------------------------------------------------
        // MediaPipe
        // -----------------------------------------------------

        try {

            landmarkProcessor =
                LandmarkProcessor(this)

        } catch (e: Exception) {

            e.printStackTrace()

            recognizedSentenceText.text =
                "—"

            recognizedLabelText.text =
                "MediaPipe Error"

            recognizedLabelText.setTextColor(
                Color.RED
            )

            return
        }


        // -----------------------------------------------------
        // TFLite Model
        // -----------------------------------------------------

        try {

            signRecognizer =
                SignRecognizer(this)

        } catch (e: Exception) {

            e.printStackTrace()

            recognizedSentenceText.text =
                "—"

            recognizedLabelText.text =
                e.message
                    ?: "Model configuration error"

            recognizedLabelText.setTextColor(
                Color.RED
            )

            return
        }


        // -----------------------------------------------------
        // Camera Permission
        // -----------------------------------------------------

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) ==
            PackageManager.PERMISSION_GRANTED
        ) {

            startCameraAndProcessing()

        } else {

            requestPermission.launch(
                Manifest.permission.CAMERA
            )
        }
    }


    // =========================================================
    // Start Camera
    // =========================================================

    private fun startCameraAndProcessing() {

        cameraManager =
            CameraManager(

                context = this,

                lifecycleOwner = this,

                previewView = previewView

            ) { bitmap ->

                try {

                    // -----------------------------------------
                    // MediaPipe landmark extraction
                    // -----------------------------------------

                    val landmarks =
                        landmarkProcessor
                            .extractLandmarks(
                                bitmap
                            )


                    val now =
                        SystemClock.uptimeMillis()


                    // -----------------------------------------
                    // Landmark Overlay
                    // -----------------------------------------

                    scope.launch {

                        landmarkOverlay
                            .updateLandmarks(

                                landmarks,

                                bitmap.width,

                                bitmap.height
                            )
                    }


                    // -----------------------------------------
                    // Gesture recognition logic
                    // -----------------------------------------

                    processLandmarkFrame(
                        landmarks,
                        now
                    )

                } catch (e: Exception) {

                    e.printStackTrace()
                }
            }


        cameraManager.startCamera()
    }


    // =========================================================
    // Process Each Frame
    // =========================================================

    private fun processLandmarkFrame(
        landmarks: FloatArray,
        now: Long
    ) {

        /*
         * Hand landmarks count කරනවා.
         */
        val detectedHandPoints =
            countDetectedHandPoints(
                landmarks
            )


        /*
         * අඩුම ගානේ hand points 6ක්
         * detect වුණොත් hand exists.
         */
        val hasHand =
            detectedHandPoints >= 6


        /*
         * Previous frame එකත් current frame එකත් අතර
         * hand movement calculate කරනවා.
         */
        val motion =
            calculateHandMotion(
                previousLandmarks,
                landmarks
            )


        /*
         * Current frame copy එක.
         */
        val frame =
            TimedFrame(

                timestampMs = now,

                landmarks =
                    landmarks.copyOf()
            )


        // =====================================================
        // STATE MACHINE
        // =====================================================

        when (state) {

            // =================================================
            // IDLE
            // =================================================

            State.IDLE -> {

                if (hasHand) {

                    /*
                     * Beginning miss නොවෙන්න
                     * previous frames ටික save.
                     */
                    pushPreRoll(
                        frame
                    )


                    /*
                     * Hand එක තිබ්බා කියලාම
                     * sign start කරන්නේ නෑ.
                     *
                     * Actual movement detect වුණාම
                     * sign collect කරන්න පටන් ගන්නවා.
                     */
                    if (
                        previousHasHand &&
                        motion >=
                        START_MOTION_THRESHOLD
                    ) {

                        startGesture(
                            now
                        )

                    } else {

                        updateStatus(
                            now = now,
                            message =
                                "✋ සංඥාව ආරම්භ කරන්න",
                            color =
                                "#757575"
                        )
                    }

                } else {

                    preRoll.clear()

                    updateStatus(
                        now = now,
                        message =
                            "අත කැමරාවට පෙන්වන්න",
                        color =
                            "#757575"
                    )
                }
            }


            // =================================================
            // COLLECTING
            // =================================================

            State.COLLECTING -> {

                if (hasHand) {

                    /*
                     * Current frame gesture එකට add.
                     */
                    gestureBuffer.add(
                        frame
                    )

                    noHandFrames =
                        0


                    /*
                     * Movement එක තවම තියෙනවා නම්
                     * last motion time update.
                     */
                    if (
                        motion >=
                        ACTIVE_MOTION_THRESHOLD
                    ) {

                        lastMotionMs =
                            now
                    }

                } else {

                    /*
                     * Hand එක frame එකේ නැහැ.
                     */
                    noHandFrames++
                }


                val elapsed =
                    now -
                            gestureStartMs


                // ---------------------------------------------
                // UI Status
                // ---------------------------------------------

                updateStatus(

                    now = now,

                    message =
                        "✋ සංඥාව හඳුනාගනිමින්...",

                    color =
                        "#1565C0"
                )


                // ---------------------------------------------
                // Enough data?
                // ---------------------------------------------

                val enoughData =

                    gestureBuffer.size >=
                            MIN_GESTURE_FRAMES &&

                            elapsed >=
                            MIN_GESTURE_MS


                // ---------------------------------------------
                // Hand disappeared?
                // ---------------------------------------------

                val handGone =

                    noHandFrames >=
                            NO_HAND_END_FRAMES


                // ---------------------------------------------
                // Hand still visible but movement stopped?
                // ---------------------------------------------

                val movementStopped =

                    hasHand &&

                            now -
                            lastMotionMs >=
                            STILL_END_MS


                // ---------------------------------------------
                // Safety maximum
                // ---------------------------------------------

                val maximumReached =

                    elapsed >=
                            MAX_GESTURE_MS ||

                            gestureBuffer.size >=
                            MAX_GESTURE_FRAMES


                // ---------------------------------------------
                // Gesture End
                // ---------------------------------------------

                if (

                    maximumReached ||

                    (
                            enoughData &&

                                    (
                                            handGone ||
                                                    movementStopped
                                            )
                            )
                ) {

                    finishGesture(
                        now
                    )
                }
            }


            // =================================================
            // DISPLAYING
            // =================================================

            State.DISPLAYING -> {

                /*
                 * මේ state එකේ:
                 *
                 * Camera + landmarks continue වෙනවා.
                 *
                 * නමුත්:
                 *
                 * ❌ new gesture collect කරන්නේ නෑ
                 * ❌ prediction කරන්නේ නෑ
                 *
                 * Result එක screen එකේ
                 * තත්පර 2.5ක් display වෙනවා.
                 *
                 * clearResultRunnable එක පසුව
                 * State.IDLE කරනවා.
                 */
            }
        }


        /*
         * DISPLAYING state එකේදී previous movement
         * tracking අවශ්‍ය නැහැ.
         */
        if (state != State.DISPLAYING) {

            previousLandmarks =
                landmarks.copyOf()

            previousHasHand =
                hasHand
        }
    }


    // =========================================================
    // Start Gesture
    // =========================================================

    private fun startGesture(
        now: Long
    ) {

        state =
            State.COLLECTING


        gestureBuffer.clear()


        /*
         * Pre-roll frames current gesture එකට add.
         */
        for (savedFrame in preRoll) {

            gestureBuffer.add(
                savedFrame
            )
        }


        gestureStartMs =

            gestureBuffer
                .firstOrNull()
                ?.timestampMs

                ?: now


        lastMotionMs =
            now


        noHandFrames =
            0


        updateStatus(

            now = now,

            message =
                "✋ සංඥාව හඳුනාගනිමින්...",

            color =
                "#1565C0",

            force =
                true
        )
    }


    // =========================================================
    // Finish Gesture + Predict
    // =========================================================

    private fun finishGesture(
        now: Long
    ) {

        /*
         * ඉතාම වැදගත්:
         *
         * Prediction එක ලැබුණාම immediately
         * DISPLAYING state එකට යනවා.
         *
         * ඒ නිසා same sign එක නැවත නැවත
         * predict වෙන්නේ නැහැ.
         */
        state =
            State.DISPLAYING


        // -----------------------------------------------------
        // Keep relevant gesture frames
        // -----------------------------------------------------

        val trimmedFrames =
            gestureBuffer.filter {

                it.timestampMs <=
                        lastMotionMs +
                        END_TAIL_MS
            }


        val finalFrames =

            if (
                trimmedFrames.size >=
                MIN_GESTURE_FRAMES
            ) {

                trimmedFrames

            } else {

                gestureBuffer.toList()
            }


        // -----------------------------------------------------
        // Clear recording buffers
        // -----------------------------------------------------

        gestureBuffer.clear()

        preRoll.clear()


        // -----------------------------------------------------
        // Not enough frames
        // -----------------------------------------------------

        if (
            finalFrames.size <
            MIN_GESTURE_FRAMES
        ) {

            scope.launch {

                recognizedSentenceText.text =
                    "—"


                recognizedLabelText.text =
                    "සංඥාව නැවත පැහැදිලිව කරන්න"


                recognizedLabelText.setTextColor(

                    Color.parseColor(
                        "#757575"
                    )
                )


                scheduleResultClear()
            }

            return
        }


        // -----------------------------------------------------
        // Create variable-length sequence
        // -----------------------------------------------------

        val sequence =

            finalFrames
                .map {
                    it.landmarks
                }
                .toTypedArray()


        // -----------------------------------------------------
        // MODEL PREDICTION
        // -----------------------------------------------------

        try {

            /*
             * SignRecognizer:
             *
             * variable frames
             *
             *      ↓
             *
             * Linear Interpolation
             *
             *      ↓
             *
             * 32 × 225
             *
             *      ↓
             *
             * signify.tflite
             */
            val result =

                signRecognizer.predict(
                    sequence
                )


            // -------------------------------------------------
            // Display Result
            // -------------------------------------------------

            scope.launch {

                if (
                    result.isRecognized
                ) {

                    /*
                     * =========================================
                     * RECOGNIZED
                     * =========================================
                     */


                    /*
                     * Sinhala word
                     */
                    recognizedSentenceText.text =
                        result.sinhalaWord


                    /*
                     * English class + confidence
                     */
                    recognizedLabelText.text =

                        "✅ ${result.englishLabel} — " +
                                "${(result.confidence * 100).toInt()}%"


                    recognizedLabelText.setTextColor(

                        Color.parseColor(
                            "#2E7D32"
                        )
                    )


                } else {

                    /*
                     * =========================================
                     * NOT RECOGNIZED
                     * =========================================
                     */

                    recognizedSentenceText.text =
                        "—"


                    recognizedLabelText.text =

                        "❌ හඳුනාගත නොහැකිය — " +
                                "${(result.confidence * 100).toInt()}%"


                    recognizedLabelText.setTextColor(

                        Color.parseColor(
                            "#C62828"
                        )
                    )
                }


                /*
                 * =============================================
                 * IMPORTANT
                 * =============================================
                 *
                 * Result එක තත්පර 2.5ක් screen එකේ
                 * තියලා automatically clear කරනවා.
                 */
                scheduleResultClear()
            }


        } catch (e: Exception) {

            e.printStackTrace()


            scope.launch {

                recognizedSentenceText.text =
                    "—"


                recognizedLabelText.text =
                    "Prediction Error"


                recognizedLabelText.setTextColor(
                    Color.RED
                )


                scheduleResultClear()
            }
        }
    }


    // =========================================================
    // Schedule Result Clear
    // =========================================================

    private fun scheduleResultClear() {

        /*
         * කලින් clear timer එකක් තිබුණොත් remove.
         */
        recognizedSentenceText
            .removeCallbacks(
                clearResultRunnable
            )


        /*
         * RESULT_DISPLAY_MS පසුව clear.
         *
         * Default:
         * 2500ms = 2.5 seconds
         */
        recognizedSentenceText
            .postDelayed(

                clearResultRunnable,

                RESULT_DISPLAY_MS
            )
    }


    // =========================================================
    // Clear Result + Ready for Next Sign
    // =========================================================

    private fun clearResultAndResume() {

        /*
         * =============================================
         * Clear previous result
         * =============================================
         */

        recognizedSentenceText.text =
            "—"


        recognizedLabelText.text =
            "අත කැමරාවට පෙන්වා සංඥාව කරන්න"


        recognizedLabelText.setTextColor(

            Color.parseColor(
                "#757575"
            )
        )


        /*
         * =============================================
         * Clear old gesture information
         * =============================================
         */

        gestureBuffer.clear()

        preRoll.clear()


        previousLandmarks =
            null


        previousHasHand =
            false


        noHandFrames =
            0


        gestureStartMs =
            0L


        lastMotionMs =
            0L


        /*
         * =============================================
         * Ready for next word
         * =============================================
         */

        state =
            State.IDLE
    }


    // =========================================================
    // Pre-Roll Buffer
    // =========================================================

    private fun pushPreRoll(
        frame: TimedFrame
    ) {

        preRoll.addLast(
            frame
        )


        /*
         * Always keep only latest few frames.
         */
        while (
            preRoll.size >
            PRE_ROLL_FRAMES
        ) {

            preRoll.removeFirst()
        }
    }


    // =========================================================
    // Count Detected Hand Points
    // =========================================================

    private fun countDetectedHandPoints(
        landmarks: FloatArray
    ): Int {

        /*
         * Expected:
         *
         * 225 features
         */
        if (
            landmarks.size < 225
        ) {

            return 0
        }


        var count =
            0


        /*
         * Landmark point structure:
         *
         * Pose:
         * 0 ... 32
         *
         * Left Hand:
         * 33 ... 53
         *
         * Right Hand:
         * 54 ... 74
         *
         * Therefore hands:
         *
         * 33 until 75
         */
        for (
        point in
        33 until 75
        ) {

            val base =
                point * 3


            val x =
                landmarks[base]


            val y =
                landmarks[
                    base + 1
                ]


            if (
                x != 0f ||
                y != 0f
            ) {

                count++
            }
        }


        return count
    }


    // =========================================================
    // Calculate Hand Motion
    // =========================================================

    private fun calculateHandMotion(
        previous: FloatArray?,
        current: FloatArray
    ): Float {

        if (
            previous == null
        ) {

            return 0f
        }


        if (
            previous.size < 225 ||
            current.size < 225
        ) {

            return 0f
        }


        var totalMovement =
            0f


        var validPoints =
            0


        /*
         * Compare both hands.
         */
        for (
        point in
        33 until 75
        ) {

            val base =
                point * 3


            // -----------------------------------------
            // Previous
            // -----------------------------------------

            val previousX =
                previous[
                    base
                ]

            val previousY =
                previous[
                    base + 1
                ]


            // -----------------------------------------
            // Current
            // -----------------------------------------

            val currentX =
                current[
                    base
                ]

            val currentY =
                current[
                    base + 1
                ]


            // -----------------------------------------
            // Point exists?
            // -----------------------------------------

            val previousExists =

                previousX != 0f ||
                        previousY != 0f


            val currentExists =

                currentX != 0f ||
                        currentY != 0f


            /*
             * Compare only landmarks available
             * in both frames.
             */
            if (
                previousExists &&
                currentExists
            ) {

                val dx =
                    currentX -
                            previousX


                val dy =
                    currentY -
                            previousY


                val distance =

                    sqrt(
                        dx * dx +
                                dy * dy
                    )


                totalMovement +=
                    distance


                validPoints++
            }
        }


        /*
         * Average movement.
         */
        return if (
            validPoints > 0
        ) {

            totalMovement /
                    validPoints

        } else {

            0f
        }
    }


    // =========================================================
    // Update Status UI
    // =========================================================

    private fun updateStatus(
        now: Long,
        message: String,
        color: String,
        force: Boolean = false
    ) {

        /*
         * Camera frames 20-30 FPS එන නිසා
         * හැම frame එකේම UI update කළොත්
         * main thread එක overload වෙන්න පුළුවන්.
         *
         * ඒ නිසා 100ms interval.
         */
        if (
            !force &&
            now -
            lastUiUpdateMs <
            100L
        ) {

            return
        }


        lastUiUpdateMs =
            now


        scope.launch {

            /*
             * Result DISPLAYING state එකේ
             * තියෙනවා නම් status එක overwrite
             * කරන්න එපා.
             */
            if (
                state ==
                State.DISPLAYING
            ) {

                return@launch
            }


            recognizedLabelText.text =
                message


            recognizedLabelText.setTextColor(

                Color.parseColor(
                    color
                )
            )
        }
    }


    // =========================================================
    // Supported Words Dialog
    // =========================================================

    private fun setupSettingsButton() {

        findViewById<View>(
            R.id.settingsButton
        )?.setOnClickListener {

            SupportedWordsDialog()
                .show(

                    supportFragmentManager,

                    "SupportedWordsDialog"
                )
        }
    }


    // =========================================================
    // Edge-to-Edge Padding
    // =========================================================

    private fun applySystemBarPadding() {

        val mainView =

            findViewById<View>(
                R.id.main
            )

                ?: return


        val initialLeft =
            mainView.paddingLeft


        val initialTop =
            mainView.paddingTop


        val initialRight =
            mainView.paddingRight


        val initialBottom =
            mainView.paddingBottom


        ViewCompat
            .setOnApplyWindowInsetsListener(
                mainView
            ) { view, insets ->


                val bars =

                    insets.getInsets(

                        WindowInsetsCompat
                            .Type
                            .systemBars()
                    )


                view.setPadding(

                    initialLeft +
                            bars.left,

                    initialTop +
                            bars.top,

                    initialRight +
                            bars.right,

                    initialBottom +
                            bars.bottom
                )


                insets
            }
    }


    // =========================================================
    // Destroy / Cleanup
    // =========================================================

    override fun onDestroy() {

        /*
         * Pending result clear timer remove.
         */
        if (
            ::recognizedSentenceText.isInitialized
        ) {

            recognizedSentenceText
                .removeCallbacks(
                    clearResultRunnable
                )
        }


        /*
         * Coroutine cancel.
         */
        scope.cancel()


        /*
         * Camera shutdown.
         */
        if (
            ::cameraManager.isInitialized
        ) {

            cameraManager.shutdown()
        }


        /*
         * MediaPipe close.
         */
        if (
            ::landmarkProcessor.isInitialized
        ) {

            landmarkProcessor.close()
        }


        /*
         * TFLite close.
         */
        if (
            ::signRecognizer.isInitialized
        ) {

            signRecognizer.close()
        }


        super.onDestroy()
    }
}