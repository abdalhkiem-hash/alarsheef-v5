package com.alarsheef.archive.ai

/**
 * متى يُتجاوز محرك سامسونغ؟ على بعض الأجهزة يعيد «نجاحًا» بلا أي نص (ويستغرق ~3 ثوانٍ للغة)،
 * بينما يقرأ Tesseract الصورة نفسها. بعد [GIVE_UP_AFTER] صور متتالية ثبت فيها وجود نص بمحرك آخر
 * وسامسونغ فارغ، يُتجاوز حتى يعود إلى القراءة. الصورة الخالية من النص لا تُحسب ضده.
 */
object OcrFallbackPolicy {

    const val GIVE_UP_AFTER = 3

    fun shouldTrySamsung(emptyStreak: Int): Boolean = emptyStreak < GIVE_UP_AFTER

    /**
     * @param samsungChars ما قرأه سامسونغ في هذه الصورة.
     * @param otherChars أطول نص قرأه محرك آخر في الصورة نفسها.
     */
    fun nextStreak(streak: Int, samsungChars: Int, otherChars: Int, minChars: Int, strongChars: Int): Int = when {
        samsungChars >= minChars -> 0
        otherChars >= strongChars -> streak + 1
        else -> streak
    }
}
