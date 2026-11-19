package com.soswf.obd.core

/** Texto do relatório de descoberta: legível por pessoa e completo o bastante para análise. */
object Relatorio {

    fun texto(r: RelatorioDescoberta, cabecalhoExtra: Map<String, String> = emptyMap()): String = buildString {
        appendLine("RELATÓRIO DE DESCOBERTA OBD")
        appendLine("=".repeat(40))
        cabecalhoExtra.forEach { (k, v) -> appendLine("$k: $v") }
        appendLine()

        appendLine("## Adaptador")
        appendLine("Versão declarada: ${r.adaptador.versao}")
        r.adaptador.descricao?.let { appendLine("Descrição (AT@1): $it") }
        appendLine("Protocolo: ${r.adaptador.protocolo} (código ${r.adaptador.protocoloCodigo})")
        r.adaptador.tensao?.let { appendLine("Tensão no conector: $it") }
        r.latenciaMediaMs?.let { appendLine("Tempo médio por pedido: $it ms (~${1000 / maxOf(it, 1)} leituras/s)") }
        appendLine("Respostas a comandos de identificação:")
        r.comandosAt.forEach { (cmd, resp) -> appendLine("  $cmd -> ${resp ?: "(não reconhecido)"}") }
        appendLine()

        appendLine("## Veículo")
        appendLine("VIN: ${r.vin ?: "não informado"}")
        r.calibracao?.let { appendLine("Calibração: $it") }
        r.nomeEcu?.let { appendLine("Central: $it") }
        appendLine("Centrais que responderam: ${r.pidsPorEcu.keys.joinToString().ifEmpty { "nenhuma" }}")
        r.pidsPorEcu.forEach { (ecu, pids) ->
            appendLine("  $ecu: ${pids.filterNot { Pids.ehMapa(it) }.size} PIDs — " +
                pids.filterNot { Pids.ehMapa(it) }.sorted().joinToString(" ") { "%02X".format(it) })
        }
        appendLine()

        appendLine("## Diagnóstico")
        r.prontidao?.let { p ->
            appendLine("Luz de injeção: ${if (p.luzAcesa) "ACESA" else "apagada"} | códigos registrados: ${p.qtdCodigos}")
            appendLine("Monitores de prontidão:")
            p.monitores.filter { it.suportado }.forEach {
                appendLine("  [${if (it.completo) "ok" else "INCOMPLETO"}] ${it.nome}")
            }
        }
        fun lista(titulo: String, l: List<Dtc>) {
            appendLine("$titulo: ${if (l.isEmpty()) "nenhum" else ""}")
            l.forEach { appendLine("  $it — ${CodigosFalha.explicar(it).descricao}") }
        }
        lista("Códigos confirmados", r.confirmados)
        lista("Códigos pendentes", r.pendentes)
        lista("Códigos permanentes", r.permanentes)
        r.freezeDtc?.let { d ->
            appendLine("Freeze frame (causado por $d):")
            r.freezeFrame.forEach { appendLine("  ${it.nome}: ${it.formatado()}") }
        }
        appendLine("Testes do Modo 06 suportados: ${r.mids06.sorted().joinToString(" ") { "%02X".format(it) }.ifEmpty { "nenhum" }}")
        val falhas = Diagnostico.falhasIgnicao(r.testes06)
        if (falhas.isNotEmpty()) {
            appendLine("Falhas de ignição por cilindro (ciclo atual / média 10 ciclos):")
            falhas.forEach { appendLine("  Cilindro ${it.cilindro}: ${it.cicloAtual ?: "—"} / ${it.media10Ciclos ?: "—"}") }
        }
        appendLine()

        appendLine("## Leituras (motor parado ou em marcha lenta no momento do teste)")
        r.leituras.groupBy { Pids.porPid[it.pid]?.grupo ?: "Outros" }.toSortedMap().forEach { (grupo, ls) ->
            appendLine("[$grupo]")
            ls.forEach { l ->
                val origem = "(${l.ecu} PID ${"%02X".format(l.pid)})"
                l.valores.forEach { v -> appendLine("  ${v.nome}: ${v.formatado()}   $origem") }
            }
        }
        appendLine()

        appendLine("## Sondagens Toyota (experimental, resposta bruta)")
        r.sondagens.forEach { appendLine("  ${it.destino} ${it.pedido} -> ${it.resposta}") }
        appendLine()

        if (r.avisos.isNotEmpty()) {
            appendLine("## Avisos")
            r.avisos.forEach { appendLine("  - $it") }
            appendLine()
        }

        appendLine("## Transcrição completa (${r.transcricao.size} trocas)")
        r.transcricao.forEach { t ->
            appendLine(">${t.comando}  [${t.ms} ms]")
            appendLine("  " + ElmParser.linhas(t.resposta).joinToString(" / "))
        }
    }
}
