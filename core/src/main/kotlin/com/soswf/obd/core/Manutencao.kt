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

/**
 * Serviço feito. [itemId] null = serviço avulso (não zera nenhum item do plano).
 * [data] e [km] são opcionais: históricos antigos costumam ter só um dos dois, e o app não inventa o outro.
 */
data class Servico(
    val data: LocalDate?,
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
data class LeituraOdometro(val data: LocalDate?, val km: Int, val refKmApp: Double = 0.0)

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
        // O odômetro só cresce: a leitura mais recente é a de maior km.
        val ult = odometro.maxByOrNull { it.km } ?: return null
        return ult.km + (kmAppTotal - ult.refKmApp).coerceAtLeast(0.0).toInt()
    }

    /** Pares (data, km) conhecidos, para estimar a ordem de serviços que só têm um dos dois. */
    private val pares: List<Pair<Long, Int>> by lazy {
        (servicos.mapNotNull { s -> s.data?.let { d -> s.km?.let { d.toEpochDay() to it } } } +
            odometro.mapNotNull { o -> o.data?.let { it.toEpochDay() to o.km } })
            .distinct().sortedBy { it.first }
    }

    /**
     * Posição do serviço na linha do tempo, em km (real ou estimado por interpolação entre pares
     * data/km conhecidos). Serve só para ordenar; nunca é mostrada como km do serviço.
     */
    fun posicao(sv: Servico): Double {
        sv.km?.let { return it.toDouble() }
        val d = sv.data?.toEpochDay() ?: return Double.NEGATIVE_INFINITY
        val p = pares
        if (p.isEmpty()) return d.toDouble()
        if (p.size == 1) return p[0].second.toDouble() + (d - p[0].first) * 100.0 // ritmo arbitrário só para ordenar
        val (a, b) = when {
            d <= p.first().first -> p[0] to p[1]
            d >= p.last().first -> p[p.size - 2] to p.last()
            else -> p.zipWithNext().first { (x, y) -> d in x.first..y.first }
        }
        val dias = (b.first - a.first).takeIf { it != 0L } ?: return a.second.toDouble()
        return a.second + (b.second - a.second).toDouble() * (d - a.first) / dias
    }

    /**
     * Situação de cada item. "Atenção" quando faltam até 10% do intervalo de km ou 30 dias.
     * Vence pelo que chegar primeiro (km ou data).
     */
    fun situacao(hoje: LocalDate, odometroAtual: Int?): List<Situacao> = plano.map { item ->
        val ultimo = servicos.filter { it.itemId == item.id }.maxByOrNull { posicao(it) }
        if (ultimo == null) return@map Situacao(item, null, null, null, null, null, Estado.SEM_HISTORICO)
        val proxKm = item.intervaloKm?.let { iv -> ultimo.km?.let { it + iv } }
        val proxData = item.intervaloMeses?.let { m -> ultimo.data?.plusMonths(m.toLong()) }
        // Último serviço sem km e sem data utilizável para este intervalo: não dá para calcular vencimento.
        if (proxKm == null && proxData == null) return@map Situacao(item, ultimo, null, null, null, null, Estado.SEM_HISTORICO)
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
 *   remover:  id (tira o item do plano; serve para corrigir um plano importado antes)
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
        m.servicos.sortedBy { m.posicao(it) }.forEach {
            linha("tipo" to "servico", "id" to it.itemId, "nome" to it.descricao, "data" to it.data, "km" to it.km,
                "custo" to it.custo?.let { c -> "%.2f".format(java.util.Locale.ROOT, c) }, "local" to it.local, "obs" to it.obs)
        }
        m.odometro.sortedBy { it.km }.forEach { linha("tipo" to "odometro", "data" to it.data, "km" to it.km, "ref_km_app" to it.refKmApp) }
    }

    /** Ids de itens do plano marcados para remoção (linhas tipo "remover"). */
    fun remocoes(texto: String): List<String> {
        val linhas = texto.lines().filter { it.isNotBlank() && !it.startsWith("#") }
        if (linhas.isEmpty()) return emptyList()
        val cab = separar(linhas[0]).map { it.trim().lowercase() }
        val iTipo = cab.indexOf("tipo"); val iId = cab.indexOf("id")
        return linhas.drop(1).map { separar(it) }
            .filter { v -> v.getOrNull(iTipo)?.trim()?.lowercase() == "remover" }
            .mapNotNull { v -> v.getOrNull(iId)?.trim()?.ifEmpty { null } }
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
                    "servico" -> servicos += Servico(c("data").ifEmpty { null }?.let { LocalDate.parse(it) }, c("km").filter { it.isDigit() }.toIntOrNull(),
                        c("id").ifEmpty { null }, c("nome"), c("custo").replace(',', '.').toDoubleOrNull(), c("local"), c("obs"))
                    "remover" -> Unit // tratado em remocoes()
                    "odometro" -> odo += LeituraOdometro(c("data").ifEmpty { null }?.let { LocalDate.parse(it) }, c("km").filter { it.isDigit() }.toInt(), c("ref_km_app").toDoubleOrNull() ?: 0.0)
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
