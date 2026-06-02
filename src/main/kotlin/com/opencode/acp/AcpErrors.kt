package com.opencode.acp

/**
 * OpenCode bridge errors — codes -32100 to -32199.
 * ACP protocol errors and JSON-RPC errors are handled by the SDK.
 */

class OpenCodeConnectionFailed(host: String, port: Int, cause: Throwable) :
    Exception("Cannot connect to OpenCode engine at $host:$port: ${cause.message}", cause)

class OpenCodeApiError(val statusCode: Int, val body: String) :
    Exception("OpenCode API error $statusCode: $body")

class OpenCodeTimeout(timeoutMs: Long) :
    Exception("OpenCode request timed out after ${timeoutMs}ms")

class SessionNotFoundException(sessionId: String) :
    Exception("Session not found: $sessionId")

class TerminalNotFoundException(terminalId: String) :
    Exception("Terminal not found: $terminalId")

class MethodNotFoundException(method: String) :
    Exception("Custom method not found: $method")

class UnsupportedContentException(type: String) :
    Exception("Content type not supported: $type")
