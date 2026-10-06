package com.frameender.pocketstash.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.frameender.pocketstash.AppContainer
import com.frameender.pocketstash.container

/** ViewModel with manual DI from the app container. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(
    key: String? = null,
    crossinline create: (AppContainer) -> VM,
): VM {
    val container = LocalContext.current.container
    return viewModel(
        key = key,
        factory = viewModelFactory { initializer { create(container) } },
    )
}

/** Simple load/error/data holder for detail screens. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Error(val message: String) : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
}

fun <T> Load<T>.valueOrNull(): T? = (this as? Load.Ready<T>)?.value

fun Throwable.friendly(): String = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
