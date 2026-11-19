package com.soswf.obd.core

data class LeituraPid(val ecu: String, val pid: Int, val valores: List<Valor>)
data class Sondagem(val destino: String, val pedido: String, val resposta: String)

data class RelatorioDescoberta(
    val adaptador: InfoAdaptador,
    val comandosAt: Map<String, String?>,
    val latenciaMediaMs: Long?,
    val pidsPorEcu: Map<String, Set<Int>>,
    val leituras: List<LeituraPid>,
    val vin: String?,
    val calibracao: String?,
    val nomeEcu: String?,
    val prontidao: Diagnostico.Prontidao?,
    val confirmados: List<Dtc>,
    val pendentes: List<Dtc>,
    val permanentes: List<Dtc>,
    val freezeDtc: Dtc?,
    val freezeFrame: List<Valor>,
    val mids06: Set<Int>,
    val testes06: List<Diagnostico.Teste06>,
    val sondagens: List<Sondagem>,
    val avisos: List<String>,
    val transcricao: List<Troca>,
)

/** Operações de leitura reaproveitadas pela descoberta e pelas telas. */
class Leitor(val elm: Elm327) {

    private val can get() = elm.cabecalho != Cabecalho.NENHUM

    /** Respostas positivas: payload que começa com [svc + 0x40] e, se [pid] dado, com o PID. */
    private fun positivas(r: Resultado, svc: Int, pid: Int? = null): List<EcuResposta> =
        (r as? Resultado.Ok)?.respostas.orEmpty().filter {
            it.bytes.isNotEmpty() && it.bytes[0] == svc + 0x40 && (pid == null || it.bytes.getOrNull(1) == pid)
        }

    /** Mapa de suporte encadeado (00, 20, 40...) por central. [prefixo] = "01", "06", "09". */
    fun suporte(prefixo: String, sufixo: String = ""): Map<String, Set<Int>> {
        val svc = prefixo.toInt(16)
        val porEcu = LinkedHashMap<String, MutableSet<Int>>()
        var base = 0
        while (base <= 0xE0) {
            val r = elm.pedir("%s%02X%s".format(prefixo, base, sufixo))
            val oks = positivas(r, svc, base)
            if (oks.isEmpty()) break
            for (e in oks) {
                val dados = e.bytes.drop(2 + sufixo.length / 2)
                if (dados.size >= 4) porEcu.getOrPut(e.ecu) { mutableSetOf() } += Pids.suportados(base, dados)
            }
            val proximo = base + 0x20
            if (porEcu.values.none { proximo in it }) break
            base = proximo
        }
        return porEcu
    }

    fun ler(pid: Int): List<LeituraPid> {
        val r = elm.pedir("01%02X".format(pid))
        return positivas(r, 0x01, pid).mapNotNull { e ->
            Pids.decodificar(pid, e.bytes.drop(2))?.let { LeituraPid(e.ecu, pid, it) }
        }
    }

    fun codigos(servico: String): List<Dtc> {
        val r = elm.pedir(servico)
        return positivas(r, servico.toInt(16)).flatMap { Diagnostico.codigos(it.bytes.drop(1), can) }.distinct()
    }

    fun prontidao(): Diagnostico.Prontidao? =
        positivas(elm.pedir("0101"), 0x01, 0x01).firstOrNull()?.bytes?.drop(2)
            ?.takeIf { it.size >= 4 }?.let { Diagnostico.prontidao(it) }

    fun freezeFrame(): Pair<Dtc?, List<Valor>> {
        val causa = positivas(elm.pedir("020200"), 0x02, 0x02).firstOrNull()?.bytes?.drop(3)
            ?.takeIf { it.size >= 2 && (it[0] != 0 || it[1] != 0) }?.let { Dtc.de(it[0], it[1]) }
            ?: return null to emptyList()
        val pids = suporte("02", "00").values.flatten().toSortedSet().filterNot { Pids.ehMapa(it) || it == 0x02 }
        val valores = pids.flatMap { pid ->
            positivas(elm.pedir("02%02X00".format(pid)), 0x02, pid).firstOrNull()
                ?.let { Pids.decodificar(pid, it.bytes.drop(3)) }.orEmpty()
        }
        return causa to valores
    }

    fun modo06(mids: Collection<Int>): List<Diagnostico.Teste06> = mids.flatMap { mid ->
        positivas(elm.pedir("06%02X".format(mid)), 0x06, mid).flatMap { Diagnostico.modo06(it.bytes.drop(1)) }
    }

    fun servico09(pid: Int): List<Int>? =
        positivas(elm.pedir("09%02X".format(pid), 3000), 0x09, pid).firstOrNull()?.bytes?.drop(2)
}

object Descoberta {

    /** Comandos AT inofensivos que revelam o chip real por trás do "ELM327 v1.5". */
    private val sondasAt = listOf("ATI", "AT@1", "ATCS", "ATIGN", "STI", "STDI")

    /**
     * Pedidos de fabricante Toyota só de leitura (serviço 21). Resposta bruta vai para o
     * relatório; a decodificação vem depois, com os dados reais do carro.
     * 7E0 = motor, 7E1 = câmbio automático.
     */
    private val sondasToyota = listOf(
        "7E1" to "0100", "7E1" to "0120", "7E1" to "2101", "7E1" to "2182", "7E1" to "2183", "7E1" to "21D9",
        "7E0" to "2101", "7E0" to "2181",
    )

    fun executar(elm: Elm327, progresso: (String) -> Unit = {}): RelatorioDescoberta {
        val avisos = mutableListOf<String>()
        fun <T> etapa(nome: String, padrao: T, bloco: () -> T): T {
            progresso(nome)
            return try {
                bloco()
            } catch (e: Exception) {
                if (e is java.io.IOException && e !is ElmTimeoutException) throw e
                avisos += "$nome: ${e.message}"
                padrao
            }
        }

        progresso("Inicializando adaptador")
        val info = elm.inicializar()
        val leitor = Leitor(elm)

        val ats = etapa("Identificando o chip do adaptador", emptyMap<String, String?>()) {
            sondasAt.associateWith { elm.atOpcional(it) }
        }

        val latencia = etapa("Medindo velocidade de resposta", null as Long?) {
            val antes = elm.transcricao.size
            repeat(10) { elm.pedir("010C") }
            elm.transcricao.drop(antes).map { it.ms }.average().toLong()
        }

        val pids = etapa("Listando parâmetros suportados", emptyMap<String, Set<Int>>()) { leitor.suporte("01") }

        val todos = pids.values.flatten().toSortedSet().filterNot { Pids.ehMapa(it) }
        val leituras = etapa("Lendo ${todos.size} parâmetros", emptyList<LeituraPid>()) {
            todos.flatMap { leitor.ler(it) }
        }
        todos.filter { it !in Pids.porPid }.takeIf { it.isNotEmpty() }?.let { sem ->
            avisos += "PIDs suportados sem decodificador no app: " + sem.joinToString { "%02X".format(it) }
        }

        val sup09 = etapa("Identificação do veículo", emptyMap<String, Set<Int>>()) { leitor.suporte("09") }
        val tem09 = sup09.values.flatten().toSet()
        val vin = etapa("Lendo VIN", null as String?) {
            if (2 in tem09) leitor.servico09(0x02)?.let { Diagnostico.vin(it) } else null
        }
        val calid = etapa("Lendo calibração", null as String?) {
            if (4 in tem09) leitor.servico09(0x04)?.let { Diagnostico.texto09(it) } else null
        }
        val nome = etapa("Lendo nome da central", null as String?) {
            if (0x0A in tem09) leitor.servico09(0x0A)?.let { Diagnostico.texto09(it) } else null
        }

        val pront = etapa("Monitores de prontidão", null as Diagnostico.Prontidao?) { leitor.prontidao() }
        val conf = etapa("Códigos confirmados", emptyList<Dtc>()) { leitor.codigos("03") }
        val pend = etapa("Códigos pendentes", emptyList<Dtc>()) { leitor.codigos("07") }
        val perm = etapa("Códigos permanentes", emptyList<Dtc>()) { leitor.codigos("0A") }
        val (ffDtc, ff) = etapa("Freeze frame", null as Dtc? to emptyList<Valor>()) { leitor.freezeFrame() }

        val mids = etapa("Testes de bordo (Modo 06)", emptySet<Int>()) {
            leitor.suporte("06").values.flatten().filterNot { Pids.ehMapa(it) }.toSet()
        }
        val testes = etapa("Contagem de falhas de ignição", emptyList<Diagnostico.Teste06>()) {
            leitor.modo06(mids.filter { it in 0xA1..0xAD })
        }

        val sondagens = etapa("Sondando dados Toyota (câmbio)", emptyList<Sondagem>()) {
            val r = sondasToyota.map { (dest, ped) ->
                require(ped.substring(0, 2) in Elm327.SERVICOS_LEITURA)
                elm.destinar(dest)
                val bruto = try {
                    elm.bruto(ped, 2000)
                } catch (e: ElmTimeoutException) {
                    e.parcial + " <<TEMPO ESGOTADO>>"
                }
                Sondagem(dest, ped, ElmParser.linhas(bruto, ped).joinToString(" / "))
            }
            elm.destinar(null)
            r
        }

        return RelatorioDescoberta(
            info, ats, latencia, pids, leituras, vin, calid, nome, pront, conf, pend, perm, ffDtc, ff,
            mids, testes, sondagens, avisos, elm.transcricao.toList(),
        )
    }
}
