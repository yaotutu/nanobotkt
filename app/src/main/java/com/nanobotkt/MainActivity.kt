package com.nanobotkt

import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private val appViewModel: AppViewModel by viewModels()
    private lateinit var connectivityManager: ConnectivityManager
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = appViewModel.setNetworkAvailable(true)
        override fun onLost(network: Network) {
            appViewModel.setNetworkAvailable(connectivityManager.activeNetwork != null)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        appViewModel.setNetworkAvailable(connectivityManager.activeNetwork != null)
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
        setContent { NanobotRoot(appViewModel) }
    }

    override fun onStart() {
        super.onStart()
        appViewModel.onForeground()
    }

    override fun onStop() {
        appViewModel.onBackground()
        super.onStop()
    }

    override fun onDestroy() {
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        super.onDestroy()
    }
}


