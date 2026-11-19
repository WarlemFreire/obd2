package com.soswf.obd.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Cruza cada fórmula com a python-OBD (biblioteca de referência amplamente usada).
 * Os vetores foram gerados com bytes aleatórios e decodificados por ela; ver README.
 */
class PidsReferenciaTest {

    private data class Vetor(val pid: Int, val bytes: List<Int>, val valor: Double, val nome: String)

    private fun carregar(): List<Vetor> {
        val json = javaClass.getResource("/referencia_python_obd.json")!!.readText()
        val obj = Regex("\\{[^}]*\\}").findAll(json).map { it.value }
        return obj.map { o ->
            fun campo(n: String) = Regex("\"$n\":\\s*(\\[[^\\]]*\\]|[^,}\\[\\]]+)").find(o)!!.groupValues[1].trim()
            Vetor(
                pid = campo("pid").toInt(),
                bytes = campo("bytes").trim('[', ']').split(',').map { it.trim().toInt() },
                valor = campo("valor").toDouble(),
                nome = campo("nome").trim('"'),
            )
        }.toList()
    }

    /**
     * 0x32: a python-OBD sinaliza byte a byte; a faixa da norma (−8192 a 8191,75 Pa) só fecha com 16 bits
     *       sinalizados, que é o que o app faz.
     * 0x54: a python-OBD usa (256A+B) − 32767; o app usa 16 bits sinalizados. A norma não deixa claro;
     *       resolver com a resposta real do carro (pressão em repouso ≈ 0 Pa).
     */
    private val divergenciasConhecidas = setOf(0x32, 0x54)

    @Test
    fun pressaoEvapUsaDezesseisBitsSinalizados() {
        val v = Pids.decodificar(0x32, listOf(0xFF, 0xFC))!!.first().numero!!
        kotlin.test.assertEquals(-1.0, v) // 0xFFFC = −4 → −4/4 = −1 Pa
        val max = Pids.decodificar(0x32, listOf(0x7F, 0xFF))!!.first().numero!!
        kotlin.test.assertEquals(8191.75, max)
    }

    @Test
    fun todasAsFormulasBatemComPythonObd() {
        val vetores = carregar()
        assertTrue(vetores.size > 1000, "referência carregada: ${vetores.size}")
        val falhas = mutableListOf<String>()
        val semDecodificador = mutableSetOf<String>()
        for (v in vetores) {
            val nossos = Pids.decodificar(v.pid, v.bytes)
            if (nossos == null) { semDecodificador += "%02X %s".format(v.pid, v.nome); continue }
            val numeros = nossos.mapNotNull { it.numero }
            if (v.pid in divergenciasConhecidas) continue
            // Tolerância relativa de 0,1%: a python-OBD usa 65535 e 0,0000305 onde a norma usa 65536 e 2/65536.
            if (numeros.none { abs(it - v.valor) <= 1e-6 + abs(v.valor) * 1e-3 }) {
                falhas += "PID %02X (%s) bytes=%s: python=%s nosso=%s".format(v.pid, v.nome, v.bytes, v.valor, numeros)
            }
        }
        println("PIDs da python-OBD sem decodificador no app: $semDecodificador")
        val comparados = vetores.map { it.pid }.toSet()
        val nossosSemReferencia = Pids.porPid.keys.filter { it !in comparados }
        println("PIDs do app sem referência na python-OBD: " + nossosSemReferencia.joinToString { "%02X".format(it) })
        // Nenhum PID que a python-OBD conhece pode ficar sem decodificador no app por erro nosso.
        val esperadosSemDecodificador = setOf(0x50, 0x55, 0x56, 0x57, 0x58)
        val inesperados = semDecodificador.filter { it.substring(0, 2).toInt(16) !in esperadosSemDecodificador }
        assertTrue(inesperados.isEmpty(), "PIDs sem decodificador por engano: $inesperados")
        if (falhas.isNotEmpty()) fail("${falhas.size} divergências:\n" + falhas.distinctBy { it.substring(0, 6) }.joinToString("\n"))
    }
}
