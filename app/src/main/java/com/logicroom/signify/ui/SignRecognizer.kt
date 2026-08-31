package com.logicroom.signify.ui

import android.content.Context
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.exp

class SignRecognizer(context: Context) {

    private val interpreter: Interpreter
    private val labels: List<String>

    private val TARGET_FRAMES = 32
    private val NUM_FEATURES = 225

    // Start with these. Later tune using validation/test results.
    private val CONFIDENCE_THRESHOLD = 0.60f
    private val MIN_MARGIN = 0.05f

    private val inputBuffer:
            Array<Array<FloatArray>>

    private val outputBuffer:
            Array<FloatArray>

    private val sinhalaMap = mapOf(
        "COLOMBO" to "කොළඹ",
        "COME" to "එන්න",
        "DONT" to "එපා",
        "DRINK" to "බොන්න",
        "EAT" to "කන්න",
        "FATHER" to "තාත්තා",
        "GIVE" to "දෙන්න",
        "GO" to "යන්න",
        "GOODMORNING" to "සුබ උදෑසනක්",
        "GOODNIGHT" to "සුබ රාත්‍රියක්",
        "HELLO" to "ආයුබෝවන්",
        "HELP" to "උදව්",
        "HOME" to "ගෙදර",
        "HOSPITAL" to "රෝහල",
        "ME" to "මම",
        "MOTHER" to "අම්මා",
        "POLICE" to "පොලීසිය",
        "THANKS" to "ස්තූතියි",
        "TIME" to "වේලාව",
        "TODAY" to "අද",
        "WHERE" to "කොහේද",
        "WHO" to "කවුද",
        "YES" to "ඔව්",
        "YOU" to "ඔබ"
    )

    init {
        interpreter = Interpreter(loadModelFile(context))

        labels = loadLabels(context)

        val inputTensor = interpreter.getInputTensor(0)
        val outputTensor = interpreter.getOutputTensor(0)

        val inputShape = inputTensor.shape()
        val outputShape = outputTensor.shape()

        require(inputTensor.dataType() == DataType.FLOAT32) {
            "Expected FLOAT32 input model but found ${inputTensor.dataType()}"
        }

        require(outputTensor.dataType() == DataType.FLOAT32) {
            "Expected FLOAT32 output model but found ${outputTensor.dataType()}"
        }

        require(
            inputShape.size == 3 &&
                    inputShape[0] == 1 &&
                    inputShape[1] == TARGET_FRAMES &&
                    inputShape[2] == NUM_FEATURES
        ) {
            "Wrong model input shape: ${inputShape.contentToString()}. " +
                    "Expected [1, 32, 225]"
        }

        val numberOfClasses = outputShape.last()

        require(numberOfClasses == labels.size) {
            "MODEL/LABEL MISMATCH: model output=$numberOfClasses classes, " +
                    "labels.txt=${labels.size} labels."
        }

        inputBuffer = Array(1) {
            Array(TARGET_FRAMES) {
                FloatArray(NUM_FEATURES)
            }
        }

        outputBuffer = Array(1) {
            FloatArray(numberOfClasses)
        }
    }

    @Synchronized
    fun predict(sequence: Array<FloatArray>): PredictionResult {

        if (sequence.isEmpty()) {
            return PredictionResult(
                "UNKNOWN",
                "හඳුනාගත නොහැකිය",
                0f,
                false
            )
        }

        sequence.forEach {
            require(it.size == NUM_FEATURES) {
                "Each frame must contain 225 features. Found ${it.size}"
            }
        }

        val normalized = interpolateSequence(sequence)

        for (frame in 0 until TARGET_FRAMES) {
            for (feature in 0 until NUM_FEATURES) {
                inputBuffer[0][frame][feature] =
                    normalized[frame][feature]
            }
        }

        // Clear previous output
        outputBuffer[0].fill(0f)

        interpreter.run(inputBuffer, outputBuffer)

        val rawOutput = outputBuffer[0].copyOf()

        // Important:
        // If model already contains a SoftMax layer, DON'T softmax again.
        val probabilities =
            if (looksLikeProbabilities(rawOutput)) {
                rawOutput
            } else {
                softmax(rawOutput)
            }

        val sortedIndices =
            probabilities.indices.sortedByDescending {
                probabilities[it]
            }

        val bestIndex = sortedIndices.firstOrNull() ?: 0
        val secondIndex =
            sortedIndices.getOrNull(1) ?: bestIndex

        val confidence = probabilities[bestIndex]
        val secondConfidence = probabilities[secondIndex]

        val margin = confidence - secondConfidence

        if (bestIndex !in labels.indices) {
            return PredictionResult(
                "UNKNOWN",
                "හඳුනාගත නොහැකිය",
                confidence,
                false
            )
        }

        val englishLabel = labels[bestIndex]
        val sinhalaWord = sinhalaMap[englishLabel]

        val recognized =
            confidence >= CONFIDENCE_THRESHOLD &&
                    margin >= MIN_MARGIN &&
                    sinhalaWord != null

        return if (recognized) {
            PredictionResult(
                englishLabel,
                sinhalaWord!!,
                confidence,
                true
            )
        } else {
            PredictionResult(
                englishLabel,
                "හඳුනාගත නොහැකිය",
                confidence,
                false
            )
        }
    }

    /**
     * THIS IS THE IMPORTANT PART.
     *
     * 5 frames  -> interpolate -> 32 frames
     * 18 frames -> interpolate -> 32 frames
     * 32 frames -> unchanged
     * 70 frames -> interpolate -> 32 frames
     * 118 frames -> interpolate -> 32 frames
     *
     * No zero padding.
     */
    private fun interpolateSequence(
        sequence: Array<FloatArray>
    ): Array<FloatArray> {

        val sourceFrames = sequence.size

        if (sourceFrames == TARGET_FRAMES) {
            return Array(TARGET_FRAMES) {
                sequence[it].copyOf()
            }
        }

        // Only one usable frame
        if (sourceFrames == 1) {
            return Array(TARGET_FRAMES) {
                sequence[0].copyOf()
            }
        }

        val result =
            Array(TARGET_FRAMES) {
                FloatArray(NUM_FEATURES)
            }

        for (targetFrame in 0 until TARGET_FRAMES) {

            val sourcePosition =
                targetFrame.toFloat() *
                        (sourceFrames - 1).toFloat() /
                        (TARGET_FRAMES - 1).toFloat()

            val lower =
                sourcePosition.toInt()
                    .coerceIn(0, sourceFrames - 1)

            val upper =
                (lower + 1)
                    .coerceIn(0, sourceFrames - 1)

            val fraction =
                sourcePosition - lower.toFloat()

            for (feature in 0 until NUM_FEATURES) {

                val lowerValue =
                    sequence[lower][feature]

                val upperValue =
                    sequence[upper][feature]

                result[targetFrame][feature] =
                    lowerValue +
                            (upperValue - lowerValue) * fraction
            }
        }

        return result
    }

    private fun looksLikeProbabilities(
        values: FloatArray
    ): Boolean {

        if (values.isEmpty()) return false

        if (values.any {
                !it.isFinite() ||
                        it < -0.0001f ||
                        it > 1.0001f
            }
        ) {
            return false
        }

        val sum = values.sum()

        return abs(sum - 1f) < 0.03f
    }

    private fun softmax(logits: FloatArray): FloatArray {

        val max =
            logits.maxOrNull() ?: 0f

        val exps =
            FloatArray(logits.size) {
                exp(
                    (logits[it] - max)
                        .toDouble()
                ).toFloat()
            }

        val sum = exps.sum()

        if (sum <= 0f) {
            return FloatArray(logits.size)
        }

        return FloatArray(exps.size) {
            exps[it] / sum
        }
    }

    private fun loadLabels(
        context: Context
    ): List<String> {

        return context.assets
            .open("labels.txt")
            .bufferedReader()
            .useLines { lines ->

                lines
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .map { canonicalLabel(it) }
                    .toList()
            }
    }

    private fun canonicalLabel(
        value: String
    ): String {

        return value
            .trim()
            .uppercase()
            .replace("_", "")
            .replace(" ", "")
            .replace("-", "")
    }

    private fun loadModelFile(
        context: Context
    ): MappedByteBuffer {

        val fileDescriptor =
            context.assets.openFd("signify.tflite")

        FileInputStream(
            fileDescriptor.fileDescriptor
        ).use { inputStream ->

            return inputStream.channel.map(
                FileChannel.MapMode.READ_ONLY,
                fileDescriptor.startOffset,
                fileDescriptor.declaredLength
            )
        }
    }

    fun close() {
        interpreter.close()
    }

    data class PredictionResult(
        val englishLabel: String,
        val sinhalaWord: String,
        val confidence: Float,
        val isRecognized: Boolean
    )
}