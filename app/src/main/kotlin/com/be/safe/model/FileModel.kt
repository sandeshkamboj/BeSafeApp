package com.be.safe.model

import kotlinx.serialization.Serializable

@Serializable
data class FileModel(
    val id: Int? = null,
    val user_id: String,
    val type: String,
    val path: String,
    val bucket: String,
    val created_at: String
)