package com.soswf.obd.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManutencaoTest {

    private val hoje = LocalDate.of(2026, 11, 19)
    private val plano = listOf(
        ItemPlano("oleo", "Óleo", 5000, 6),
        ItemPlano("fluido_freio", "Fluido de freio", null, 24),
        ItemPlano("velas", "Velas", 30000, null),
        ItemPlano("atf", "ATF", 40000, null),
    )

    @Test
    fun venceOQueChegarPrimeiro() {
        val m = Manutencao(
            plano,
            listOf(
                Servico(LocalDate.of(2026, 9, 1), 100_000, "oleo", "troca de óleo"),       // vence por km em 105.000
                Servico(LocalDate.of(2024, 12, 1), 80_000, "fluido_freio", "fluido"),       // vence por data em 01/12/2026
                Servico(LocalDate.of(2026, 1, 10), 90_000, "velas", "velas"),               // 120.000: ok
            ),
            emptyList(),
        )
        val s = m.situacao(hoje, odometroAtual = 104_600).associateBy { it.item.id }
        assertEquals(Estado.ATENCAO, s.getValue("oleo").estado)          // faltam 400 km (<= 10% de 5.000)
        assertEquals(400, s.getValue("oleo").faltamKm)
        assertEquals(Estado.ATENCAO, s.getValue("fluido_freio").estado)  // faltam 12 dias
        assertEquals(12L, s.getValue("fluido_freio").faltamDias)
        assertEquals(Estado.OK, s.getValue("velas").estado)
        assertEquals(Estado.SEM_HISTORICO, s.getValue("atf").estado)

        val vencido = m.situacao(hoje, odometroAtual = 105_100).first()
        assertEquals("oleo", vencido.item.id)                            // vencidos primeiro na lista
        assertEquals(Estado.VENCIDO, vencido.estado)
        assertEquals(-100, vencido.faltamKm)
    }

    @Test
    fun oleoVencePorDataMesmoComPoucoKm() {
        val m = Manutencao(plano, listOf(Servico(LocalDate.of(2026, 4, 1), 100_000, "oleo", "óleo")), emptyList())
        assertEquals(Estado.VENCIDO, m.situacao(hoje, 101_000).first { it.item.id == "oleo" }.estado) // 6 meses passaram
    }

    @Test
    fun odometroEstimadoSomaSoOQueOAppMediuDesdeALeitura() {
        val m = Manutencao(plano, emptyList(), listOf(
            LeituraOdometro(LocalDate.of(2026, 11, 1), 150_000, refKmApp = 10.0),
            LeituraOdometro(LocalDate.of(2026, 11, 15), 151_200, refKmApp = 300.0),
        ))
        assertEquals(151_200 + 150, m.odometroEstimado(kmAppTotal = 450.0))
        assertNull(Manutencao(plano, emptyList(), emptyList()).odometroEstimado(100.0))
    }

    @Test
    fun csvIdaEVolta() {
        val m = Manutencao(
            Manutencao.PLANO_PADRAO,
            listOf(Servico(LocalDate.of(2026, 11, 18), 152_340, "filtro_cabine", "Filtro de cabine, marca X", 45.9, "Autopeças, Rio", "obs com \"aspas\"")),
            listOf(LeituraOdometro(LocalDate.of(2026, 11, 19), 152_400, 12.5)),
        )
        val erros = mutableListOf<String>()
        val volta = ManutencaoCsv.ler(ManutencaoCsv.escrever(m), erros)
        assertTrue(erros.isEmpty(), erros.toString())
        assertEquals(m, volta)
    }

    @Test
    fun importacaoToleraLinhaRuimEKmComPonto() {
        val csv = """
            tipo,id,nome,data,km,intervalo_km,intervalo_meses,custo,local,obs,ref_km_app
            servico,oleo,Troca de óleo 0W-20,2026-10-02,"151.200",,,"189,90",Oficina,,
            servico,,Embreagem do compressor (trinca),2026-08-10,148000,,,,,,
            servico,oleo,data errada,02/10/2026,1,,,,,,
            odometro,,,2026-11-19,152400,,,,,,
        """.trimIndent()
        val erros = mutableListOf<String>()
        val m = ManutencaoCsv.ler(csv, erros)
        assertEquals(2, m.servicos.size)
        assertEquals(151_200, m.servicos[0].km)
        assertEquals(189.90, m.servicos[0].custo!!, 1e-9)
        assertNull(m.servicos[1].itemId)
        assertEquals(1, erros.size)            // a linha com data em formato errado
        assertEquals(152_400, m.odometro.single().km)
    }

    @Test
    fun historicoComDataOuKmFaltando() {
        // Caso real do prontuário: óleo com data+km, depois só km; cabine só km, depois só data.
        val m = Manutencao(
            plano + ItemPlano("filtro_cabine", "Cabine", 10000, 6),
            listOf(
                Servico(LocalDate.of(2026, 8, 7), 104_064, "oleo", "óleo"),
                Servico(LocalDate.of(2026, 10, 9), 114_791, null, "limpeza MAF"),
                Servico(null, 113_215, "oleo", "óleo"),
                Servico(null, 103_000, "filtro_cabine", "cabine"),
                Servico(LocalDate.of(2026, 11, 16), null, "filtro_cabine", "cabine"),
                Servico(null, null, "atf", "sem data nem km"),
            ),
            listOf(LeituraOdometro(null, 118_909)),
        )
        val s = m.situacao(hoje, m.odometroEstimado(0.0)).associateBy { it.item.id }
        // Óleo: o mais recente é o de 113.215 (sem data) -> vence por km: passou 694 km
        assertEquals(113_215, s.getValue("oleo").ultimo!!.km)
        assertEquals(Estado.VENCIDO, s.getValue("oleo").estado)
        assertEquals(-694, s.getValue("oleo").faltamKm)
        assertNull(s.getValue("oleo").proximaData)                     // sem data, só vence por km
        // Cabine: o de 16/11 (só data) é mais recente que o de 103.000 (só km) -> vence por data em 16/05/2027
        assertEquals(LocalDate.of(2026, 11, 16), s.getValue("filtro_cabine").ultimo!!.data)
        assertEquals(LocalDate.of(2027, 5, 16), s.getValue("filtro_cabine").proximaData)
        assertEquals(Estado.OK, s.getValue("filtro_cabine").estado)
        // Serviço sem data nem km: aparece como último, mas sem como calcular vencimento
        assertEquals(Estado.SEM_HISTORICO, s.getValue("atf").estado)
        assertEquals("sem data nem km", s.getValue("atf").ultimo!!.descricao)
        // CSV com campos vazios vai e volta (gravado em ordem cronológica estimada)
        val volta = ManutencaoCsv.ler(ManutencaoCsv.escrever(m))
        assertEquals(m.plano, volta.plano)
        assertEquals(m.servicos.toSet(), volta.servicos.toSet())
        assertEquals(m.odometro, volta.odometro)
        assertEquals(listOf(null, 103_000, 104_064, 113_215, 114_791, null), volta.servicos.map { it.km })
    }
}
