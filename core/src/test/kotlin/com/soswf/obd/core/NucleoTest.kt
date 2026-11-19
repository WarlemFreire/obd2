package com.soswf.obd.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Adaptador falso: responde a partir de um mapa comando -> texto. */
class LinkRoteirizado(private val respostas: Map<String, String>) : ElmLink {
    val enviados = mutableListOf<String>()
    private var ultimo = ""
    override fun send(text: String) { ultimo = text.trim(); enviados += ultimo }
    // Como o ELM327 real: comando AT/ST desconhecido -> "?"; pedido OBD sem resposta -> "NO DATA".
    override fun readUntilPrompt(timeoutMs: Long) =
        (respostas[ultimo] ?: if (ultimo.startsWith("AT") || ultimo.startsWith("ST")) "?" else "NO DATA") + "\r\r"
    override fun close() {}
}

class NucleoTest {

    @Test
    fun quadroUnicoComCabecalho() {
        val r = ElmParser.interpretar("7E8 04 41 0C 14 5F \r\r", "010C", Cabecalho.CAN_11)
        assertIs<Resultado.Ok>(r)
        assertEquals(EcuResposta("7E8", listOf(0x41, 0x0C, 0x14, 0x5F)), r.primeira())
    }

    @Test
    fun vinMultiQuadroDoEmulador() {
        // Resposta real capturada do ELM327-emulator (cenário Toyota).
        val bruto = "7E8 10 14 49 02 01 57 50 30 \r7E8 21 5A 5A 5A 39 39 5A 54 \r7E8 22 53 33 39 30 30 30 30 \r\r"
        val r = ElmParser.interpretar(bruto, "0902", Cabecalho.CAN_11) as Resultado.Ok
        val bytes = r.primeira()!!.bytes
        assertEquals(0x14, bytes.size)
        assertEquals("WP0ZZZ99ZTS390000", Diagnostico.vin(bytes.drop(2)))
    }

    @Test
    fun duasCentraisRespondem() {
        val bruto = "7E8 06 41 00 BE 3F A8 13 \r7E9 06 41 00 98 18 80 11 \r"
        val r = ElmParser.interpretar(bruto, "0100", Cabecalho.CAN_11) as Resultado.Ok
        assertEquals(listOf("7E8", "7E9"), r.respostas.map { it.ecu })
    }

    @Test
    fun espacosDesligados() {
        val r = ElmParser.interpretar("7E804410C145F\r", "010C", Cabecalho.CAN_11) as Resultado.Ok
        assertEquals(listOf(0x41, 0x0C, 0x14, 0x5F), r.primeira()!!.bytes)
    }

    @Test
    fun semCabecalhoMultiLinha() {
        val bruto = "014\r0: 49 02 01 57 50 30\r1: 5A 5A 5A 39 39 5A 54\r2: 53 33 39 30 30 30 30\r"
        val r = ElmParser.interpretar(bruto, "0902", Cabecalho.NENHUM) as Resultado.Ok
        assertEquals("WP0ZZZ99ZTS390000", Diagnostico.vin(r.primeira()!!.bytes.drop(2)))
    }

    @Test
    fun errosDoAdaptador() {
        assertEquals(ErroElm.SEM_DADOS, (ElmParser.interpretar("NO DATA\r", "0152", Cabecalho.CAN_11) as Resultado.Erro).tipo)
        assertEquals(ErroElm.NAO_ENTENDIDO, (ElmParser.interpretar("?\r", "XYZ", Cabecalho.CAN_11) as Resultado.Erro).tipo)
        assertEquals(ErroElm.SEM_CONEXAO_CARRO,
            (ElmParser.interpretar("SEARCHING...\rUNABLE TO CONNECT\r", "0100", Cabecalho.CAN_11) as Resultado.Erro).tipo)
        assertEquals(ErroElm.ERRO_BARRAMENTO, (ElmParser.interpretar("CAN ERROR\r", "0100", Cabecalho.CAN_11) as Resultado.Erro).tipo)
    }

    @Test
    fun ecoESearchingSaoIgnorados() {
        val r = ElmParser.interpretar("010C\rSEARCHING...\r7E8 04 41 0C 0F A0\r", "010C", Cabecalho.CAN_11) as Resultado.Ok
        assertEquals(1000.0, Pids.decodificar(0x0C, r.primeira()!!.bytes.drop(2))!!.first().numero)
    }

    @Test
    fun decodificaCodigosDeFalha() {
        assertEquals("P0171", Dtc.de(0x01, 0x71).codigo)
        assertEquals("P0300", Dtc.de(0x03, 0x00).codigo)
        assertEquals("C0035", Dtc.de(0x40, 0x35).codigo)
        assertEquals("B1A2F", Dtc.de(0x9A, 0x2F).codigo)
        assertEquals("U0100", Dtc.de(0xC1, 0x00).codigo)
        // CAN: 43 [qtd] pares
        assertEquals(listOf("P0171", "P0420"), Diagnostico.codigos(listOf(0x02, 0x01, 0x71, 0x04, 0x20), can = true).map { it.codigo })
        // Protocolo antigo: sem contagem, preenchido com zeros
        assertEquals(listOf("P0301"), Diagnostico.codigos(listOf(0x03, 0x01, 0x00, 0x00, 0x00, 0x00), can = false).map { it.codigo })
        assertTrue(Diagnostico.codigos(listOf(0x00), can = true).isEmpty())
    }

    @Test
    fun prontidaoMotorAGasolina() {
        // MIL acesa, 2 códigos; misfire e combustível suportados e completos; componentes incompletos.
        // C: catalisador, EVAP, sonda, aquecimento da sonda suportados. D: EVAP incompleto.
        val p = Diagnostico.prontidao(listOf(0x82, 0x47, 0x65, 0x04))
        assertTrue(p.luzAcesa)
        assertEquals(2, p.qtdCodigos)
        assertFalse(p.ignicaoPorCompressao)
        val m = p.monitores.associateBy { it.nome }
        assertTrue(m.getValue("Falha de ignição (misfire)").completo)
        assertFalse(m.getValue("Componentes").completo)
        assertTrue(m.getValue("Catalisador").suportado)
        assertFalse(m.getValue("Sistema evaporativo (EVAP)").completo)
        assertEquals(setOf("Componentes", "Sistema evaporativo (EVAP)"), p.pendentes.map { it.nome }.toSet())
    }

    @Test
    fun mapaDeSuporte() {
        // BE 1F A8 13: exemplo clássico da documentação do ELM327.
        val s = Pids.suportados(0x00, listOf(0xBE, 0x1F, 0xA8, 0x13))
        assertEquals(setOf(0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x13, 0x15, 0x1C, 0x1F, 0x20), s)
    }

    @Test
    fun falhasDeIgnicaoPorCilindro() {
        // 46 A2 | TID 0B UASID 24 val=0005 min=0000 max=FFFF | TID 0C 24 val=0012 0000 FFFF
        val payload = listOf(0xA2, 0x0B, 0x24, 0x00, 0x05, 0x00, 0x00, 0xFF, 0xFF, 0x0C, 0x24, 0x00, 0x12, 0x00, 0x00, 0xFF, 0xFF)
        val f = Diagnostico.falhasIgnicao(Diagnostico.modo06(payload)).single()
        assertEquals(1, f.cilindro)
        assertEquals(18, f.cicloAtual)
        assertEquals(5, f.media10Ciclos)
    }

    @Test
    fun appNuncaApagaNemEscreve() {
        val elm = Elm327(LinkRoteirizado(emptyMap()))
        for (proibido in listOf("04", "14FF00", "2E0101", "2F0101", "3101FF", "3B01", "1001", "1101")) {
            assertFailsWith<IllegalArgumentException>(proibido) { elm.pedir(proibido) }
        }
    }

    @Test
    fun baseDeCodigos() {
        assertTrue(CodigosFalha.total >= 140)
        val e = CodigosFalha.explicar(Dtc("P0171"))
        assertTrue(e.conhecido)
        assertTrue(e.causas.size >= 3)
        val fab = CodigosFalha.explicar(Dtc("P1349"))
        assertFalse(fab.conhecido)
        assertTrue(fab.descricao.contains("fabricante"))
        val gen = CodigosFalha.explicar(Dtc("P0455"))
        assertTrue(gen.conhecido)
        assertTrue(CodigosFalha.explicar(Dtc("P0999")).descricao.contains("transmissão"))
        assertTrue(CodigosFalha.explicar(Dtc("B2799")).descricao.contains("fabricante"))
    }

    @Test
    fun inicializacaoDetectaCan11() {
        val link = LinkRoteirizado(mapOf(
            "ATZ" to "ELM327 v1.5", "ATE0" to "OK", "ATL0" to "OK", "ATS1" to "OK", "ATH1" to "OK",
            "ATAT1" to "OK", "ATSP0" to "OK", "0100" to "SEARCHING...\r7E8 06 41 00 BE 3F A8 13",
            "ATDPN" to "A6", "AT@1" to "OBDII to RS232 Interpreter", "ATDP" to "AUTO, ISO 15765-4 (CAN 11/500)",
            "ATRV" to "12.6V",
        ))
        val elm = Elm327(link)
        val info = elm.inicializar()
        assertEquals(Cabecalho.CAN_11, elm.cabecalho)
        assertEquals("ELM327 v1.5", info.versao)
        assertEquals("12.6V", info.tensao)
        assertNull(elm.atOpcional("STI"))
    }
}
