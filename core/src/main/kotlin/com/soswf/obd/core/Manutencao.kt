package com.soswf.obd.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Item do plano: troca/inspeção a cada [intervaloKm] e/ou [intervaloMeses] (o que vencer primeiro). */
data class ItemPlano(
    val id: String,
    val nome: String,
    val intervaloKm: Int?,
    val intervaloMeses: Int?,
    val obs: String = "",
)

/** Serviço feito. [itemId] null = serviço avulso (não zera nenhum item do plano). */
data class Servico(
    val data: LocalDate,
    val km: Int?,
    val itemId: String?,
    val descricao: String,
    val custo: Double? = null,
    val local: String = "",
    val obs: String = "",
)

/**
 * Leitura do odômetro do painel. [refKmApp] = total de km que o app tinha medido naquele momento,
 * para estimar o odômetro depois somando só o que o app mediu desde então.
 */
data class LeituraOdometro(val data: LocalDate, val km: Int, val refKmApp: Double = 0.0)

enum class Estado { OK, ATENCAO, VENCIDO, SEM_HISTORICO }

/** Ordem na tela: vencidos primeiro, depois atenção, sem histórico e, por último, os em dia. */
private val ORDEM_TELA = listOf(Estado.VENCIDO, Estado.ATENCAO, Estado.SEM_HISTORICO, Estado.OK)

data class Situacao(
    val item: ItemPlano,
    val ultimo: Servico?,
    val proximoKm: Int?,
    val proximaData: LocalDate?,
    val faltamKm: Int?,
    val faltamDias: Long?,
    val estado: Estado,
)

data class Manutencao(
    val plano: List<ItemPlano>,
    val servicos: List<Servico>,
    val odometro: List<LeituraOdometro>,
) {
    /** Odômetro estimado: última leitura + km medidos pelo app desde ela. null sem leitura nenhuma. */
    fun odometroEstimado(kmAppTotal: Double): Int? {
        val ult = odometro.maxWithOrNull(compareBy({ it.data }, { it.km })) ?: return null
        return ult.km + (kmAppTotal - ult.refKmApp).coerceAtLeast(0.0).toInt()
    }

    /**
     * Situação de cada item. "Atenção" quando faltam até 10% do intervalo de km ou 30 dias.
     * Vence pelo que chegar primeiro (km ou data).
     */
    fun situacao(hoje: LocalDate, odometroAtual: Int?): List<Situacao> = plano.map { item ->
        val ultimo = servicos.filter { it.itemId == item.id }.maxWithOrNull(compareBy({ it.data }, { it.km ?: 0 }))
        if (ultimo == null) return@map Situacao(item, null, null, null, null, null, Estado.SEM_HISTORICO)
        val proxKm = item.intervaloKm?.let { iv -> ultimo.km?.let { it + iv } }
        val proxData = item.intervaloMeses?.let { ultimo.data.plusMonths(it.toLong()) }
        val faltamKm = proxKm?.let { p -> odometroAtual?.let { p - it } }
        val faltamDias = proxData?.let { ChronoUnit.DAYS.between(hoje, it) }
        val vencido = (faltamKm != null && faltamKm <= 0) || (faltamDias != null && faltamDias <= 0)
        val atencao = (faltamKm != null && item.intervaloKm != null && faltamKm <= item.intervaloKm / 10) ||
            (faltamDias != null && faltamDias <= 30)
        val estado = when {
            vencido -> Estado.VENCIDO
            atencao -> Estado.ATENCAO
            else -> Estado.OK
        }
        Situacao(item, ultimo, proxKm, proxData, faltamKm, faltamDias, estado)
    }.sortedWith(compareBy<Situacao> { ORDEM_TELA.indexOf(it.estado) }.thenBy { it.faltamKm ?: Int.MAX_VALUE })

    companion object {
        /**
         * Plano padrão para o Etios 1.5 em uso severo (aplicativo, cidade, calor) com GNV.
         * SUGESTÃO EDITÁVEL: intervalos marcados "conferir" não foram confirmados no manual Toyota.
         */
        val PLANO_PADRAO = listOf(
            ItemPlano("oleo", "Óleo do motor + filtro de óleo", 5000, 6, "Uso severo. Toyota recomenda 0W-20 para o 2NR (conferir no manual)"),
            ItemPlano("filtro_ar", "Filtro de ar do motor", 10000, 12, "Uso severo/poeira"),
            ItemPlano("filtro_cabine", "Filtro de cabine (ar-condicionado)", 10000, 6, "Uso intenso do ar no Rio"),
            ItemPlano("velas", "Velas de ignição", 30000, null, "Encurtado pelo uso de GNV (conferir)"),
            ItemPlano("fluido_freio", "Fluido de freio", null, 24, ""),
            ItemPlano("pastilhas", "Pastilhas/discos de freio (inspeção)", 10000, 12, ""),
            ItemPlano("arrefecimento", "Líquido de arrefecimento", 40000, 24, "Conferir intervalo do líquido Toyota no manual"),
            ItemPlano("atf", "Fluido do câmbio automático", 40000, null, "Toyota trata como longa duração; uso severo pede troca (conferir)"),
            ItemPlano("correia", "Correia de acessórios (inspeção)", 20000, 24, ""),
            ItemPlano("pneus", "Rodízio, alinhamento e balanceamento", 10000, null, ""),
            ItemPlano("borboleta", "Limpeza do corpo de borboleta", 20000, null, "Problema comum no Etios"),
            ItemPlano("bateria", "Bateria e sistema de carga (teste)", null, 12, ""),
            ItemPlano("higienizacao_ar", "Higienização do ar-condicionado", null, 12, ""),
            ItemPlano("gnv_filtro", "GNV: filtro do gás", 10000, null, "Conferir com o instalador"),
            ItemPlano("gnv_redutor", "GNV: revisão do redutor", 30000, null, "Conferir com o instalador"),
            ItemPlano("gnv_bicos", "GNV: limpeza dos bicos de gás", 30000, null, "Óleo do gás suja os bicos"),
            ItemPlano("gnv_vistoria", "GNV: inspeção de segurança", null, 12, "Conferir exigência do Detran-RJ"),
            ItemPlano("gnv_cilindro", "GNV: requalificação do cilindro", null, 60, "Conferir data na etiqueta do cilindro"),
        )
    }
}

/**
 * Um único CSV para guardar, exportar e importar: uma linha por registro, coluna "tipo" diz qual.
 *   plano:    id, nome, intervalo_km, intervalo_meses, obs
 *   servico:  data, km, id (item), nome (descrição), custo, local, obs
 *   odometro: data, km, ref_km_app
 */
object ManutencaoCsv {
    val COLUNAS = listOf("tipo", "id", "nome", "data", "km", "intervalo_km", "intervalo_meses", "custo", "local", "obs", "ref_km_app")

    fun escrever(m: Manutencao): String = buildString {
        appendLine(COLUNAS.joinToString(","))
        fun linha(vararg pares: Pair<String, Any?>) {
            val mapa = pares.toMap()
            appendLine(COLUNAS.joinToString(",") { c -> esc(mapa[c]?.toString().orEmpty()) })
        }
        m.plano.forEach { linha("tipo" to "plano", "id" to it.id, "nome" to it.nome, "intervalo_km" to it.intervaloKm, "intervalo_meses" to it.intervaloMeses, "obs" to it.obs) }
        m.servicos.sortedBy { it.data }.forEach {
            linha("tipo" to "servico", "id" to it.itemId, "nome" to it.descricao, "data" to it.data, "km" to it.km,
                "custo" to it.custo?.let { c -> "%.2f".format(java.util.Locale.ROOT, c) }, "local" to it.local, "obs" to it.obs)
        }
        m.odometro.sortedBy { it.data }.forEach { linha("tipo" to "odometro", "data" to it.data, "km" to it.km, "ref_km_app" to it.refKmApp) }
    }

    /** Lê o CSV. Linhas com erro são ignoradas e listadas em [erros] (não derruba a importação). */
    fun ler(texto: String, erros: MutableList<String> = mutableListOf()): Manutencao {
        val linhas = texto.lines().filter { it.isNotBlank() && !it.startsWith("#") }
        if (linhas.isEmpty()) return Manutencao(emptyList(), emptyList(), emptyList())
        val cab = separar(linhas[0]).map { it.trim().lowercase() }
        val plano = mutableListOf<ItemPlano>(); val servicos = mutableListOf<Servico>(); val odo = mutableListOf<LeituraOdometro>()
        linhas.drop(1).forEachIndexed { i, l ->
            val v = separar(l)
            fun c(n: String) = cab.indexOf(n).let { if (it in v.indices) v[it].trim() else "" }
            try {
                when (c("tipo").lowercase()) {
                    "plano" -> plano += ItemPlano(c("id").ifEmpty { error("id vazio") }, c("nome"), c("intervalo_km").toIntOrNull(), c("intervalo_meses").toIntOrNull(), c("obs"))
                    "servico" -> servicos += Servico(LocalDate.parse(c("data")), c("km").filter { it.isDigit() }.toIntOrNull(),
                        c("id").ifEmpty { null }, c("nome"), c("custo").replace(',', '.').toDoubleOrNull(), c("local"), c("obs"))
                    "odometro" -> odo += LeituraOdometro(LocalDate.parse(c("data")), c("km").filter { it.isDigit() }.toInt(), c("ref_km_app").toDoubleOrNull() ?: 0.0)
                    else -> erros += "linha ${i + 2}: tipo desconhecido '${c("tipo")}'"
                }
            } catch (e: Exception) {
                erros += "linha ${i + 2}: ${e.message}"
            }
        }
        return Manutencao(plano, servicos, odo)
    }

    private fun esc(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    fun separar(l: String): List<String> {
        val r = mutableListOf<String>(); val sb = StringBuilder(); var aspas = false; var i = 0
        while (i < l.length) {
            val ch = l[i]
            when {
                aspas && ch == '"' && i + 1 < l.length && l[i + 1] == '"' -> { sb.append('"'); i++ }
                ch == '"' -> aspas = !aspas
                ch == ',' && !aspas -> { r += sb.toString(); sb.setLength(0) }
                else -> sb.append(ch)
            }
            i++
        }
        r += sb.toString()
        return r
    }
}
