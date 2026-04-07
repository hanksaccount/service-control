package com.litman.servicecontrol

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class TermuxResultReceiver(
    private val onResult: (stdout: String, stderr: String, exitCode: Int) -> Unit
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Termux skickar resultatet antingen direkt eller i en "result"-bundle
        val bundle = intent.getBundleExtra("result")
        val stdout = bundle?.getString("stdout") ?: intent.getStringExtra("stdout") ?: ""
        val stderr = bundle?.getString("stderr") ?: intent.getStringExtra("stderr") ?: ""
        val exitCode = bundle?.getInt("exit_code", -1) ?: intent.getIntExtra("exit_code", -1)
        onResult(stdout, stderr, exitCode)
    }
}
