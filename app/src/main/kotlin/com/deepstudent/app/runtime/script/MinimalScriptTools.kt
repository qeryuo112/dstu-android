package com.deepstudent.app.runtime.script

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.script.api.ScriptToolPort
import com.deepstudent.services.script.api.ScriptToolResult
import com.deepstudent.services.script.backend.ScriptAuthorization
import com.deepstudent.services.script.backend.ScriptToolAuthorizer
import com.deepstudent.services.script.backend.ScriptToolPolicy

/** No I/O or privileged capability is exposed by this S1 vertical slice. */
internal object MinimalScriptTools : ScriptToolPort {
    val policy = ScriptToolPolicy(setOf("builtin-script_echo", "builtin-script_add"), ScriptToolAuthorizer { request ->
        if (request.tool in setOf("builtin-script_echo", "builtin-script_add")) ScriptAuthorization.Allowed
        else ScriptAuthorization.Denied("NOT_REGISTERED", "tool is not registered")
    })

    override fun call(tool: String, arguments: JsonValue): ScriptToolResult = when (tool) {
        "builtin-script_echo" -> ScriptToolResult.Success(arguments)
        "builtin-script_add" -> add(arguments)
        else -> ScriptToolResult.Failure("NOT_REGISTERED", "tool is not registered")
    }

    private fun add(arguments: JsonValue): ScriptToolResult {
        val objectValue = arguments as? JsonValue.Object ?: return invalid()
        val left = (objectValue["a"] as? JsonValue.Number)?.asLongOrNull() ?: return invalid()
        val right = (objectValue["b"] as? JsonValue.Number)?.asLongOrNull() ?: return invalid()
        return try { ScriptToolResult.Success(JsonValue.obj("sum" to JsonValue.integer(Math.addExact(left, right)))) }
        catch (_: ArithmeticException) { invalid() }
    }

    private fun invalid() = ScriptToolResult.Failure("INVALID_ARGUMENTS", "expected two bounded integers")
}
