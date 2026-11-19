package com.soswf.obd.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConsumoTest {

    @Test
    fun marchaLentaDoEtiosComEtanol() {
        // Relatório real: MAF 0x019E = 4,14 g/s, etanol 100%, lambda 1,000.
        val lh = Consumo.litrosPorHora(4.14, 100.0, 1.0)
        // 4,14 / 8,4 = 0,493 g/s -> 1774 g/h -> /809 g/L = 2,19 L/h
        assertEquals(2.19, lh, 0.01)
    }

    @Test
    fun gasolinaGastaMenosLitrosQueEtanolProMesmoAr() {
        val g = Consumo.litrosPorHora(10.0, 0.0)
        val e = Consumo.litrosPorHora(10.0, 100.0)
        assertEquals(e / g, (14.7 / 8.4) * (0.745 / 0.809), 1e-9) // ~1,61x
    }

    @Test
    fun lambdaForaDaFaixaEhIgnorado() {
        assertEquals(Consumo.litrosPorHora(5.0, 100.0, 1.0), Consumo.litrosPorHora(5.0, 100.0, 1.999))
    }

    private fun v(n: Double) = listOf(Valor("x", n, ""))

    @Test
    fun acumuladorSeparaParadoDeAndando() {
        val a = AcumuladorConsumo()
        a.registrar(0x52, v(100.0), 0)
        // 60 s parado a 2,19 L/h: leituras de 1 em 1 s
        a.registrar(0x0D, v(0.0), 0)
        for (s in 0..60) a.registrar(0x10, v(4.14), s * 1000L)
        assertTrue(a.parado)
        assertNull(a.kmPorLitroAgora)
        assertEquals(2.19 / 60, a.litrosParado, 0.001)
        assertEquals(60.0, a.segundosParado, 1e-9)

        // 60 s a 36 km/h -> 0,6 km
        for (s in 61..121) {
            a.registrar(0x0D, v(36.0), s * 1000L)
            a.registrar(0x10, v(10.0), s * 1000L)
        }
        assertEquals(0.6, a.km, 0.011)
        assertEquals(60.0, a.segundosParado, 1e-9) // não cresceu andando
        val kml = a.kmPorLitroAgora!!
        assertEquals(36.0 / Consumo.litrosPorHora(10.0, 100.0), kml, 1e-9)
    }

    @Test
    fun buracoNaConexaoNaoContaComoConsumo() {
        val a = AcumuladorConsumo()
        a.registrar(0x52, v(100.0), 0)
        a.registrar(0x10, v(4.0), 0)
        a.registrar(0x10, v(4.0), 60_000) // 60 s sem leitura: ignora o intervalo
        assertEquals(0.0, a.litros)
    }

    @Test
    fun tipoDeCombustivelEhPlanoBMasNaoSobrepoeOTeorReal() {
        val a = AcumuladorConsumo()
        a.registrar(0x51, listOf(Valor("Tipo", null, "", "Gasolina")), 0)
        assertEquals(0.0, a.etanolPct)
        a.registrar(0x52, v(100.0), 1)
        a.registrar(0x51, listOf(Valor("Tipo", null, "", "Gasolina")), 2)
        assertEquals(100.0, a.etanolPct)
    }

    @Test
    fun semTeorDeEtanolNaoChuta() {
        val a = AcumuladorConsumo()
        a.registrar(0x10, v(4.0), 0)
        assertNull(a.litrosHora)
    }
}
