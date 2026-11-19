package com.soswf.obd.core

/** Resposta de uma central (ECU) a um pedido, já sem cabeçalho e sem bytes de controle ISO-TP. */
data class EcuResposta(val ecu: String, val bytes: List<Int>)

sealed class Resultado {
    /** Uma entrada por central que respondeu. */
    data class Ok(val respostas: List<EcuResposta>) : Resultado() {
        fun primeira(): EcuResposta? = respostas.firstOrNull()
    }

    data class Erro(val tipo: ErroElm, val bruto: String) : Resultado()
}

enum class ErroElm(val mensagem: String) {
    SEM_DADOS("Sem dados: a central não suporta esse pedido"),
    NAO_ENTENDIDO("Adaptador não reconheceu o comando (?)"),
    SEM_CONEXAO_CARRO("Não conseguiu falar com o carro (ignição ligada?)"),
    ERRO_BARRAMENTO("Erro no barramento CAN"),
    INTERROMPIDO("Pedido interrompido"),
    ESTOURO_BUFFER("Buffer do adaptador estourou"),
    TEMPO_ESGOTADO("Adaptador não respondeu a tempo"),
    INVALIDO("Resposta ilegível"),
}

/** Formato dos cabeçalhos que o adaptador vai devolver. */
enum class Cabecalho { CAN_11, CAN_29, NENHUM }

object ElmParser {

    private val erros = listOf(
        "NO DATA" to ErroElm.SEM_DADOS,
        "UNABLE TO CONNECT" to ErroElm.SEM_CONEXAO_CARRO,
        "BUS INIT: ...ERROR" to ErroElm.SEM_CONEXAO_CARRO,
        "BUS INIT:...ERROR" to ErroElm.SEM_CONEXAO_CARRO,
        "CAN ERROR" to ErroElm.ERRO_BARRAMENTO,
        "BUS ERROR" to ErroElm.ERRO_BARRAMENTO,
        "BUS BUSY" to ErroElm.ERRO_BARRAMENTO,
        "FB ERROR" to ErroElm.ERRO_BARRAMENTO,
        "DATA ERROR" to ErroElm.ERRO_BARRAMENTO,
        "RX ERROR" to ErroElm.ERRO_BARRAMENTO,
        "STOPPED" to ErroElm.INTERROMPIDO,
        "BUFFER FULL" to ErroElm.ESTOURO_BUFFER,
    )

    /** Linhas úteis: sem eco, sem "SEARCHING...", sem vazias. */
    fun linhas(bruto: String, comandoEnviado: String? = null): List<String> {
        val eco = comandoEnviado?.trim()?.uppercase()?.replace(" ", "")
        return bruto.replace("\r", "\n").split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { it.startsWith("SEARCHING") }
            .filterNot { it == "BUS INIT: ...OK" || it == "BUS INIT:...OK" || it == "BUS INIT: ..." }
            .filterNot { eco != null && it.uppercase().replace(" ", "") == eco }
    }

    fun interpretar(bruto: String, comando: String, cabecalho: Cabecalho): Resultado {
        val ls = linhas(bruto, comando)
        if (ls.isEmpty()) return Resultado.Erro(ErroElm.SEM_DADOS, bruto)
        for (l in ls) {
            if (l == "?") return Resultado.Erro(ErroElm.NAO_ENTENDIDO, bruto)
            val u = l.uppercase()
            erros.firstOrNull { u.contains(it.first) }?.let {
                // "NO DATA" junto com respostas válidas de outra central: ignora a linha.
                if (it.second != ErroElm.SEM_DADOS || ls.size == 1) return Resultado.Erro(it.second, bruto)
            }
        }
        val uteis = ls.filterNot { l -> erros.any { l.uppercase().contains(it.first) } }
        return try {
            val respostas = when (cabecalho) {
                Cabecalho.CAN_11, Cabecalho.CAN_29 -> comCabecalho(uteis, cabecalho)
                Cabecalho.NENHUM -> semCabecalho(uteis)
            }
            if (respostas.isEmpty()) Resultado.Erro(ErroElm.INVALIDO, bruto) else Resultado.Ok(respostas)
        } catch (e: IllegalArgumentException) {
            Resultado.Erro(ErroElm.INVALIDO, bruto)
        }
    }

    private fun hexBytes(tokens: List<String>): List<Int> = tokens.map {
        require(it.length == 2) { "token inválido: $it" }
        it.toInt(16)
    }

    private fun tokens(l: String): List<String> {
        val t = l.trim().split(Regex("\\s+"))
        // Adaptador com espaços desligados (ATS0): quebra em pares.
        return if (t.size == 1 && t[0].length > 3) t[0].chunked(2) else t
    }

    /** Agrupa quadros por central e remonta mensagens ISO-TP (multi-quadro). */
    private fun comCabecalho(linhas: List<String>, cab: Cabecalho): List<EcuResposta> {
        val porEcu = LinkedHashMap<String, MutableList<List<Int>>>()
        for (l in linhas) {
            val semEspaco = l.replace(" ", "")
            if (!semEspaco.matches(Regex("[0-9A-Fa-f]+"))) throw IllegalArgumentException(l)
            val (ecu, resto) = when (cab) {
                Cabecalho.CAN_11 -> semEspaco.substring(0, 3).uppercase() to semEspaco.substring(3)
                else -> semEspaco.substring(0, 8).uppercase() to semEspaco.substring(8)
            }
            require(resto.length % 2 == 0) { l }
            porEcu.getOrPut(ecu) { mutableListOf() }.add(resto.chunked(2).map { it.toInt(16) })
        }
        return porEcu.map { (ecu, quadros) -> EcuResposta(ecu, isoTp(quadros)) }
    }

    fun isoTp(quadros: List<List<Int>>): List<Int> {
        val primeiro = quadros.first()
        require(primeiro.isNotEmpty())
        val tipo = primeiro[0] shr 4
        return when (tipo) {
            0 -> {
                val n = primeiro[0] and 0x0F
                require(n <= primeiro.size - 1) { "tamanho" }
                primeiro.subList(1, 1 + n)
            }
            1 -> {
                val n = ((primeiro[0] and 0x0F) shl 8) or primeiro[1]
                val dados = ArrayList<Int>(n)
                dados.addAll(primeiro.drop(2))
                val seguintes = quadros.drop(1).filter { it.isNotEmpty() && it[0] shr 4 == 2 }
                for (q in seguintes) dados.addAll(q.drop(1))
                require(dados.size >= n) { "mensagem incompleta" }
                dados.subList(0, n)
            }
            else -> throw IllegalArgumentException("PCI inesperado")
        }
    }

    /** Cabeçalhos desligados: linhas soltas ou formato "014 / 0: ... / 1: ...". */
    private fun semCabecalho(linhas: List<String>): List<EcuResposta> {
        val multi = linhas.any { it.matches(Regex("^[0-9A-Fa-f]:.*")) }
        if (multi) {
            val total = linhas.first { !it.contains(":") }.trim().toInt(16)
            val dados = linhas.filter { it.contains(":") }
                .sortedBy { it.substringBefore(":").trim().toInt(16) }
                .flatMap { hexBytes(tokens(it.substringAfter(":").trim())) }
            require(dados.size >= total)
            return listOf(EcuResposta("ECU", dados.subList(0, total)))
        }
        return linhas.mapIndexed { i, l -> EcuResposta(if (i == 0) "ECU" else "ECU$i", hexBytes(tokens(l))) }
    }
}
