package com.example.flutternativeimage

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import android.util.Log

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Arrays
import java.util.HashMap

import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry.Registrar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.coroutines.CoroutineContext

/**
 * FlutterNativeImagePlugin
 */
class FlutterNativeImagePlugin private constructor(private val activity: Activity) : MethodCallHandler, CoroutineScope {
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "compressImage" -> compressImage(call, result)
            "getImageProperties" -> getImageProperties(call, result)
            "cropImage" -> cropImage(call, result)
            "getPlatformVersion" -> result.success("Android " + android.os.Build.VERSION.RELEASE)
            else -> result.notImplemented()
        }
    }

    private fun compressImage(call: MethodCall, result: Result) {
        val fileName = call.argument<String>("file")!!
        val resizePercentage = call.argument<Int>("percentage")!!
        var targetWidth = if (call.argument<Any>("targetWidth") == null) 0 else call.argument<Int>("targetWidth")!!
        var targetHeight = if (call.argument<Any>("targetHeight") == null) 0 else call.argument<Int>("targetHeight")!!
        val quality = call.argument<Int>("quality")!!

        val file = File(fileName)

        if (!file.exists()) {
            result.error("file does not exist", fileName, null)
            return
        }

        launch {
            val opts = BitmapFactory.Options()

            // load image bounds
            opts.inJustDecodeBounds = true
            BitmapFactory.decodeFile(fileName, opts)
            opts.inJustDecodeBounds = false

            if (targetHeight == 0)
                targetHeight = opts.outHeight / 100 * resizePercentage
            if (targetWidth == 0)
                targetWidth = opts.outWidth / 100 * resizePercentage

            // calculate appropriate sample size
            opts.inSampleSize = calculateInSampleSize(opts, targetWidth, targetHeight)

            var bmp = BitmapFactory.decodeFile(fileName, opts)
            val bos = ByteArrayOutputStream()

            bmp = Bitmap.createScaledBitmap(bmp, targetWidth, targetHeight, true)

            // reconfigure bitmap to use RGB_565 before compressing
            // fixes https://github.com/btastic/flutter_native_image/issues/47
            val newBmp = bmp.copy(Bitmap.Config.RGB_565, false)
            newBmp.compress(Bitmap.CompressFormat.JPEG, quality, bos)

            try {
                val outputFile = File.createTempFile(
                        getFilenameWithoutExtension(file) + "_compressed",
                        ".jpg",
                        activity.externalCacheDir
                )
                outputFile.deleteOnExit()
                val outputFileName = outputFile.path

                val outputStream = FileOutputStream(outputFileName)
                bos.writeTo(outputStream)

                copyExif(fileName, outputFileName)

                withContext(Dispatchers.Main) {
                    result.success(outputFileName)
                }
            } catch (e: FileNotFoundException) {
                Timber.e(e)
                withContext(Dispatchers.Main) {
                    result.error("file does not exist", fileName, null)
                }
            } catch (e: IOException) {
                Timber.e(e)
                withContext(Dispatchers.Main) {
                    result.error("something went wrong", fileName, null)
                }
            }
        }
    }

    private fun getImageProperties(call: MethodCall, result: Result) {
        val fileName = call.argument<String>("file")!!
        val file = File(fileName)

        if (!file.exists()) {
            result.error("file does not exist", fileName, null)
            return
        }

        launch {
            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            BitmapFactory.decodeFile(fileName, options)
            val properties = HashMap<String, Int>()
            properties["width"] = options.outWidth
            properties["height"] = options.outHeight

            var orientation = ExifInterface.ORIENTATION_UNDEFINED
            try {
                val exif = ExifInterface(fileName)
                orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
            } catch (ex: IOException) {
                // EXIF could not be read from the file; ignore
            }

            properties["orientation"] = orientation

            withContext(Dispatchers.Main) {
                result.success(properties)
            }
        }
    }

    private fun cropImage(call: MethodCall, result: Result) {
        // TODO this method should be refactored too

        val fileName = call.argument<String>("file")!!
        val originX = call.argument<Int>("originX")!!
        val originY = call.argument<Int>("originY")!!
        val width = call.argument<Int>("width")!!
        val height = call.argument<Int>("height")!!

        val file = File(fileName)

        if (!file.exists()) {
            result.error("file does not exist", fileName, null)
            return
        }

        var bmp = BitmapFactory.decodeFile(fileName)
        val bos = ByteArrayOutputStream()

        try {
            bmp = Bitmap.createBitmap(bmp, originX, originY, width, height)
        } catch (e: IllegalArgumentException) {
            e.printStackTrace()
            result.error("bounds are outside of the dimensions of the source image", fileName, null)
        }

        bmp.compress(Bitmap.CompressFormat.JPEG, 100, bos)
        bmp.recycle()
        var outputStream: OutputStream? = null
        try {
            val outputFileName = File.createTempFile(
                    getFilenameWithoutExtension(file) + "_cropped",
                    ".jpg",
                    activity.externalCacheDir
            ).path


            outputStream = FileOutputStream(outputFileName)
            bos.writeTo(outputStream)

            copyExif(fileName, outputFileName)

            result.success(outputFileName)
        } catch (e: FileNotFoundException) {
            e.printStackTrace()
            result.error("file does not exist", fileName, null)
        } catch (e: IOException) {
            e.printStackTrace()
            result.error("something went wrong", fileName, null)
        } finally {
            if (outputStream != null) {
                try {
                    outputStream.close()
                } catch (e: IOException) {
                    e.printStackTrace()
                }

            }
        }
    }

    private fun copyExif(filePathOri: String, filePathDest: String) {
        try {
            val oldExif = ExifInterface(filePathOri)
            val newExif = ExifInterface(filePathDest)

            val attributes = listOf(
                    "FNumber",
                    "ExposureTime",
                    "ISOSpeedRatings",
                    "GPSAltitude",
                    "GPSAltitudeRef",
                    "FocalLength",
                    "GPSDateStamp",
                    "WhiteBalance",
                    "GPSProcessingMethod",
                    "GPSTimeStamp",
                    "DateTime",
                    "Flash",
                    "GPSLatitude",
                    "GPSLatitudeRef",
                    "GPSLongitude",
                    "GPSLongitudeRef",
                    "Make",
                    "Model",
                    "Orientation"
            )
            for (attribute in attributes) {
                setIfNotNull(oldExif, newExif, attribute)
            }

            newExif.saveAttributes()
        } catch (e: Exception) {
            Timber.e(e, "Error preserving Exif data on selected image")
        }
    }

    private fun setIfNotNull(oldExif: ExifInterface, newExif: ExifInterface, property: String) {
        if (oldExif.getAttribute(property) != null) {
            newExif.setAttribute(property, oldExif.getAttribute(property))
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        // Raw height and width of image
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {

            val halfHeight = height / 2
            val halfWidth = width / 2

            // Calculate the largest inSampleSize value that is a power of 2 and
            // keeps both height and width larger than the requested height and width.
            while (halfHeight / inSampleSize > reqHeight && halfWidth / inSampleSize > reqWidth) {
                inSampleSize *= 2
            }
        }

        return inSampleSize
    }

    private fun getFilenameWithoutExtension(file: File): String {
        val fileName = file.name

        return if (fileName.indexOf(".") > 0) {
            fileName.substring(0, fileName.lastIndexOf("."))
        } else {
            fileName
        }
    }

    companion object {
        /**
         * Plugin registration.
         */
        @JvmStatic
        fun registerWith(registrar: Registrar) {
            val channel = MethodChannel(registrar.messenger(), "flutter_native_image")
            channel.setMethodCallHandler(FlutterNativeImagePlugin(registrar.activity()))
        }
    }
}
