package com.deepstudent.services.sync.data.apply

import com.deepstudent.platform.database.SqlOperations

/** A domain-specific owner can use the same transaction as the batch receipt. */
interface IncomingSpecialRowHandler {
    fun validate(rows: List<ByteArray>)
    fun apply(sql: SqlOperations, rows: List<ByteArray>)
}
