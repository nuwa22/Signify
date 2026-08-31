package com.logicroom.signify.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

class LandmarkOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val posePaint =
        Paint().apply {
            color = Color.GREEN
            strokeWidth = 4f
            style = Paint.Style.FILL
        }

    private val handPaint =
        Paint().apply {
            color = Color.CYAN
            strokeWidth = 4f
            style = Paint.Style.FILL
        }

    private val linePaint =
        Paint().apply {
            color = Color.WHITE
            strokeWidth = 2f
            style = Paint.Style.STROKE
            alpha = 180
        }

    private var landmarks:
            FloatArray? = null

    private var imageWidth = 1
    private var imageHeight = 1

    private val handConnections =
        listOf(
            0 to 1,
            1 to 2,
            2 to 3,
            3 to 4,

            0 to 5,
            5 to 6,
            6 to 7,
            7 to 8,

            0 to 9,
            9 to 10,
            10 to 11,
            11 to 12,

            0 to 13,
            13 to 14,
            14 to 15,
            15 to 16,

            0 to 17,
            17 to 18,
            18 to 19,
            19 to 20,

            5 to 9,
            9 to 13,
            13 to 17
        )

    private val poseConnections =
        listOf(
            11 to 12,
            11 to 13,
            13 to 15,
            12 to 14,
            14 to 16,
            11 to 23,
            12 to 24
        )

    fun updateLandmarks(
        lm: FloatArray,
        imgW: Int,
        imgH: Int
    ) {

        landmarks = lm

        imageWidth =
            maxOf(1, imgW)

        imageHeight =
            maxOf(1, imgH)

        postInvalidate()
    }

    override fun onDraw(
        canvas: Canvas
    ) {

        super.onDraw(canvas)

        val lm =
            landmarks ?: return

        if (lm.size < 225) {
            return
        }

        /*
         * PreviewView = FILL_CENTER
         *
         * So reproduce the same center-crop transform.
         */
        val scale =
            max(
                width.toFloat() /
                        imageWidth.toFloat(),

                height.toFloat() /
                        imageHeight.toFloat()
            )

        val displayedWidth =
            imageWidth * scale

        val displayedHeight =
            imageHeight * scale

        val offsetX =
            (width - displayedWidth) / 2f

        val offsetY =
            (height - displayedHeight) / 2f

        fun point(
            index: Int
        ): Pair<Float, Float>? {

            val base =
                index * 3

            val xNorm =
                lm[base]

            val yNorm =
                lm[base + 1]

            if (
                xNorm == 0f &&
                yNorm == 0f
            ) {
                return null
            }

            val imageX =
                xNorm *
                        imageWidth

            val imageY =
                yNorm *
                        imageHeight

            val viewX =
                imageX * scale +
                        offsetX

            val viewY =
                imageY * scale +
                        offsetY

            return Pair(
                viewX,
                viewY
            )
        }

        /*
         * POSE
         */
        for (
        connection in
        poseConnections
        ) {

            val p1 =
                point(
                    connection.first
                )

            val p2 =
                point(
                    connection.second
                )

            if (
                p1 != null &&
                p2 != null
            ) {

                canvas.drawLine(
                    p1.first,
                    p1.second,
                    p2.first,
                    p2.second,
                    linePaint
                )
            }
        }

        for (i in 0 until 33) {

            val p =
                point(i)

            if (p != null) {

                canvas.drawCircle(
                    p.first,
                    p.second,
                    5f,
                    posePaint
                )
            }
        }

        /*
         * LEFT HAND
         */
        val leftOffset = 33

        for (
        connection in
        handConnections
        ) {

            val p1 =
                point(
                    leftOffset +
                            connection.first
                )

            val p2 =
                point(
                    leftOffset +
                            connection.second
                )

            if (
                p1 != null &&
                p2 != null
            ) {

                canvas.drawLine(
                    p1.first,
                    p1.second,
                    p2.first,
                    p2.second,
                    linePaint
                )
            }
        }

        for (i in 0 until 21) {

            val p =
                point(
                    leftOffset + i
                )

            if (p != null) {

                canvas.drawCircle(
                    p.first,
                    p.second,
                    6f,
                    handPaint
                )
            }
        }

        /*
         * RIGHT HAND
         */
        val rightOffset = 54

        for (
        connection in
        handConnections
        ) {

            val p1 =
                point(
                    rightOffset +
                            connection.first
                )

            val p2 =
                point(
                    rightOffset +
                            connection.second
                )

            if (
                p1 != null &&
                p2 != null
            ) {

                canvas.drawLine(
                    p1.first,
                    p1.second,
                    p2.first,
                    p2.second,
                    linePaint
                )
            }
        }

        for (i in 0 until 21) {

            val p =
                point(
                    rightOffset + i
                )

            if (p != null) {

                canvas.drawCircle(
                    p.first,
                    p.second,
                    6f,
                    handPaint
                )
            }
        }
    }
}