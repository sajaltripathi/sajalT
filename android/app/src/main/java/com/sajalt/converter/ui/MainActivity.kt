package com.sajalt.converter.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.sajalt.converter.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.cardImageToPdf.setOnClickListener { startActivity(Intent(this, ImageToPdfActivity::class.java)) }
        binding.cardDocxToPdf.setOnClickListener { startActivity(Intent(this, DocxToPdfActivity::class.java)) }
        binding.cardPdfToDocx.setOnClickListener { startActivity(Intent(this, PdfToDocxActivity::class.java)) }
        binding.cardPdfCompress.setOnClickListener { startActivity(Intent(this, PdfCompressActivity::class.java)) }
        binding.cardImageCompress.setOnClickListener { startActivity(Intent(this, ImageCompressActivity::class.java)) }
        binding.cardOcr.setOnClickListener { startActivity(Intent(this, OcrActivity::class.java)) }
    }
}
