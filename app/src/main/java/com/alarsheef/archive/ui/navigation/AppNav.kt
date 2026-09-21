package com.alarsheef.archive.ui.navigation

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Month : Screen("month/{year}") {
        fun build(year: Int) = "month/$year"
    }
    data object Day : Screen("day/{year}/{month}") {
        fun build(year: Int, month: Int) = "day/$year/$month"
    }
    data object Files : Screen("files/{year}/{month}/{day}?openImageId={openImageId}") {
        const val OPEN_IMAGE_ID_ARG = "openImageId"
        fun build(year: Int, month: Int, day: Int, openImageId: Long = -1L) =
            "files/$year/$month/$day?openImageId=$openImageId"
    }
    data object Faces : Screen("faces")
}
