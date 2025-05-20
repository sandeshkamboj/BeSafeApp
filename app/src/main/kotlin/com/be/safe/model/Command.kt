package com.be.safe.model

import kotlinx.serialization.Serializable

@Serializable
data class Command(
    val id: Int? = null,
    val user_id: String,
    val type: String,
    val options: Options
)

@Serializable
data class Options(
    val camera: String? = null,
    val flash: String? = null,
    val quality: String? = null,
    val duration: Int? = null
)