package com.oreilly.hellokotlin

import android.Manifest
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.webkit.*
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.example.advancedwebview.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    
    // For File Uploads
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var cameraImageUri: Uri? = null

    // Permission Launcher
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Handle specific permission results if needed
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 1. Install Splash Screen (Must be before setContentView)
        installSplashScreen()
        
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupPermissions()
        setupWebView()
        setupUI()
        setupBackNavigation()
    }

    private fun setupPermissions() {
        val permissions = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.WRITE_EXTERNAL_STORAGE // For older Android versions
        )
        requestPermissionLauncher.launch(permissions)
    }

    private fun setupUI() {
        // Load button logic
        binding.btnLoad.setOnClickListener {
            loadUrlFromInput()
        }

        // Allow loading by pressing "Enter" on keyboard
        binding.etUrl.setOnEditorActionListener { _, _, _ ->
            loadUrlFromInput()
            true
        }
    }

    private fun loadUrlFromInput() {
        var url = binding.etUrl.text.toString().trim()
        if (url.isNotEmpty()) {
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://$url"
            }
            binding.webView.loadUrl(url)
            // Hide keyboard logic could be added here
        }
    }

    private fun setupWebView() {
        binding.webView.apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                databaseEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                builtInZoomControls = true
                displayZoomControls = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                
                // User Agent to look like a real browser (optional)
                userAgentString = userAgentString.replace("; wv", "")
            }

            // Handle Downloads
            setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
                val request = DownloadManager.Request(Uri.parse(url))
                request.setMimeType(mimetype)
                request.addRequestHeader("cookie", CookieManager.getInstance().getCookie(url))
                request.addRequestHeader("User-Agent", userAgent)
                request.setDescription("Downloading file...")
                request.setTitle(URLUtil.guessFileName(url, contentDisposition, mimetype))
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(url, contentDisposition, mimetype))
                
                val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)
                Toast.makeText(context, "Download Started", Toast.LENGTH_SHORT).show()
            }

            // WebViewClient: Handles navigation inside the WebView
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url.toString()
                    
                    // Open external apps (Mail, Tel, WhatsApp, Maps)
                    if (url.startsWith("mailto:") || url.startsWith("tel:") || url.startsWith("geo:")) {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        return true
                    }
                    
                    // Keep standard HTTP links inside WebView
                    return false
                }
            }

            // WebChromeClient: Handles UI elements (Titles, Alerts, File Chooser, Geolocation)
            webChromeClient = object : WebChromeClient() {
                
                // Handle Geolocation
                override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                    // Check if we have permission, then grant
                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                        callback.invoke(origin, true, false)
                    } else {
                        callback.invoke(origin, false, false)
                    }
                }

                // Handle File Upload (<input type="file">)
                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    // Cancel previous callback if it exists
                    fileUploadCallback?.onReceiveValue(null)
                    fileUploadCallback = filePathCallback

                    val intentList: MutableList<Intent> = ArrayList()

                    // 1. Camera Intent
                    var takePictureIntent: Intent? = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                    if (takePictureIntent?.resolveActivity(packageManager) != null) {
                        var photoFile: File? = null
                        try {
                            photoFile = createImageFile()
                            takePictureIntent.putExtra("PhotoPath", cameraImageUri.toString())
                        } catch (ex: Exception) {
                            // Error occurred
                        }

                        if (photoFile != null) {
                            cameraImageUri = FileProvider.getUriForFile(
                                this@MainActivity,
                                "${applicationContext.packageName}.provider",
                                photoFile
                            )
                            takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri)
                        } else {
                            takePictureIntent = null
                        }
                    }

                    // 2. Gallery Intent
                    val contentSelectionIntent = Intent(Intent.ACTION_GET_CONTENT)
                    contentSelectionIntent.addCategory(Intent.CATEGORY_OPENABLE)
                    contentSelectionIntent.type = "*/*"

                    // Combine intents
                    if (takePictureIntent != null) {
                        intentList.add(takePictureIntent)
                    }
                    
                    val chooserIntent = Intent(Intent.ACTION_CHOOSER)
                    chooserIntent.putExtra(Intent.EXTRA_INTENT, contentSelectionIntent)
                    chooserIntent.putExtra(Intent.EXTRA_TITLE, "Image Chooser")
                    chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, intentList.toTypedArray())

                    try {
                        fileUploadLauncher.launch(chooserIntent)
                    } catch (e: ActivityNotFoundException) {
                        fileUploadCallback = null
                        return false
                    }

                    return true
                }
            }
        }
        
        // Load default URL
        binding.webView.loadUrl("https://www.google.com")
    }

    // Helper to create temporary image file
    private fun createImageFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val imageFileName = "JPEG_" + timeStamp + "_"
        val storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile(imageFileName, ".jpg", storageDir)
    }

    // Activity Result Launcher for File Uploads
    private val fileUploadLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (fileUploadCallback == null) return@registerForActivityResult

        val resultCode = result.resultCode
        val data = result.data

        var results: Array<Uri>? = null

        if (resultCode == RESULT_OK) {
            if (data == null || data.data == null) {
                // If no data, it might be the camera intent
                if (cameraImageUri != null) {
                    results = arrayOf(cameraImageUri!!)
                }
            } else {
                // It is the gallery intent
                val dataString = data.dataString
                if (dataString != null) {
                    results = arrayOf(Uri.parse(dataString))
                }
            }
        }
        
        fileUploadCallback?.onReceiveValue(results)
        fileUploadCallback = null
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }
}
