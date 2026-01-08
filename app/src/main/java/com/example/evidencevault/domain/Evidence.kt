package com.example.evidencevault.domain

import java.io.File

data class Evidence(
    val file: File,
    val title: String,
    val dateText: String,
    val durationText: String
)
