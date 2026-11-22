package com.deepstudent.features.cards.api

fun interface IncomingDeckSync {
    fun apply(changes: List<ByteArray>)
}
