/*
 * Copyright 2026 TerraNet Technologies LLC
 * Licensed under the Apache License, Version 2.0.
 */

package com.terranet.echosense.android.ui.echosense

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.media.Image
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.UnavailableException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max

/**
 * Dedicated ARCore preview used while Safety is enabled. ARCore must own the camera to generate
 * Depth API frames, so this view replaces CameraX temporarily and also supplies RGB frames for
 * EchoSense object detection and one-shot multimodal analysis.
 */
class ARCoreDepthCameraView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs), GLSurfaceView.Renderer, DefaultLifecycleObserver {

  var onDepthAvailabilityChanged: ((Boolean, String?) -> Unit)? = null
  var onDepthObservations: ((List<DepthObservation>) -> Unit)? = null
  var onCameraFrame: ((Bitmap) -> Unit)? = null

  private var lifecycleOwner: LifecycleOwner? = null
  private var session: Session? = null
  private var cameraTextureId = 0
  private var shaderProgram = 0
  private var viewWidth = 1
  private var viewHeight = 1
  private var lastDisplayRotation = -1
  private var lastDepthAtMs = 0L
  private var lastRgbAtMs = 0L
  private var isSessionResumed = false
  @Volatile private var safetyProcessingEnabled = false
  private val frameConversionInFlight = AtomicBoolean(false)
  private val latestFrameLock = Any()
  private var latestFrame: Bitmap? = null
  private val delayedStart = Runnable {
    if (lifecycleOwner != null) start()
  }

  private val quadVertices = createFloatBuffer(
    -1f, -1f,
     1f, -1f,
    -1f,  1f,
     1f,  1f,
  )
  private val transformedTextureCoordinates = createFloatBuffer(*FloatArray(8))

  init {
    setEGLContextClientVersion(2)
    preserveEGLContextOnPause = true
    setRenderer(this)
    renderMode = RENDERMODE_CONTINUOUSLY
  }

  fun attach(owner: LifecycleOwner) {
    if (lifecycleOwner === owner) return
    lifecycleOwner?.lifecycle?.removeObserver(this)
    lifecycleOwner = owner
    owner.lifecycle.addObserver(this)
    if (owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
      postDelayed(delayedStart, 300L)
    }
  }

  fun detach() {
    removeCallbacks(delayedStart)
    lifecycleOwner?.lifecycle?.removeObserver(this)
    lifecycleOwner = null
    safetyProcessingEnabled = false
    onDepthAvailabilityChanged = null
    onDepthObservations = null
    onCameraFrame = null
    stop()
    try { session?.close() } catch (_: Exception) {}
    session = null
    synchronized(latestFrameLock) {
      latestFrame?.recycle()
      latestFrame = null
    }
  }

  fun captureCurrentFrame(): Bitmap? = synchronized(latestFrameLock) {
    latestFrame?.copy(Bitmap.Config.ARGB_8888, false)
  }

  fun setSafetyProcessingEnabled(enabled: Boolean) {
    safetyProcessingEnabled = enabled
  }

  override fun onResume(owner: LifecycleOwner) = start()

  override fun onPause(owner: LifecycleOwner) = stop()

  private fun start() {
    if (isSessionResumed) return
    if (session == null) {
      val activity = context.findActivity()
      try {
        val installStatus = if (activity != null) {
          ArCoreApk.getInstance().requestInstall(activity, true)
        } else {
          ArCoreApk.InstallStatus.INSTALLED
        }
        if (installStatus == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
          notifyUnavailable("Google Play Services for AR installation requested")
          return
        }
        val newSession = Session(context)
        val config = newSession.config.apply {
          updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
          planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
        }
        val depthSupported = newSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
        if (depthSupported) config.depthMode = Config.DepthMode.AUTOMATIC
        newSession.configure(config)
        session = newSession
        if (cameraTextureId != 0) newSession.setCameraTextureName(cameraTextureId)
        post { onDepthAvailabilityChanged?.invoke(depthSupported, if (depthSupported) null else "Depth API is not supported") }
        if (!depthSupported) {
          newSession.close()
          session = null
          return
        }
      } catch (error: UnavailableException) {
        notifyUnavailable(error.message ?: error.javaClass.simpleName)
        return
      } catch (error: Exception) {
        notifyUnavailable(error.message ?: error.javaClass.simpleName)
        return
      }
    }

    try {
      session?.resume()
      super<GLSurfaceView>.onResume()
      isSessionResumed = true
    } catch (error: CameraNotAvailableException) {
      notifyUnavailable("Camera is busy")
    }
  }

  private fun stop() {
    if (!isSessionResumed) return
    try { super<GLSurfaceView>.onPause() } catch (_: Exception) {}
    try { session?.pause() } catch (_: Exception) {}
    isSessionResumed = false
  }

  private fun notifyUnavailable(reason: String) {
    Log.i(TAG, "ARCore depth unavailable: $reason")
    post { onDepthAvailabilityChanged?.invoke(false, reason) }
  }

  override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
    GLES20.glClearColor(0f, 0f, 0f, 1f)
    cameraTextureId = createExternalTexture()
    shaderProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
    session?.setCameraTextureName(cameraTextureId)
  }

  override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
    viewWidth = max(1, width)
    viewHeight = max(1, height)
    GLES20.glViewport(0, 0, width, height)
    lastDisplayRotation = -1
  }

  override fun onDrawFrame(gl: GL10?) {
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
    val activeSession = session ?: return
    if (cameraTextureId == 0) return

    try {
      updateDisplayGeometry(activeSession)
      val frame = activeSession.update()
      if (frame.timestamp == 0L) return
      drawCamera(frame)
      val now = SystemClock.elapsedRealtime()
      if (safetyProcessingEnabled && now - lastDepthAtMs >= DEPTH_INTERVAL_MS) {
        lastDepthAtMs = now
        acquireDepth(frame)?.let { observations ->
          post { onDepthObservations?.invoke(observations) }
        }
      }
      if (now - lastRgbAtMs >= RGB_INTERVAL_MS && frameConversionInFlight.compareAndSet(false, true)) {
        lastRgbAtMs = now
        acquireCameraBitmap(frame)
      }
    } catch (_: CameraNotAvailableException) {
      notifyUnavailable("Camera became unavailable")
    } catch (error: Exception) {
      Log.w(TAG, "ARCore frame failed: ${error.message}")
    }
  }

  private fun updateDisplayGeometry(activeSession: Session) {
    val rotation = @Suppress("DEPRECATION")
      (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
    if (rotation != lastDisplayRotation) {
      activeSession.setDisplayGeometry(rotation, viewWidth, viewHeight)
      lastDisplayRotation = rotation
    }
  }

  private fun drawCamera(frame: Frame) {
    quadVertices.position(0)
    transformedTextureCoordinates.position(0)
    frame.transformCoordinates2d(
      Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
      quadVertices,
      Coordinates2d.TEXTURE_NORMALIZED,
      transformedTextureCoordinates,
    )
    transformedTextureCoordinates.position(0)
    quadVertices.position(0)

    GLES20.glUseProgram(shaderProgram)
    val position = GLES20.glGetAttribLocation(shaderProgram, "a_Position")
    val texCoord = GLES20.glGetAttribLocation(shaderProgram, "a_TexCoord")
    GLES20.glEnableVertexAttribArray(position)
    GLES20.glEnableVertexAttribArray(texCoord)
    GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, quadVertices)
    GLES20.glVertexAttribPointer(texCoord, 2, GLES20.GL_FLOAT, false, 0, transformedTextureCoordinates)
    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
    GLES20.glUniform1i(GLES20.glGetUniformLocation(shaderProgram, "u_Texture"), 0)
    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    GLES20.glDisableVertexAttribArray(position)
    GLES20.glDisableVertexAttribArray(texCoord)
  }

  private fun acquireDepth(frame: Frame): List<DepthObservation>? {
    val image = try {
      frame.acquireDepthImage16Bits()
    } catch (_: NotYetAvailableException) {
      return null
    }
    image.use { depth ->
      val imageDimensions = frame.camera.imageIntrinsics.imageDimensions
      val plane = depth.planes[0]
      val bearings = listOf(
        Bearing.LEFT to (0.08f..0.36f),
        Bearing.CENTER to (0.36f..0.64f),
        Bearing.RIGHT to (0.64f..0.92f),
      )
      return bearings.mapNotNull { (bearing, xRange) ->
        val viewPoints = FloatArray(GRID_SIZE * GRID_SIZE * 2)
        var offset = 0
        repeat(GRID_SIZE) { row ->
          repeat(GRID_SIZE) { column ->
            viewPoints[offset++] = xRange.start + (xRange.endInclusive - xRange.start) *
              (column + 0.5f) / GRID_SIZE
            viewPoints[offset++] = 0.22f + 0.56f * (row + 0.5f) / GRID_SIZE
          }
        }
        val imagePoints = FloatArray(viewPoints.size)
        frame.transformCoordinates2d(
          Coordinates2d.VIEW_NORMALIZED,
          viewPoints,
          Coordinates2d.IMAGE_PIXELS,
          imagePoints,
        )
        val samples = mutableListOf<Float>()
        val buffer = plane.buffer.duplicate().order(ByteOrder.nativeOrder())
        for (index in imagePoints.indices step 2) {
          val cameraX = imagePoints[index]
          val cameraY = imagePoints[index + 1]
          val depthX = (cameraX / imageDimensions[0] * depth.width).toInt().coerceIn(0, depth.width - 1)
          val depthY = (cameraY / imageDimensions[1] * depth.height).toInt().coerceIn(0, depth.height - 1)
          val byteOffset = depthY * plane.rowStride + depthX * plane.pixelStride
          if (byteOffset < 0 || byteOffset + 1 >= buffer.limit()) continue
          val millimeters = buffer.getShort(byteOffset).toInt() and 0xFFFF
          if (millimeters in 200..8000) samples += millimeters / 1000f
        }
        if (samples.size < 12) return@mapNotNull null
        samples.sort()
        val distance = samples[(samples.size * 0.18f).toInt().coerceIn(0, samples.lastIndex)]
        if (distance > 3.5f) return@mapNotNull null
        val lower = samples[(samples.lastIndex * 0.25f).toInt()]
        val upper = samples[(samples.lastIndex * 0.75f).toInt()]
        val coverage = samples.size.toFloat() / (GRID_SIZE * GRID_SIZE)
        val wallHit = frame.hitTest(viewWidth * xRange.center(), viewHeight * 0.50f)
          .firstOrNull { result ->
            val planeTrackable = result.trackable as? Plane
            planeTrackable?.trackingState == TrackingState.TRACKING &&
              planeTrackable.type == Plane.Type.VERTICAL &&
              planeTrackable.isPoseInPolygon(result.hitPose)
          }
        val wallLike = wallHit != null || (coverage >= 0.65f && upper - lower <= 0.28f)
        DepthObservation(
          bearing = bearing,
          distanceMeters = distance,
          confidence = coverage.coerceIn(0f, 1f),
          surface = if (wallLike) DepthSurface.WALL else DepthSurface.UNKNOWN,
        )
      }
    }
  }

  private fun acquireCameraBitmap(frame: Frame) {
    try {
      frame.acquireCameraImage().use { image ->
        val converted = image.toBitmap()
        val rotated = converted.rotated(displayRotationDegrees())
        synchronized(latestFrameLock) {
          latestFrame?.recycle()
          latestFrame = rotated
        }
        if (safetyProcessingEnabled) {
          onCameraFrame?.invoke(rotated.copy(Bitmap.Config.ARGB_8888, false))
        }
      }
    } catch (_: NotYetAvailableException) {
      // ARCore needs a few frames before the CPU image is available.
    } catch (error: Exception) {
      Log.w(TAG, "Unable to acquire ARCore camera image: ${error.message}")
    } finally {
      frameConversionInFlight.set(false)
    }
  }

  private fun displayRotationDegrees(): Int {
    val rotation = @Suppress("DEPRECATION")
      (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
    return when (rotation) {
      Surface.ROTATION_90 -> 0
      Surface.ROTATION_180 -> 270
      Surface.ROTATION_270 -> 180
      else -> 90
    }
  }

  companion object {
    private const val TAG = "ARCoreDepthCamera"
    private const val GRID_SIZE = 9
    private const val DEPTH_INTERVAL_MS = 120L
    private const val RGB_INTERVAL_MS = 220L

    private const val VERTEX_SHADER = """
      attribute vec4 a_Position;
      attribute vec2 a_TexCoord;
      varying vec2 v_TexCoord;
      void main() {
        gl_Position = a_Position;
        v_TexCoord = a_TexCoord;
      }
    """
    private const val FRAGMENT_SHADER = """
      #extension GL_OES_EGL_image_external : require
      precision mediump float;
      uniform samplerExternalOES u_Texture;
      varying vec2 v_TexCoord;
      void main() {
        gl_FragColor = texture2D(u_Texture, v_TexCoord);
      }
    """

    private fun createFloatBuffer(vararg values: Float): FloatBuffer = ByteBuffer
      .allocateDirect(values.size * Float.SIZE_BYTES)
      .order(ByteOrder.nativeOrder())
      .asFloatBuffer()
      .apply { put(values); position(0) }

    private fun createExternalTexture(): Int {
      val textures = IntArray(1)
      GLES20.glGenTextures(1, textures, 0)
      GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0])
      GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
      GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
      GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
      GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
      return textures[0]
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
      fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        return shader
      }
      return GLES20.glCreateProgram().also { program ->
        GLES20.glAttachShader(program, compile(GLES20.GL_VERTEX_SHADER, vertexSource))
        GLES20.glAttachShader(program, compile(GLES20.GL_FRAGMENT_SHADER, fragmentSource))
        GLES20.glLinkProgram(program)
      }
    }
  }
}

private fun ClosedFloatingPointRange<Float>.center(): Float = (start + endInclusive) / 2f

private fun Context.findActivity(): Activity? {
  var current = this
  while (current is ContextWrapper) {
    if (current is Activity) return current
    current = current.baseContext
  }
  return current as? Activity
}

private fun Image.toBitmap(): Bitmap {
  val width = width
  val height = height
  val pixels = IntArray(width * height)
  val yPlane = planes[0]
  val uPlane = planes[1]
  val vPlane = planes[2]
  val yBuffer = yPlane.buffer
  val uBuffer = uPlane.buffer
  val vBuffer = vPlane.buffer
  for (y in 0 until height) {
    for (x in 0 until width) {
      val yValue = yBuffer.get(y * yPlane.rowStride + x * yPlane.pixelStride).toInt() and 0xFF
      val uvX = x / 2
      val uvY = y / 2
      val uValue = (uBuffer.get(uvY * uPlane.rowStride + uvX * uPlane.pixelStride).toInt() and 0xFF) - 128
      val vValue = (vBuffer.get(uvY * vPlane.rowStride + uvX * vPlane.pixelStride).toInt() and 0xFF) - 128
      val r = (yValue + 1.370705f * vValue).toInt().coerceIn(0, 255)
      val g = (yValue - 0.337633f * uValue - 0.698001f * vValue).toInt().coerceIn(0, 255)
      val b = (yValue + 1.732446f * uValue).toInt().coerceIn(0, 255)
      pixels[y * width + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
  }
  return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}

private fun Bitmap.rotated(degrees: Int): Bitmap {
  if (degrees == 0) return this
  val matrix = android.graphics.Matrix().apply { postRotate(degrees.toFloat()) }
  val result = Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
  if (result !== this) recycle()
  return result
}
