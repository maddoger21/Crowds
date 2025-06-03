package com.example.crowds

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.firebase.ui.auth.AuthUI
import com.firebase.ui.auth.FirebaseAuthUIActivityResultContract
import com.firebase.ui.auth.data.model.FirebaseAuthUIAuthenticationResult
import com.google.firebase.auth.FirebaseAuth

import android.widget.Button
import android.widget.Toast

class SignInActivity : AppCompatActivity() {

    // Регистрируем callback для FirebaseUI Auth
    private val signInLauncher =
        registerForActivityResult(FirebaseAuthUIActivityResultContract()) { res ->
            onSignInResult(res)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sign_in)

        // Если уже залогинен, сразу переходим в MainActivity
        if (FirebaseAuth.getInstance().currentUser != null) {
            goToMain()
            return
        }

        // Настраиваем поставщиков — только Google
        val providers = arrayListOf(
            AuthUI.IdpConfig.GoogleBuilder().build()
        )

        // Кнопка, запускающая FirebaseUI
        findViewById<Button>(R.id.btn_google_sign_in).setOnClickListener {
            val signInIntent = AuthUI.getInstance()
                .createSignInIntentBuilder()
                .setAvailableProviders(providers)
                // Можно добавить стиль, логотип:
                //.setIsSmartLockEnabled(false)
                .setLogo(R.mipmap.ic_launcher)
                .build()
            signInLauncher.launch(signInIntent)
        }
    }

    private fun onSignInResult(result: FirebaseAuthUIAuthenticationResult) {
        val response = result.idpResponse
        if (result.resultCode == RESULT_OK) {
            // Успешная аутентификация
            goToMain()
        } else {
            // Неудача (можно вывести Toast или логику повторного показа)
            // Например:
            Toast.makeText(this, "Вход не выполнен", Toast.LENGTH_SHORT).show()
        }
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}