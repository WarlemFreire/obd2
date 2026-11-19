package com.soswf.obd.core

import org.junit.Assume.assumeTrue
import java.io.File
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Descoberta completa contra o ELM327-emulator (github.com/Ircama/ELM327-emulator), cenário Toyota:
 *
 *   python3 -m elm -n 35000 -s car -d
 *   OBD_EMULADOR=127.0.0.1:35000 ./gradlew :core:test
 *
 * Sem a variável, o teste é pulado (CI não sobe o emulador).
 */
class EmuladorTest {

    @Test
    fun descobertaCompletaNoCenarioToyota() {
        val alvo = System.getenv("OBD_EMULADOR")
        assumeTrue("OBD_EMULADOR não definido", !alvo.isNullOrBlank())
        val (host, porta) = alvo!!.split(":")
        val socket = Socket(host, porta.toInt())
        val elm = Elm327(StreamElmLink(socket.getInputStream(), socket.getOutputStream()) { socket.close() })
        val etapas = mutableListOf<String>()
        val r = try {
            Descoberta.executar(elm) { etapas += it }
        } finally {
            elm.fechar()
        }
        val texto = Relatorio.texto(r, mapOf("Origem" to "ELM327-emulator, cenário car"))
        File("build/relatorio-emulador.txt").writeText(texto)

        assertEquals(Cabecalho.CAN_11, elm.cabecalho)
        assertTrue(r.pidsPorEcu.containsKey("7E8"), "central do motor respondeu")
        assertTrue(r.leituras.any { it.pid == 0x0C }, "leu a rotação")
        assertTrue(r.leituras.any { it.pid == 0x05 }, "leu a temperatura")
        assertNotNull(r.vin)
        assertEquals(17, r.vin!!.length)
        assertNotNull(r.prontidao)
        assertTrue(r.sondagens.size == 8)
        // Nenhuma etapa pode ter quebrado silenciosamente por bug nosso.
        val avisosDeBug = r.avisos.filterNot { it.startsWith("PIDs suportados sem decodificador") }
        assertTrue(avisosDeBug.isEmpty(), "avisos: $avisosDeBug")
        // Trava de segurança: nenhum comando de apagar/escrever saiu para o adaptador.
        assertTrue(r.transcricao.none { it.comando.startsWith("04") || it.comando.startsWith("2E") || it.comando.startsWith("31") })
    }

    @Test
    fun gravacaoContraOEmulador() {
        val alvo = System.getenv("OBD_EMULADOR")
        assumeTrue("OBD_EMULADOR não definido", !alvo.isNullOrBlank())
        val (host, porta) = alvo!!.split(":")
        val socket = Socket(host, porta.toInt())
        val elm = Elm327(StreamElmLink(socket.getInputStream(), socket.getOutputStream()) { socket.close() })
        val arquivo = File("build/gravacao-emulador.csv")
        try {
            elm.inicializar()
            val leitor = Leitor(elm)
            val pids = leitor.suporte("01").values.flatten()
            arquivo.bufferedWriter().use { w ->
                val g = Gravador(leitor, pids, w, mapOf("Origem" to "ELM327-emulator"), cicloBloco21 = 10, cicloDiagnostico = 20)
                repeat(40) { g.ciclo() }
                assertEquals(40, g.linhas)
            }
        } finally {
            elm.fechar()
        }
        val linhas = arquivo.readLines().filter { !it.startsWith("#") }
        assertEquals(41, linhas.size) // cabeçalho + 40
        val cab = linhas[0].split(",")
        val rpm = cab.indexOf("0C Rotação do motor")
        assertTrue(rpm > 0)
        assertTrue(linhas.drop(1).all { it.split(",")[rpm].isNotEmpty() }, "rotação em toda linha")
    }
}
