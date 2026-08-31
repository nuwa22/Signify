package com.logicroom.signify.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.appcompat.app.AppCompatActivity
import com.logicroom.signify.R

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        val content  = findViewById<View>(R.id.splashContent)
        val logo     = findViewById<View>(R.id.splashLogo)
        val subtitle = findViewById<View>(R.id.splashSubtitle)

        playIntroAnimation(content, logo, subtitle)

        content.postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }, 2100L)
    }

    private fun playIntroAnimation(content: View, logo: View, subtitle: View) {
        val logoEntrance = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(logo, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(logo, View.SCALE_X, 0.78f, 1.04f, 1f),
                ObjectAnimator.ofFloat(logo, View.SCALE_Y, 0.78f, 1.04f, 1f),
                ObjectAnimator.ofFloat(logo, View.TRANSLATION_Y, 22f, 0f)
            )
            duration = 760L
            interpolator = OvershootInterpolator(1.15f)
        }

        val subtitleEntrance = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(subtitle, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(subtitle, View.TRANSLATION_Y, 14f, 0f)
            )
            startDelay = 330L
            duration = 560L
            interpolator = DecelerateInterpolator()
        }

        val settle = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(content, View.SCALE_X, 1f, 1.015f, 1f),
                ObjectAnimator.ofFloat(content, View.SCALE_Y, 1f, 1.015f, 1f)
            )
            startDelay = 920L
            duration = 420L
            interpolator = DecelerateInterpolator()
        }

        AnimatorSet().apply {
            playTogether(logoEntrance, subtitleEntrance, settle)
            start()
        }
    }
}