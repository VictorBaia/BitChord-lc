package com.music.bitchord.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.music.bitchord.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun ProfilePhotoCropDialog(
    source: Uri,
    onDismiss: () -> Unit,
    onSaved: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bitmap by produceState<Bitmap?>(initialValue = null, source) {
        value = withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, source)) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else {
                context.contentResolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it) }
            }
        }
    }
    var zoom by remember(source) { mutableFloatStateOf(1f) }
    var offset by remember(source) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var saving by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = { if (!saving) onDismiss() }) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(stringResource(R.string.adjust_profile_photo), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.profile_photo_crop_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(18.dp))
                        .onSizeChanged { viewport = it }
                        .pointerInput(bitmap, viewport) {
                            detectTransformGestures { _, pan, gestureZoom, _ ->
                                val image = bitmap ?: return@detectTransformGestures
                                val nextZoom = (zoom * gestureZoom).coerceIn(1f, 5f)
                                val base = max(
                                    viewport.width.toFloat() / image.width,
                                    viewport.height.toFloat() / image.height,
                                )
                                val maxX = ((image.width * base * nextZoom) - viewport.width).coerceAtLeast(0f) / 2f
                                val maxY = ((image.height * base * nextZoom) - viewport.height).coerceAtLeast(0f) / 2f
                                zoom = nextZoom
                                offset = Offset(
                                    (offset.x + pan.x).coerceIn(-maxX, maxX),
                                    (offset.y + pan.y).coerceIn(-maxY, maxY),
                                )
                            }
                        },
                ) {
                    bitmap?.let { image ->
                        val preview = remember(image) { image.asImageBitmap() }
                        Canvas(Modifier.matchParentSize()) {
                            val base = max(size.width / image.width, size.height / image.height)
                            val scale = base * zoom
                            val destinationWidth = (image.width * scale).roundToInt()
                            val destinationHeight = (image.height * scale).roundToInt()
                            drawImage(
                                image = preview,
                                srcOffset = IntOffset.Zero,
                                srcSize = IntSize(image.width, image.height),
                                dstOffset = IntOffset(
                                    ((size.width - destinationWidth) / 2f + offset.x).roundToInt(),
                                    ((size.height - destinationHeight) / 2f + offset.y).roundToInt(),
                                ),
                                dstSize = IntSize(destinationWidth, destinationHeight),
                            )
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.cancel)) }
                    Button(
                        enabled = bitmap != null && viewport.width > 0 && viewport.height > 0 && !saving,
                        onClick = {
                            val image = bitmap ?: return@Button
                            saving = true
                            scope.launch {
                                val uri = withContext(Dispatchers.IO) {
                                    val base = max(viewport.width.toFloat() / image.width, viewport.height.toFloat() / image.height)
                                    val side = (min(viewport.width, viewport.height) / (base * zoom))
                                        .coerceIn(1f, min(image.width, image.height).toFloat())
                                    val centerX = image.width / 2f - offset.x / (base * zoom)
                                    val centerY = image.height / 2f - offset.y / (base * zoom)
                                    val left = (centerX - side / 2f).coerceIn(0f, image.width - side)
                                    val top = (centerY - side / 2f).coerceIn(0f, image.height - side)
                                    val cropped = Bitmap.createBitmap(image, left.toInt(), top.toInt(), side.toInt(), side.toInt())
                                    val output = Bitmap.createScaledBitmap(cropped, 1024, 1024, true)
                                    val directory = File(context.filesDir, "profile").apply { mkdirs() }
                                    val file = File(directory, "navidrome-user.jpg")
                                    FileOutputStream(file).use { output.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                                    if (output !== cropped) output.recycle()
                                    cropped.recycle()
                                    Uri.fromFile(file).toString()
                                }
                                onSaved(uri)
                            }
                        },
                    ) { Text(stringResource(R.string.save)) }
                }
            }
        }
    }
}
