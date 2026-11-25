package com.example.replybubble

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.replybubble.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.etUrl.setText(Prefs.apiUrl(this))
        binding.etModel.setText(Prefs.model(this))
        binding.etKey.setText(Prefs.apiKey(this))
        binding.etPersona.setText(Prefs.persona(this))

        binding.btnSave.setOnClickListener {
            Prefs.setApiUrl(this, binding.etUrl.text.toString().trim())
            Prefs.setModel(this, binding.etModel.text.toString().trim().ifBlank { Prefs.DEFAULT_MODEL })
            Prefs.setApiKey(this, binding.etKey.text.toString().trim())
            Prefs.setPersona(this, binding.etPersona.text.toString().trim())
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
