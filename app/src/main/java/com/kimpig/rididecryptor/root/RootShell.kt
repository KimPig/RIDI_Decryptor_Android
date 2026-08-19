package com.kimpig.rididecryptor.root

import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.io.SuFileInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

data class RootCommandResult(val exitCode: Int, val stdout: ByteArray, val stderr: String)

class RootShell {
    fun knownRootStatus(): Boolean? = Shell.isAppGrantedRoot()

    fun isAvailable(): Boolean = runCatching {
        val result = execute("id -u", 20)
        result.exitCode == 0 && result.stdout.toString(Charsets.UTF_8).trim() == "0"
    }.getOrDefault(false)

    fun requestRootAccess(): Boolean {
        runCatching { Shell.getCachedShell()?.close() }
        return isAvailable()
    }

    fun isRegularFile(source: String): Boolean {
        val result = execute("if [ -f ${quote(source)} ]; then printf 1; fi", 20)
        return result.exitCode == 0 && result.stdout.toString(Charsets.UTF_8).trim() == "1"
    }

    /** Executes discovery-only commands. Callers must never pass a mutating command. */
    fun text(command: String, timeoutSeconds: Long = 45): String {
        val result = execute(command, timeoutSeconds)
        if (result.exitCode != 0) {
            throw IOException("Root command failed (${result.exitCode}): ${result.stderr.take(240)}")
        }
        return result.stdout.toString(Charsets.UTF_8)
    }

    fun readTextFile(source: String, maxBytes: Int = 8 * 1024 * 1024): String {
        require(maxBytes > 0)
        return openRootInput(source).use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > maxBytes) {
                    throw IOException("Official app preference file is unexpectedly large")
                }
                output.write(buffer, 0, count)
            }
            output.toString(Charsets.UTF_8.name())
        }
    }

    fun readFilePrefix(source: String, maxBytes: Int): ByteArray {
        require(maxBytes in 1..4096)
        return openRootInput(source).use { input ->
            val buffer = ByteArray(maxBytes)
            var total = 0
            while (total < maxBytes) {
                val count = input.read(buffer, total, maxBytes - total)
                if (count < 0) break
                total += count
            }
            buffer.copyOf(total)
        }
    }

    fun fileLength(source: String): Long {
        val value = text("stat -c %s ${quote(source)} 2>/dev/null", 20).trim()
        return value.toLongOrNull() ?: throw IOException("Root could not read the output file size")
    }

    fun copyFile(source: String, destination: File, timeoutSeconds: Long = 600) {
        // The privileged side is input-only. All writes target a normal app-owned File.
        requireNonOfficialDestination(destination)
        destination.parentFile?.mkdirs()
        val worker = CompletableFuture.runAsync({
            openRootInput(source).use { input ->
                FileOutputStream(destination).use { output -> input.copyTo(output, 128 * 1024) }
            }
        }, Shell.EXECUTOR)
        try {
            worker.get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            worker.cancel(true)
            destination.delete()
            throw IOException("Root copy timed out")
        } catch (error: Exception) {
            destination.delete()
            val cause = error.cause ?: error
            throw IOException("Could not read official app file: ${cause.message.orEmpty().take(240)}", cause)
        }
    }

    private fun execute(command: String, timeoutSeconds: Long): RootCommandResult {
        val stdout = mutableListOf<String>()
        val stderr = mutableListOf<String>()
        val future = Shell.cmd(command).to(stdout, stderr).enqueue()
        val result = try {
            future.get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            throw IOException("Root command timed out")
        }
        return RootCommandResult(
            result.code,
            stdout.joinToString("\n").toByteArray(Charsets.UTF_8),
            stderr.joinToString("\n")
        )
    }

    private fun openRootInput(source: String) = try {
        SuFileInputStream.open(source)
    } catch (error: Exception) {
        throw IOException("Root could not open ${source.substringAfterLast('/')}: ${error.message.orEmpty()}", error)
    }

    private fun requireNonOfficialDestination(destination: File) {
        val path = destination.canonicalPath.replace('\\', '/')
        val privateOfficial = Regex("^/data/(?:data|user(?:_de)?/[0-9]+)/com\\.initialcoms\\.ridi(?:/|$)")
        val externalOfficial = path.contains("/Android/data/com.initialcoms.ridi/")
        require(!privateOfficial.containsMatchIn(path) && !externalOfficial) {
            "Writing to an official RIDI path is forbidden"
        }
    }

    companion object {
        init {
            Shell.setDefaultBuilder(
                Shell.Builder.create()
                    .setFlags(Shell.FLAG_MOUNT_MASTER)
                    .setTimeout(20)
            )
        }

        fun quote(value: String): String {
            require(!value.contains('\n') && !value.contains('\r') && !value.contains('\u0000'))
            return "'" + value.replace("'", "'\\''") + "'"
        }
    }
}
