package android.app

import android.graphics.drawable.Icon

class RemoteAction(
    val icon: Icon,
    val title: CharSequence,
    val contentDescription: CharSequence,
    val actionIntent: PendingIntent
)
