package com.example.evidencevault.domain

import java.io.File

enum class IntegrityStatus { OK, MODIFIED, UNVERIFIED }

data class Evidence(
    val file: File,
    val title: String,
    val dateText: String,
    val durationText: String, // Changed to match the new UI
    val integrity: IntegrityStatus
)
