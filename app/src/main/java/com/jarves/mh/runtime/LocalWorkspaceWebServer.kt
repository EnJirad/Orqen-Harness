package com.jarves.mh.runtime

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lightweight, zero-dependency local HTTP server bound strictly to 127.0.0.1
 * for hosting AI-built websites (static HTML/CSS/JS, Vite/React dist/, etc.)
 * inside ORQEN's Web Preview without file:// CORS or ES-module restrictions.
 */
class LocalWorkspaceWebServer(
    rootDir: File,
) : AutoCloseable {
    private val canonicalRoot: File = rootDir.canonicalFile
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService? = null

    val port: Int
        get() = serverSocket?.localPort ?: -1

    val baseUrl: String
        get() = if (port > 0) "http://127.0.0.1:$port" else ""

    fun start(): String {
        if (running.get() && port > 0) return baseUrl
        val socket = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        running.set(true)
        val pool = Executors.newCachedThreadPool { r ->
            Thread(r, "orqen-local-web-${socket.localPort}").apply { isDaemon = true }
        }
        executor = pool
        pool.execute {
            while (running.get() && !socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                pool.execute { handleClient(client) }
            }
        }
        return baseUrl
    }

    private fun handleClient(client: Socket) {
        client.use { sock ->
            runCatching {
                sock.soTimeout = 5_000
                val reader = BufferedReader(InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8))
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val rawTarget = parts[1].substringBefore("?")
                val decodedPath = runCatching {
                    URLDecoder.decode(rawTarget, "UTF-8")
                }.getOrDefault(rawTarget).trimStart('/')

                // Drain headers
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }

                if (method != "GET" && method != "HEAD") {
                    writeResponse(sock, 405, "Method Not Allowed", "text/plain", ByteArray(0), headOnly = true)
                    return
                }

                var targetFile = if (decodedPath.isBlank()) {
                    File(canonicalRoot, "index.html")
                } else {
                    File(canonicalRoot, decodedPath)
                }
                if (targetFile.isDirectory) {
                    targetFile = File(targetFile, "index.html")
                }

                val canonicalTarget = runCatching { targetFile.canonicalFile }.getOrNull()
                val insideRoot = canonicalTarget != null &&
                    canonicalTarget.toPath().startsWith(canonicalRoot.toPath())

                val resolvedFile = when {
                    insideRoot && canonicalTarget!!.isFile -> canonicalTarget
                    // SPA fallback to index.html for extensionless client-side routes
                    !decodedPath.contains('.') && File(canonicalRoot, "index.html").isFile -> File(canonicalRoot, "index.html")
                    else -> null
                }

                if (resolvedFile == null) {
                    val body = "404 Not Found: /$decodedPath".toByteArray(StandardCharsets.UTF_8)
                    writeResponse(sock, 404, "Not Found", "text/plain; charset=utf-8", body, headOnly = method == "HEAD")
                    return
                }

                val body = resolvedFile.readBytes()
                val mime = mimeTypeFor(resolvedFile.name)
                writeResponse(sock, 200, "OK", mime, body, headOnly = method == "HEAD")
            }
        }
    }

    private fun writeResponse(
        socket: Socket,
        statusCode: Int,
        statusText: String,
        contentType: String,
        body: ByteArray,
        headOnly: Boolean,
    ) {
        val out = socket.getOutputStream()
        val header = buildString {
            append("HTTP/1.1 $statusCode $statusText\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Cache-Control: no-store, no-cache, must-revalidate\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        out.write(header.toByteArray(StandardCharsets.US_ASCII))
        if (!headOnly && body.isNotEmpty()) {
            out.write(body)
        }
        out.flush()
    }

    override fun close() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { executor?.shutdownNow() }
        executor = null
    }

    companion object {
        fun mimeTypeFor(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
            "html", "htm" -> "text/html; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "js", "mjs", "cjs" -> "application/javascript; charset=utf-8"
            "json" -> "application/json; charset=utf-8"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "ico" -> "image/x-icon"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "wasm" -> "application/wasm"
            "txt", "md" -> "text/plain; charset=utf-8"
            else -> "application/octet-stream"
        }
    }
}
