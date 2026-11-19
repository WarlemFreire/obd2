package com.soswf.obd.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Respostas reais do Etios Sedan 2018 (adaptador ELM327 v1.5 clone, BLE), capturadas no
 * relatório de descoberta de 19/11/2026. Cada teste reproduz um comportamento visto no carro.
 */
class EtiosRealTest {

    private fun ok(bruto: String) = ElmParser.interpretar(bruto, "", Cabecalho.CAN_11) as Resultado.Ok

    @Test
    fun modo06RepeteMidEmCadaRegistro() {
        // 06A5 = cilindro 4: TID 0B (média 10 ciclos) = 0, TID 0C (ciclo atual) = 1 falha.
        val r = ok("7E8 10 13 46 A5 0B 24 00 00\r7E8 21 00 00 FF FF A5 0C 24\r7E8 22 00 01 00 00 FF FF 00\r")
        val testes = Diagnostico.modo06(r.primeira()!!.bytes.drop(1))
        assertEquals(2, testes.size)
        assertTrue(testes.all { it.mid == 0xA5 && it.uasid == 0x24 })
        val f = Diagnostico.falhasIgnicao(testes).single()
        assertEquals(4, f.cilindro)
        assertEquals(1, f.cicloAtual)
        assertEquals(0, f.media10Ciclos)
    }

    @Test
    fun multiQuadroIncompletoEhRejeitadoNaoMalInterpretado() {
        // 06A3 chegou sem o 3º quadro na descoberta real.
        val r = ElmParser.interpretar("7E8 10 13 46 A3 0B 24 00 00\r7E8 21 00 00 FF FF A3 0C 24\r", "06A3", Cabecalho.CAN_11)
        assertEquals(ErroElm.INVALIDO, (r as Resultado.Erro).tipo)
    }

    @Test
    fun leitorRepeteQuandoAdaptadorPerdeResposta() {
        // Na primeira tentativa o clone devolve NO DATA para 0104 (visto ~3% das vezes).
        val link = object : ElmLink {
            var ultimo = ""
            var tentativas0104 = 0
            override fun send(text: String) { ultimo = text.trim() }
            override fun readUntilPrompt(timeoutMs: Long): String =
                if (ultimo == "0104" && tentativas0104++ == 0) "NO DATA\r" else "7E8 03 41 04 90\r"
            override fun close() {}
        }
        val leituras = Leitor(Elm327(link)).ler(0x04)
        assertEquals(2, link.tentativas0104)
        assertEquals(56.5, leituras.single().valores.single().numero!!, 0.05)
    }

    @Test
    fun servicoNaoSuportadoNaoViraListaVazia() {
        // O Etios não responde ao Modo 0A (códigos permanentes).
        val elm = Elm327(LinkRoteirizado(mapOf("03" to "7E8 02 43 00", "0A" to "NO DATA")))
        val l = Leitor(elm)
        assertEquals(emptyList(), l.codigos("03"))
        assertNull(l.codigos("0A"))
    }

    @Test
    fun prontidaoDoEtios() {
        // 41 01 00 07 21 01: luz apagada, catalisador incompleto (códigos apagados há 194 km).
        val p = Diagnostico.prontidao(listOf(0x00, 0x07, 0x21, 0x01))
        assertFalse(p.luzAcesa)
        assertEquals(listOf("Catalisador"), p.pendentes.map { it.nome })
        assertEquals(5, p.monitores.count { it.suportado })
    }

    @Test
    fun valoresDoEtiosBatemComORelatorio() {
        fun v(pid: Int, vararg b: Int) = Pids.decodificar(pid, b.toList())!!
        assertEquals(81.0, v(0x05, 0x79).single().numero)                       // água
        assertEquals(13.359, v(0x42, 0x34, 0x2F).single().numero!!, 0.001)     // tensão
        assertEquals(100.0, v(0x52, 0xFF).single().numero)                      // etanol
        assertEquals("Etanol", v(0x51, 0x03).single().texto)
        assertEquals("OBD-Brasil fase 2 (OBDBr-2)", v(0x1C, 0x1D).single().texto)
        assertEquals("Engatado (D)", v(0x65, 0x02, 0x02).single().texto)   // conferido no carro: D
        assertEquals("P ou N", v(0x65, 0x02, 0x00).single().texto)          // conferido no carro: P e N
        assertEquals("B1S1, B1S2", v(0x13, 0x03).single().texto)
    }
}
