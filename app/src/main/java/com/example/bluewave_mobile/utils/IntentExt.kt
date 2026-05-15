package com.example.bluewave_mobile.utils

import android.content.Intent
import android.os.Build
import android.os.Parcelable

/**
 * Backward-compatible helpers for [Intent] `getParcelableExtra` and
 * `getParcelableArrayExtra`, which were deprecated on API 33 because the
 * untyped versions return the wrong concrete class on SDK 33+ when the
 * caller is compiled with a newer `compileSdk`.
 */

inline fun <reified T : Parcelable> Intent.parcelableExtra(key: String): T? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(key, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(key) as? T
    }

inline fun <reified T : Parcelable> Intent.parcelableArrayExtra(key: String): Array<T>? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableArrayExtra(key, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableArrayExtra(key)?.filterIsInstance<T>()?.toTypedArray()
    }
