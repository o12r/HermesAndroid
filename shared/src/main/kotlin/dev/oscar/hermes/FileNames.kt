package dev.oscar.hermes

fun isImageName(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic")
