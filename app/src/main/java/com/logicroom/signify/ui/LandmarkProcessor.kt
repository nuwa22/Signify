package com.logicroom.signify.ui

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock

import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker.HandLandmarkerOptions
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker.PoseLandmarkerOptions

import kotlin.jvm.Synchronized

class LandmarkProcessor(
    context: Context
) {

    // =========================================================
    // MediaPipe Landmarkers
    // =========================================================

    private val poseLandmarker: PoseLandmarker
    private val handLandmarker: HandLandmarker


    // =========================================================
    // Feature configuration
    // =========================================================

    companion object {

        /*
         * Pose:
         * 33 landmarks × (x,y,z)
         * = 99 features
         */
        private const val POSE_LANDMARK_COUNT = 33

        /*
         * One hand:
         * 21 landmarks × (x,y,z)
         * = 63 features
         */
        private const val HAND_LANDMARK_COUNT = 21

        private const val VALUES_PER_LANDMARK = 3

        private const val POSE_FEATURE_COUNT =
            POSE_LANDMARK_COUNT * VALUES_PER_LANDMARK

        private const val HAND_FEATURE_COUNT =
            HAND_LANDMARK_COUNT * VALUES_PER_LANDMARK

        /*
         * Total:
         *
         * Pose       = 99
         * Left Hand  = 63
         * Right Hand = 63
         *
         * Total = 225
         */
        const val TOTAL_FEATURE_COUNT =
            POSE_FEATURE_COUNT +
                    HAND_FEATURE_COUNT +
                    HAND_FEATURE_COUNT
    }


    // =========================================================
    // Timestamp tracking
    // =========================================================

    /*
     * detectForVideo() requires consecutive timestamps.
     * We protect against two frames receiving an equal timestamp.
     */
    private var lastTimestampMs: Long = -1L


    // =========================================================
    // Initialize MediaPipe
    // =========================================================

    init {

        // -----------------------------------------------------
        // Pose Landmarker
        // -----------------------------------------------------

        val poseBaseOptions =
            BaseOptions.builder()
                .setModelAssetPath(
                    "pose_landmarker_lite.task"
                )
                .build()

        val poseOptions =
            PoseLandmarkerOptions.builder()
                .setBaseOptions(
                    poseBaseOptions
                )

                /*
                 * We process sequential camera frames.
                 */
                .setRunningMode(
                    RunningMode.VIDEO
                )

                /*
                 * Only one person needed.
                 */
                .setNumPoses(1)

                /*
                 * These can be tuned later.
                 *
                 * Too low:
                 * noise / false landmarks
                 *
                 * Too high:
                 * landmarks disappear easily.
                 */
                .setMinPoseDetectionConfidence(
                    0.5f
                )
                .setMinPosePresenceConfidence(
                    0.5f
                )
                .setMinTrackingConfidence(
                    0.5f
                )

                .build()

        poseLandmarker =
            PoseLandmarker.createFromOptions(
                context,
                poseOptions
            )


        // -----------------------------------------------------
        // Hand Landmarker
        // -----------------------------------------------------

        val handBaseOptions =
            BaseOptions.builder()
                .setModelAssetPath(
                    "hand_landmarker.task"
                )
                .build()

        val handOptions =
            HandLandmarkerOptions.builder()
                .setBaseOptions(
                    handBaseOptions
                )

                /*
                 * Sequential video-frame processing.
                 */
                .setRunningMode(
                    RunningMode.VIDEO
                )

                /*
                 * Sign Language can use both hands.
                 */
                .setNumHands(2)

                /*
                 * Detection settings.
                 */
                .setMinHandDetectionConfidence(
                    0.5f
                )
                .setMinHandPresenceConfidence(
                    0.5f
                )
                .setMinTrackingConfidence(
                    0.5f
                )

                .build()

        handLandmarker =
            HandLandmarker.createFromOptions(
                context,
                handOptions
            )
    }


    // =========================================================
    // Main landmark extraction
    // =========================================================

    /**
     * Converts one Bitmap frame into:
     *
     * FloatArray(225)
     *
     * Feature structure:
     *
     * 0   ... 98  = Pose
     * 99  ... 161 = Left Hand
     * 162 ... 224 = Right Hand
     *
     * Each landmark:
     *
     * [x, y, z]
     */
    @Synchronized
    fun extractLandmarks(
        bitmap: Bitmap
    ): FloatArray {

        // -----------------------------------------------------
        // 1. Bitmap -> MediaPipe image
        // -----------------------------------------------------

        val mpImage =
            BitmapImageBuilder(bitmap)
                .build()


        // -----------------------------------------------------
        // 2. Generate valid timestamp
        // -----------------------------------------------------

        var timestampMs =
            SystemClock.uptimeMillis()

        /*
         * MediaPipe VIDEO mode expects each new frame
         * to have a newer timestamp.
         */
        if (timestampMs <= lastTimestampMs) {

            timestampMs =
                lastTimestampMs + 1L
        }

        lastTimestampMs =
            timestampMs


        // -----------------------------------------------------
        // 3. Detect pose
        // -----------------------------------------------------

        val poseResult =
            try {

                poseLandmarker.detectForVideo(
                    mpImage,
                    timestampMs
                )

            } catch (e: Exception) {

                e.printStackTrace()
                null
            }


        // -----------------------------------------------------
        // 4. Detect hands
        // -----------------------------------------------------

        val handResult =
            try {

                handLandmarker.detectForVideo(
                    mpImage,
                    timestampMs
                )

            } catch (e: Exception) {

                e.printStackTrace()
                null
            }


        // -----------------------------------------------------
        // 5. Create empty feature arrays
        // -----------------------------------------------------

        /*
         * Missing landmarks stay as zero.
         */

        val poseFeatures =
            FloatArray(
                POSE_FEATURE_COUNT
            )

        val leftHandFeatures =
            FloatArray(
                HAND_FEATURE_COUNT
            )

        val rightHandFeatures =
            FloatArray(
                HAND_FEATURE_COUNT
            )


        // =====================================================
        // POSE FEATURES
        // =====================================================

        val poseLandmarks =
            poseResult
                ?.landmarks()
                ?.firstOrNull()

        if (poseLandmarks != null) {

            poseLandmarks
                .take(POSE_LANDMARK_COUNT)
                .forEachIndexed { index, landmark ->

                    val baseIndex =
                        index * VALUES_PER_LANDMARK

                    poseFeatures[baseIndex] =
                        safeValue(
                            landmark.x()
                        )

                    poseFeatures[baseIndex + 1] =
                        safeValue(
                            landmark.y()
                        )

                    poseFeatures[baseIndex + 2] =
                        safeValue(
                            landmark.z()
                        )
                }
        }


        // =====================================================
        // HAND FEATURES
        // =====================================================

        if (handResult != null) {

            val detectedHands =
                handResult.landmarks()

            val handednessResults =
                handResult.handedness()


            detectedHands.forEachIndexed {
                    handIndex,
                    handLandmarks ->

                /*
                 * Handedness result example:
                 *
                 * Left
                 * Right
                 */
                val handedness =
                    handednessResults
                        .getOrNull(handIndex)
                        ?.firstOrNull()
                        ?.categoryName()
                        ?.trim()


                /*
                 * Convert 21 landmarks into:
                 *
                 * 21 × 3 = 63 floats
                 */
                val handFeatures =
                    FloatArray(
                        HAND_FEATURE_COUNT
                    )

                handLandmarks
                    .take(HAND_LANDMARK_COUNT)
                    .forEachIndexed {
                            landmarkIndex,
                            landmark ->

                        val baseIndex =
                            landmarkIndex *
                                    VALUES_PER_LANDMARK

                        handFeatures[baseIndex] =
                            safeValue(
                                landmark.x()
                            )

                        handFeatures[baseIndex + 1] =
                            safeValue(
                                landmark.y()
                            )

                        handFeatures[baseIndex + 2] =
                            safeValue(
                                landmark.z()
                            )
                    }


                /*
                 * Put each detected hand into the correct
                 * 63-feature slot.
                 */
                when (
                    handedness?.uppercase()
                ) {

                    "LEFT" -> {

                        handFeatures.copyInto(
                            destination =
                                leftHandFeatures
                        )
                    }

                    "RIGHT" -> {

                        handFeatures.copyInto(
                            destination =
                                rightHandFeatures
                        )
                    }

                    /*
                     * Rare fallback:
                     *
                     * If MediaPipe detected a hand but
                     * couldn't return handedness,
                     * keep the data rather than throwing it away.
                     */
                    else -> {

                        if (
                            isEmptyHand(
                                leftHandFeatures
                            )
                        ) {

                            handFeatures.copyInto(
                                destination =
                                    leftHandFeatures
                            )

                        } else if (
                            isEmptyHand(
                                rightHandFeatures
                            )
                        ) {

                            handFeatures.copyInto(
                                destination =
                                    rightHandFeatures
                            )
                        }
                    }
                }
            }
        }


        // =====================================================
        // FINAL 225 FEATURE ARRAY
        // =====================================================

        val result =
            FloatArray(
                TOTAL_FEATURE_COUNT
            )


        // Pose:
        // index 0 ... 98
        poseFeatures.copyInto(
            destination = result,
            destinationOffset = 0
        )


        // Left Hand:
        // index 99 ... 161
        leftHandFeatures.copyInto(
            destination = result,
            destinationOffset =
                POSE_FEATURE_COUNT
        )


        // Right Hand:
        // index 162 ... 224
        rightHandFeatures.copyInto(
            destination = result,
            destinationOffset =
                POSE_FEATURE_COUNT +
                        HAND_FEATURE_COUNT
        )


        return result
    }


    // =========================================================
    // Helper functions
    // =========================================================

    /**
     * Prevent NaN / Infinity values from entering
     * the TFLite model.
     */
    private fun safeValue(
        value: Float
    ): Float {

        return if (
            value.isFinite()
        ) {

            value

        } else {

            0f
        }
    }


    /**
     * Check whether an entire hand feature vector
     * contains no detected landmarks.
     */
    private fun isEmptyHand(
        features: FloatArray
    ): Boolean {

        for (value in features) {

            if (value != 0f) {
                return false
            }
        }

        return true
    }


    // =========================================================
    // Optional detection helpers
    // =========================================================

    /**
     * Returns true if at least a reasonable number of
     * hand landmarks are present.
     *
     * Can be used from MainActivity if needed.
     */
    fun hasHand(
        landmarks: FloatArray,
        minimumPoints: Int = 6
    ): Boolean {

        if (
            landmarks.size <
            TOTAL_FEATURE_COUNT
        ) {
            return false
        }

        var detectedPoints = 0


        // Left + Right hands:
        //
        // point 33 ... 74
        //
        // pose = 0 ... 32
        // left = 33 ... 53
        // right = 54 ... 74

        for (pointIndex in 33 until 75) {

            val featureIndex =
                pointIndex *
                        VALUES_PER_LANDMARK

            val x =
                landmarks[
                    featureIndex
                ]

            val y =
                landmarks[
                    featureIndex + 1
                ]

            /*
             * A detected normalized landmark should normally
             * contain x/y values.
             */
            if (
                x != 0f ||
                y != 0f
            ) {

                detectedPoints++

                if (
                    detectedPoints >=
                    minimumPoints
                ) {

                    return true
                }
            }
        }

        return false
    }


    /**
     * Number of detected hand landmarks.
     *
     * Range:
     * 0 ... 42
     */
    fun countHandLandmarks(
        landmarks: FloatArray
    ): Int {

        if (
            landmarks.size <
            TOTAL_FEATURE_COUNT
        ) {

            return 0
        }

        var count = 0

        for (
        pointIndex in
        33 until 75
        ) {

            val featureIndex =
                pointIndex *
                        VALUES_PER_LANDMARK

            val x =
                landmarks[
                    featureIndex
                ]

            val y =
                landmarks[
                    featureIndex + 1
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


    /**
     * Returns whether pose/body landmarks were found.
     */
    fun hasBody(
        landmarks: FloatArray,
        minimumPoints: Int = 8
    ): Boolean {

        if (
            landmarks.size <
            TOTAL_FEATURE_COUNT
        ) {

            return false
        }

        var detectedPoints = 0

        for (
        pointIndex in
        0 until POSE_LANDMARK_COUNT
        ) {

            val featureIndex =
                pointIndex *
                        VALUES_PER_LANDMARK

            val x =
                landmarks[
                    featureIndex
                ]

            val y =
                landmarks[
                    featureIndex + 1
                ]

            if (
                x != 0f ||
                y != 0f
            ) {

                detectedPoints++

                if (
                    detectedPoints >=
                    minimumPoints
                ) {

                    return true
                }
            }
        }

        return false
    }


    // =========================================================
    // Cleanup
    // =========================================================

    fun close() {

        try {

            poseLandmarker.close()

        } catch (
            e: Exception
        ) {

            e.printStackTrace()
        }

        try {

            handLandmarker.close()

        } catch (
            e: Exception
        ) {

            e.printStackTrace()
        }
    }
}