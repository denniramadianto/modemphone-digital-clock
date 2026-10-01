package id.my.sir.mpdclock

import android.graphics.Color

/**
 * Satu tema = paket lengkap warna + susunan layout.
 * Semua fitur tetap sama di semua tema; yang berubah hanya tampilan.
 */
data class ClockTheme(
    val name: String,
    val layout: Int,
    val bg: Int,
    val digitOn: Int,
    val digitOff: Int,
    val text: Int,
    val subText: Int,
    val boxFill: Int,
    val boxStroke: Int,
    val divider: Int
)

val THEMES = listOf(
    ClockTheme(
        name = "Siang Klasik",
        layout = R.layout.activity_main,
        bg = Color.WHITE,
        digitOn = Color.BLACK,
        digitOff = Color.parseColor("#EDEDED"),
        text = Color.BLACK,
        subText = Color.parseColor("#D8D8D8"),
        boxFill = Color.WHITE,
        boxStroke = Color.BLACK,
        divider = Color.parseColor("#D0D0D0")
    ),
    ClockTheme(
        name = "Malam Hijau",
        layout = R.layout.activity_main_full,
        bg = Color.BLACK,
        digitOn = Color.parseColor("#00E676"),
        digitOff = Color.parseColor("#0A2A1A"),
        text = Color.parseColor("#00E676"),
        subText = Color.parseColor("#2E7D5B"),
        boxFill = Color.parseColor("#0A0A0A"),
        boxStroke = Color.parseColor("#00E676"),
        divider = Color.parseColor("#1B5E20")
    ),
    ClockTheme(
        name = "Alarm Merah",
        layout = R.layout.activity_main,
        bg = Color.BLACK,
        digitOn = Color.parseColor("#FF3B30"),
        digitOff = Color.parseColor("#2A0A0A"),
        text = Color.parseColor("#FF3B30"),
        subText = Color.parseColor("#8E2A25"),
        boxFill = Color.parseColor("#0A0A0A"),
        boxStroke = Color.parseColor("#FF3B30"),
        divider = Color.parseColor("#5E1A15")
    ),
    ClockTheme(
        name = "Senja Amber",
        layout = R.layout.activity_main,
        bg = Color.parseColor("#0B0800"),
        digitOn = Color.parseColor("#FFB300"),
        digitOff = Color.parseColor("#2A1F05"),
        text = Color.parseColor("#FFB300"),
        subText = Color.parseColor("#8A6D1F"),
        boxFill = Color.parseColor("#12100A"),
        boxStroke = Color.parseColor("#FFB300"),
        divider = Color.parseColor("#4A380F")
    ),
    ClockTheme(
        name = "Samudra",
        layout = R.layout.activity_main_side,
        bg = Color.parseColor("#0A1628"),
        digitOn = Color.parseColor("#22D3EE"),
        digitOff = Color.parseColor("#0E2A38"),
        text = Color.parseColor("#E8F4FF"),
        subText = Color.parseColor("#5E7E9E"),
        boxFill = Color.parseColor("#0E1E36"),
        boxStroke = Color.parseColor("#22D3EE"),
        divider = Color.parseColor("#164E63")
    ),
    ClockTheme(
        name = "Kertas Vintage",
        layout = R.layout.activity_main,
        bg = Color.parseColor("#F7F0DC"),
        digitOn = Color.parseColor("#5D3A1A"),
        digitOff = Color.parseColor("#E8DCC0"),
        text = Color.parseColor("#5D3A1A"),
        subText = Color.parseColor("#B09A72"),
        boxFill = Color.parseColor("#F7F0DC"),
        boxStroke = Color.parseColor("#5D3A1A"),
        divider = Color.parseColor("#C9B98A")
    ),
    ClockTheme(
        name = "Smartwatch",
        layout = R.layout.activity_main_watch,
        bg = Color.BLACK,
        digitOn = Color.WHITE,
        digitOff = Color.parseColor("#1A1A1A"),
        text = Color.WHITE,
        subText = Color.parseColor("#555555"),
        boxFill = Color.parseColor("#0A0A0A"),
        boxStroke = Color.parseColor("#00E676"),
        divider = Color.parseColor("#333333")
    ),
    ClockTheme(
        name = "Malam Klasik",
        layout = R.layout.activity_main,
        bg = Color.BLACK,
        digitOn = Color.WHITE,
        digitOff = Color.parseColor("#1A1A1A"),
        text = Color.WHITE,
        subText = Color.parseColor("#555555"),
        boxFill = Color.parseColor("#0A0A0A"),
        boxStroke = Color.WHITE,
        divider = Color.parseColor("#333333")
    )
)
