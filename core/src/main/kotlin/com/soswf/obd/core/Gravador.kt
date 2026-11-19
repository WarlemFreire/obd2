package com.soswf.obd.core

import java.io.Writer
import java.util.Locale

/**
 * Grava uma viagem em CSV, uma linha por ciclo de leitura.
 *
 * Taxa: o adaptador do Etios faz ~20 pedidos/s e o carro suporta 39 PIDs. Ler tudo em toda linha
 * daria uma linha a cada ~2 s. Por isso:
 *  - PIDs rápidos (rotação, velocidade, carga, borboleta, ar, mistura, sonda) entram em TODA linha;
 *  - PIDs lentos (temperaturas, tensão, combustível...) se revezam, [lentosPorCiclo] por linha;
 *  - a cada [cicloBloco21] ciclos, o bloco de fábrica Toyota (serviço 21) bruto, em hexadecimal;
 *  - a cada [cicloDiagnostico] ciclos, códigos pendentes e falhas de ignição por cilindro.
 *
 * Célula vazia = não lido nesta linha. Nunca repete o valor anterior.
 */
class Gravador(
    private val leitor: Leitor,
    pidsSuportados: Collection<Int>,
    private val saida: Writer,
    cabecalho: Map<String, String>,
    private val extras: List<String> = emptyList(),
    private val lerExtras: () -> Map<String, String> = { emptyMap() },
    private val relogio: () -> Long = System::currentTimeMillis,
    private val lentosPorCiclo: Int = 3,
    private val cicloBloco21: Int = 30,
    private val cicloDiagnostico: Int = 60,
    midsFalhas: Collection<Int> = emptyList(),
) {
    private val suportados = pidsSuportados.filter { it in Pids.porPid && !Pids.ehMapa(it) && it != 0x01 }.distinct().sorted()
    val rapidos: List<Int> = suportados.filter { it in RAPIDOS }
    val lentos: List<Int> = suportados.filter { it !in RAPIDOS }
    private val mids = midsFalhas.filter { it in 0xA2..0xAD }.sorted()

    /** Uma coluna por valor decodificado; o nome vem do próprio decodificador. */
    private val colunasPid: List<Pair<Int, String>> = suportados.flatMap { pid ->
        nomesDosValores(pid).map { pid to it }
    }
    private val colunasCilindros = mids.map { "falhas_cil${it - 0xA1}_ciclo" }
    val colunas: List<String> = listOf("t_epoch_ms", "t_s", "ciclo") +
        colunasPid.map { (pid, nome) -> "%02X %s".format(pid, nome) } +
        listOf("consumo_L_h_estimado", "consumo_km_l_estimado") +
        listOf("toyota_2101_hex", "toyota_2181_hex", "codigos_pendentes") + colunasCilindros + extras + listOf("falhas_leitura")

    private val inicio = relogio()
    var ciclo = 0
        private set
    private var proximoLento = 0
    private var ultimoEtanol: Double? = null
    private var teorReal = false
    var linhas = 0
        private set

    init {
        saida.write("# Gravação OBD — célula vazia = não lido nesta linha\n")
        cabecalho.forEach { (k, v) -> saida.write("# $k: ${v.replace('\n', ' ')}\n") }
        saida.write("# PIDs rápidos (toda linha): ${rapidos.joinToString(" ") { "%02X".format(it) }}\n")
        saida.write("# PIDs lentos ($lentosPorCiclo por linha, em rodízio): ${lentos.joinToString(" ") { "%02X".format(it) }}\n")
        saida.write(colunas.joinToString(",") { csv(it) } + "\n")
        saida.flush()
    }

    /** Lê um ciclo e escreve uma linha. Devolve os valores lidos (para a tela mostrar ao vivo). */
    fun ciclo(): Map<Int, List<Valor>> {
        val t = relogio()
        val celulas = HashMap<String, String>()
        val lidos = LinkedHashMap<Int, List<Valor>>()
        var falhas = 0

        val estaVez = buildList {
            addAll(rapidos)
            repeat(minOf(lentosPorCiclo, lentos.size)) {
                add(lentos[proximoLento % lentos.size]); proximoLento++
            }
        }
        for (pid in estaVez) {
            val l = leitor.ler(pid)
            val valores = l.firstOrNull { it.ecu == "7E8" }?.valores ?: l.firstOrNull()?.valores
            if (valores == null) { falhas++; continue }
            lidos[pid] = valores
            valores.forEach { v -> celulas["%02X %s".format(pid, v.nome)] = v.numero?.let(::num) ?: v.texto ?: "" }
        }

        // Consumo estimado pelo ar (ver Consumo): precisa de MAF nesta linha e do último teor de etanol.
        lidos[0x52]?.firstOrNull()?.numero?.let { ultimoEtanol = it; teorReal = true }
        if (!teorReal) lidos[0x51]?.firstOrNull()?.texto?.let { Consumo.etanolPeloTipo(it) }?.let { ultimoEtanol = it }
        val maf = lidos[0x10]?.firstOrNull()?.numero
        val etanol = ultimoEtanol
        if (maf != null && etanol != null) {
            val lh = Consumo.litrosPorHora(maf, etanol, lidos[0x44]?.firstOrNull()?.numero ?: 1.0)
            celulas["consumo_L_h_estimado"] = num(lh, 3)
            val vel = lidos[0x0D]?.firstOrNull()?.numero
            if (vel != null && vel >= 3 && lh > 0.05) celulas["consumo_km_l_estimado"] = num(vel / lh, 2)
        }

        if (ciclo % cicloBloco21 == 0) {
            celulas["toyota_2101_hex"] = bloco21("2101")
            celulas["toyota_2181_hex"] = bloco21("2181")
        }
        if (ciclo % cicloDiagnostico == 0) {
            celulas["codigos_pendentes"] = leitor.codigos("07")?.joinToString(" ")?.ifEmpty { "nenhum" } ?: "sem resposta"
            if (mids.isNotEmpty()) {
                Diagnostico.falhasIgnicao(leitor.modo06(mids)).forEach { f ->
                    f.cicloAtual?.let { celulas["falhas_cil${f.cilindro}_ciclo"] = it.toString() }
                }
            }
        }
        celulas.putAll(lerExtras())
        celulas["falhas_leitura"] = falhas.toString()
        celulas["t_epoch_ms"] = t.toString()
        celulas["t_s"] = num((t - inicio) / 1000.0, 3)
        celulas["ciclo"] = ciclo.toString()

        saida.write(colunas.joinToString(",") { csv(celulas[it] ?: "") } + "\n")
        saida.flush() // linha a linha: se o app morrer, o arquivo fica com tudo até ali
        linhas++
        ciclo++
        return lidos
    }

    private fun bloco21(pedido: String): String {
        val elm = leitor.elm
        return try {
            elm.destinar("7E0")
            val r = elm.pedir(pedido)
            (r as? Resultado.Ok)?.respostas?.firstOrNull { it.bytes.firstOrNull() == 0x61 }
                ?.bytes?.joinToString(" ") { "%02X".format(it) } ?: ""
        } finally {
            elm.destinar(null)
        }
    }

    companion object {
        /** Mudam rápido e importam para analisar acelerações, trocas de marcha e mistura. */
        val RAPIDOS = setOf(0x04, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0E, 0x10, 0x11, 0x15, 0x24, 0x34, 0x43, 0x44, 0x45, 0x49, 0x4C, 0x65)

        private fun nomesDosValores(pid: Int): List<String> {
            val def = Pids.porPid[pid] ?: return emptyList()
            // Decodifica bytes de exemplo só para descobrir os nomes das colunas. Vários padrões,
            // porque alguns valores só aparecem com certos bits (PID 65, sonda com B != FF, A4).
            val nomes = LinkedHashSet<String>()
            for (b in listOf(0x01, 0x02, 0x03, 0x07, 0x00)) {
                Pids.decodificar(pid, List(def.bytes) { b }).orEmpty().forEach { nomes += it.nome }
            }
            return nomes.toList().ifEmpty { listOf(def.nome) }
        }

        private fun num(x: Double, casas: Int = 4): String =
            String.format(Locale.ROOT, "%.${casas}f", x).trimEnd('0').trimEnd('.')

        private fun csv(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }
}
