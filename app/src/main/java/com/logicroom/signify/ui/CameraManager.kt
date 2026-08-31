package com.logicroom.signify.ui

import android.content.Context
import android.graphics.Bitmap
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val onFrameReady: (Bitmap) -> Unit
) {

    private val cameraExecutor:
            ExecutorService =
        Executors.newSingleThreadExecutor()

    fun startCamera() {

        val cameraProviderFuture =
            ProcessCameraProvider
                .getInstance(context)

        cameraProviderFuture.addListener({

            val cameraProvider =
                cameraProviderFuture.get()

            val targetRotation =
                previewView.display?.rotation
                    ?: Surface.ROTATION_0

            previewView.scaleType =
                PreviewView.ScaleType.FILL_CENTER

            val preview =
                Preview.Builder()
                    .setTargetRotation(
                        targetRotation
                    )
                    .build()
                    .also {
                        it.setSurfaceProvider(
                            previewView.surfaceProvider
                        )
                    }

            val imageAnalyzer =
                ImageAnalysis.Builder()
                    .setTargetRotation(
                        targetRotation
                    )
                    .setBackpressureStrategy(
                        ImageAnalysis
                            .STRATEGY_KEEP_ONLY_LATEST
                    )
                    .setOutputImageFormat(
                        ImageAnalysis
                            .OUTPUT_IMAGE_FORMAT_RGBA_8888
                    )
                    /*
                     * Make analysis pixels match
                     * display orientation.
                     */
                    .setOutputImageRotationEnabled(
                        true
                    )
                    .build()

            imageAnalyzer.setAnalyzer(
                cameraExecutor
            ) { imageProxy ->

                try {

                    val bitmap =
                        imageProxy.toBitmap()

                    onFrameReady(bitmap)

                } catch (e: Exception) {

                    e.printStackTrace()

                } finally {

                    imageProxy.close()
                }
            }

            try {

                cameraProvider.unbindAll()

                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector
                        .DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalyzer
                )

            } catch (e: Exception) {

                e.printStackTrace()
            }

        }, ContextCompat.getMainExecutor(context))
    }

    fun shutdown() {

        if (!cameraExecutor.isShutdown) {
            cameraExecutor.shutdown()
        }
    }
}