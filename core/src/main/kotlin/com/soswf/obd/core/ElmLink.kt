package com.soswf.obd.core

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Canal bruto com o adaptador. Bluetooth Clássico, BLE e Wi-Fi implementam isto;
 * todo o resto do núcleo só conhece esta interface.
 */
interface ElmLink {
    /** Envia um comando já com o terminador (\r). */
    fun send(text: String)

    /** Lê até o prompt '>' do ELM327 ou até estourar o tempo. Devolve o texto sem o '>'. */
    fun readUntilPrompt(timeoutMs: Long): String

    fun close()
}

class ElmTimeoutException(val parcial: String) : IOException("Adaptador não respondeu a tempo")

/** Implementação para qualquer par de streams (socket RFCOMM, socket TCP). */
class StreamElmLink(
    private val input: InputStream,
    private val output: OutputStream,
    private val onClose: () -> Unit = {},
) : ElmLink {

    override fun send(text: String) {
        // Descarta lixo que chegou fora de hora (resposta atrasada de comando anterior).
        while (input.available() > 0) input.read(ByteArray(input.available()))
        output.write(text.toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    override fun readUntilPrompt(timeoutMs: Long): String {
        val sb = StringBuilder()
        val limite = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < limite) {
            if (input.available() > 0) {
                val c = input.read()
                if (c < 0) throw IOException("Conexão encerrada pelo adaptador")
                if (c == '>'.code) return sb.toString()
                if (c != 0) sb.append(c.toChar())
            } else {
                Thread.sleep(4)
            }
        }
        throw ElmTimeoutException(sb.toString())
    }

    override fun close() {
        runCatching { input.close() }
        runCatching { output.close() }
        onClose()
    }
}
