package com.soswf.obd.core

import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GravadorTest {

    /** Adaptador falso com respostas reais do Etios; conta os pedidos. */
    private fun etios(): Pair<LinkRoteirizado, Leitor> {
        val link = LinkRoteirizado(mapOf(
            "010C" to "7E8 04 41 0C 0C A8", "010D" to "7E8 03 41 0D 00", "0104" to "7E8 03 41 04 90",
            "0105" to "7E8 03 41 05 79", "0142" to "7E8 04 41 42 34 2F", "012F" to "7E8 03 41 2F D0",
            "0152" to "7E8 03 41 52 FF", "010F" to "7E8 03 41 0F 69", "0165" to "7E8 04 41 65 02 02",
            "0115" to "7E8 04 41 15 98 FF",
            "07" to "7E8 02 47 00",
            "06A5" to "7E8 10 13 46 A5 0B 24 00 00\r7E8 21 00 00 FF FF A5 0C 24\r7E8 22 00 01 00 00 FF FF 00",
            "ATSH7E0" to "OK", "ATSH7DF" to "OK",
            "2101" to "7E8 10 1C 61 01 8D 00 56 01\r7E8 21 8B 69 64 79 0C 57 00\r7E8 22 12 38 00 30 83 29 51\r" +
                "7E8 23 30 04 00 C2 02 D6 34\r7E8 24 1B 00 00 00 00 00 00",
            "2181" to "7E8 10 08 61 81 00 45 80 00\r7E8 21 23 40 00 00 00 00 00",
        ))
        return link to Leitor(Elm327(link))
    }

    private fun linhasDados(csv: String) = csv.lines().filter { it.isNotBlank() && !it.startsWith("#") }

    @Test
    fun rapidosEmTodaLinhaLentosEmRodizio() {
        val (_, leitor) = etios()
        val out = StringWriter()
        var t = 1_000_000L
        val g = Gravador(
            leitor, listOf(0x0C, 0x0D, 0x04, 0x05, 0x42, 0x2F, 0x52, 0x0F, 0x65, 0x15), out,
            mapOf("Carro" to "Etios"), extras = listOf("bateria_celular_pct"),
            lerExtras = { mapOf("bateria_celular_pct" to "87") }, relogio = { t.also { t += 900 } },
            lentosPorCiclo = 2, cicloBloco21 = 3, cicloDiagnostico = 3, midsFalhas = listOf(0xA5),
        )
        assertEquals(listOf(0x04, 0x0C, 0x0D, 0x15, 0x65), g.rapidos)
        assertEquals(listOf(0x05, 0x0F, 0x2F, 0x42, 0x52), g.lentos)
        repeat(4) { g.ciclo() }

        val linhas = linhasDados(out.toString())
        val cab = linhas[0].split(",")
        val dados = linhas.drop(1).map { it.split(",") }
        assertEquals(4, dados.size)
        fun col(nome: String) = cab.indexOf(nome).also { assertTrue(it >= 0, "coluna $nome em $cab") }

        // Rápido: rotação em todas as linhas.
        assertTrue(dados.all { it[col("0C Rotação do motor")] == "810" })
        // Lento: água só nas linhas 0 e (5 lentos / 2 por ciclo) -> volta no ciclo 2? rodízio 05,0F | 2F,42 | 52,05 | 0F,2F
        val agua = dados.map { it[col("05 Temperatura do líquido de arrefecimento")] }
        assertEquals(listOf("81", "", "81", ""), agua)
        // Alavanca (PID 65) virou coluna mesmo só com o bit N/D.
        assertEquals("Engatado (D)", dados[0][col("65 Alavanca do câmbio")])
        // Bloco Toyota e diagnóstico nos ciclos 0 e 3, vazio nos outros.
        assertTrue(dados[0][col("toyota_2101_hex")].startsWith("61 01 8D 00 56 01 8B 69 64 79"))
        assertEquals("", dados[1][col("toyota_2101_hex")])
        assertTrue(dados[3][col("toyota_2181_hex")].startsWith("61 81"))
        assertEquals("nenhum", dados[0][col("codigos_pendentes")])
        assertEquals("1", dados[0][col("falhas_cil4_ciclo")])
        assertEquals("87", dados[2][col("bateria_celular_pct")])
        assertEquals(listOf("0.9", "1.8", "2.7", "3.6"), dados.map { it[col("t_s")] }) // início = criação do gravador
        assertTrue(dados.all { it[col("falhas_leitura")] == "0" })
    }

    @Test
    fun pidQueNaoRespondeContaFalhaEDeixaCelulaVazia() {
        val (_, leitor) = etios()
        val out = StringWriter()
        val g = Gravador(leitor, listOf(0x0C, 0x10), out, emptyMap(), cicloBloco21 = 1000, cicloDiagnostico = 1000)
        g.ciclo()
        val (cab, linha) = linhasDados(out.toString()).map { it.split(",") }
        assertEquals("", linha[cab.indexOf("10 Fluxo de ar (MAF)")])
        assertEquals("1", linha[cab.indexOf("falhas_leitura")])
    }
}
