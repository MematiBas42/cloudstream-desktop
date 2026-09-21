// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/SingleSelectionHelper.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.utils

import android.app.Activity
import android.app.Dialog
import android.view.View
import com.lagradost.cloudstream3.annotation.PlatformQuarantine
import com.lagradost.cloudstream3.annotation.QuarantineStatus
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.UIHelper.dismissSafe
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Event payload representing a single or multi selection dialog request on desktop.
 * Observed by desktop-app Compose UI to render native modal selection dialogs.
 */
data class SelectionDialogRequest(
    val title: String,
    val items: List<String>,
    val selectedIndices: List<Int>,
    val isMultiSelect: Boolean,
    val showApply: Boolean = true,
    val onSelected: (List<Int>) -> Unit,
    val onDismiss: () -> Unit = {}
)

data class InputDialogRequest(
    val title: String,
    val initialValue: String,
    val textInputType: Int? = null,
    val onSubmitted: (String) -> Unit,
    val onDismiss: () -> Unit = {}
)

data class TextDialogRequest(
    val title: String,
    val text: CharSequence,
    val onDismiss: () -> Unit = {}
)

object SingleSelectionHelper {
    private val _dialogRequests = MutableSharedFlow<SelectionDialogRequest>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val dialogRequests: SharedFlow<SelectionDialogRequest> = _dialogRequests.asSharedFlow()

    private val _inputDialogRequests = MutableSharedFlow<InputDialogRequest>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val inputDialogRequests: SharedFlow<InputDialogRequest> = _inputDialogRequests.asSharedFlow()

    private val _textDialogRequests = MutableSharedFlow<TextDialogRequest>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val textDialogRequests: SharedFlow<TextDialogRequest> = _textDialogRequests.asSharedFlow()

    val onSelectionEvent = Event<SelectionDialogRequest>()
    val onInputEvent = Event<InputDialogRequest>()
    val onTextEvent = Event<TextDialogRequest>()

    @Volatile
    var customDialogHandler: ((SelectionDialogRequest) -> Unit)? = null

    @Volatile
    var customInputHandler: ((InputDialogRequest) -> Unit)? = null

    @Volatile
    var customTextHandler: ((TextDialogRequest) -> Unit)? = null

    fun Activity?.showOptionSelectStringRes(
        view: View?,
        poster: String?,
        options: List<Int>,
        tvOptions: List<Int> = listOf(),
        callback: (Pair<Boolean, Int>) -> Unit
    ) {
        if (this == null) return

        this.showOptionSelect(
            view,
            poster,
            options.map { this.getString(it) },
            tvOptions.map { this.getString(it) },
            callback
        )
    }

    fun Activity?.showOptionSelect(
        view: View?,
        poster: String?,
        options: List<String>,
        tvOptions: List<String> = listOf(),
        callback: (Pair<Boolean, Int>) -> Unit
    ) {
        if (this == null) return

        val request = SelectionDialogRequest(
            title = poster ?: "",
            items = options,
            selectedIndices = emptyList(),
            isMultiSelect = false,
            showApply = false,
            onSelected = { if (it.isNotEmpty()) callback(Pair(false, it.first())) },
            onDismiss = {}
        )
        _dialogRequests.tryEmit(request)
        try {
            onSelectionEvent(request)
        } catch (e: Throwable) {
            logError(e)
        }
        customDialogHandler?.invoke(request)
    }

    @PlatformQuarantine(
        reason = "Android View-binding dialog layout is replaced by Compose Desktop modal dialogs (CLAUDE.md 5.4)",
        upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/SingleSelectionHelper.kt:98",
        status = QuarantineStatus.NEEDS_DESKTOP_ALTERNATIVE
    )
    fun Activity?.showDialog(
        binding: Any?,
        dialog: Dialog?,
        items: List<String>,
        selectedIndex: List<Int>,
        name: String,
        showApply: Boolean,
        isMultiSelect: Boolean,
        callback: (List<Int>) -> Unit,
        dismissCallback: () -> Unit,
        itemLayout: Int = 0
    ) {
        if (this == null) return
        val request = SelectionDialogRequest(
            title = name,
            items = items,
            selectedIndices = selectedIndex,
            isMultiSelect = isMultiSelect,
            showApply = showApply,
            onSelected = {
                callback(it)
                dialog.dismissSafe(this)
            },
            onDismiss = {
                dismissCallback()
                dialog.dismissSafe(this)
            }
        )
        _dialogRequests.tryEmit(request)
        try {
            onSelectionEvent(request)
        } catch (e: Throwable) {
            logError(e)
        }
        customDialogHandler?.invoke(request)
    }

    fun Activity?.showMultiDialog(
        items: List<String>,
        selectedIndex: List<Int>,
        name: String,
        dismissCallback: () -> Unit,
        callback: (List<Int>) -> Unit,
    ) {
        if (this == null) return

        val request = SelectionDialogRequest(
            title = name,
            items = items,
            selectedIndices = selectedIndex,
            isMultiSelect = true,
            showApply = true,
            onSelected = callback,
            onDismiss = dismissCallback
        )
        _dialogRequests.tryEmit(request)
        try {
            onSelectionEvent(request)
        } catch (e: Throwable) {
            logError(e)
        }
        customDialogHandler?.invoke(request)
    }

    fun Activity?.showDialog(
        items: List<String>,
        selectedIndex: Int,
        name: String,
        showApply: Boolean,
        dismissCallback: () -> Unit,
        callback: (Int) -> Unit,
    ) {
        if (this == null) return

        val request = SelectionDialogRequest(
            title = name,
            items = items,
            selectedIndices = listOf(selectedIndex),
            isMultiSelect = false,
            showApply = showApply,
            onSelected = { if (it.isNotEmpty()) callback(it.first()) },
            onDismiss = dismissCallback
        )
        _dialogRequests.tryEmit(request)
        try {
            onSelectionEvent(request)
        } catch (e: Throwable) {
            logError(e)
        }
        customDialogHandler?.invoke(request)
    }

    /** Only for a low amount of items */
    fun Activity?.showBottomDialog(
        items: List<String>,
        selectedIndex: Int,
        name: String,
        showApply: Boolean,
        dismissCallback: () -> Unit,
        callback: (Int) -> Unit,
    ) {
        showDialog(items, selectedIndex, name, showApply, dismissCallback, callback)
    }

    fun Activity.showBottomDialogInstant(
        items: List<String>,
        name: String,
        dismissCallback: () -> Unit,
        callback: (Int) -> Unit,
    ): Any? {
        val request = SelectionDialogRequest(
            title = name,
            items = items,
            selectedIndices = emptyList(),
            isMultiSelect = false,
            showApply = false,
            onSelected = { if (it.isNotEmpty()) callback(it.first()) },
            onDismiss = dismissCallback
        )
        _dialogRequests.tryEmit(request)
        try {
            onSelectionEvent(request)
        } catch (e: Throwable) {
            logError(e)
        }
        customDialogHandler?.invoke(request)
        return null
    }

    fun Activity.showNginxTextInputDialog(
        name: String,
        value: String,
        textInputType: Int?,
        dismissCallback: () -> Unit,
        callback: (String) -> Unit,
    ) {
        val request = InputDialogRequest(
            title = name,
            initialValue = value,
            textInputType = textInputType,
            onSubmitted = callback,
            onDismiss = dismissCallback
        )
        _inputDialogRequests.tryEmit(request)
        try {
            onInputEvent(request)
        } catch (e: Throwable) {
            logError(e)
        }
        customInputHandler?.invoke(request)
    }

    fun Activity.showBottomDialogText(
        title: String,
        text: CharSequence,
        dismissCallback: () -> Unit
    ) {
        val request = TextDialogRequest(
            title = title,
            text = text,
            onDismiss = dismissCallback
        )
        _textDialogRequests.tryEmit(request)
        try {
            onTextEvent(request)
        } catch (e: Throwable) {
            logError(e)
        }
        customTextHandler?.invoke(request)
    }
}
