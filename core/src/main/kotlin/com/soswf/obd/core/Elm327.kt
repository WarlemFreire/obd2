package com.soswf.obd.core

/** Uma troca registrada: o que foi enviado, o que voltou e quanto demorou. Vai inteira no relatório. */
data class Troca(val comando: String, val resposta: String, val ms: Long)

data class InfoAdaptador(
    val versao: String,
    val descricao: String?,
    val protocolo: String,
    val protocoloCodigo: String,
    val tensao: String?,
)

/**
 * Sessão com o ELM327. Bloqueante: chame de uma thread de IO.
 *
 * Segurança: só envia serviços OBD de leitura. Apagar códigos (04), escrever
 * (2E, 3B), rotinas (31), controle de atuadores (2F, 30) e afins são recusados
 * aqui, antes de chegar ao adaptador.
 */
class Elm327(private val link: ElmLink) {

    val transcricao: MutableList<Troca> = mutableListOf()
    var cabecalho: Cabecalho = Cabecalho.CAN_11
        private set

    /** Cabeçalho de destino atual (null = padrão funcional 7DF). */
    private var destino: String? = null

    fun bruto(comando: String, timeoutMs: Long = 1500): String {
        val ini = System.currentTimeMillis()
        link.send(comando + "\r")
        val r = try {
            link.readUntilPrompt(timeoutMs)
        } catch (e: ElmTimeoutException) {
            transcricao += Troca(comando, e.parcial + " <<TEMPO ESGOTADO>>", System.currentTimeMillis() - ini)
            throw e
        }
        transcricao += Troca(comando, r, System.currentTimeMillis() - ini)
        return r
    }

    /** Comando AT; devolve a primeira linha útil (ou "" se nenhuma). */
    fun at(comando: String, timeoutMs: Long = 1500): String =
        ElmParser.linhas(bruto(comando, timeoutMs), comando).firstOrNull() ?: ""

    /** Comando AT tolerante: devolve null se o adaptador não reconhece ou não responde. */
    fun atOpcional(comando: String): String? = try {
        at(comando).takeUnless { it == "?" || it.isEmpty() }
    } catch (e: ElmTimeoutException) {
        null
    }

    fun inicializar(): InfoAdaptador {
        // ATZ reinicia o chip; alguns clones demoram ~1 s.
        val z = ElmParser.linhas(bruto("ATZ", 4000), "ATZ").lastOrNull() ?: ""
        at("ATE0")      // sem eco
        at("ATL0")      // sem quebra de linha extra
        at("ATS1")      // com espaços entre bytes (parser mais robusto)
        at("ATH1")      // com cabeçalho: identifica qual central respondeu
        atOpcional("ATAT1") // tempo de espera adaptativo
        at("ATSP0")     // protocolo automático
        // O primeiro pedido dispara a busca do protocolo. Pode levar até ~10 s.
        bruto("0100", 12000)
        val dpn = at("ATDPN").trim().uppercase()
        val codigo = dpn.removePrefix("A")
        cabecalho = when (codigo) {
            "6", "8" -> Cabecalho.CAN_11
            "7", "9" -> Cabecalho.CAN_29
            else -> {
                // Protocolos antigos: cabeçalho em formato diferente; desliga.
                at("ATH0"); Cabecalho.NENHUM
            }
        }
        return InfoAdaptador(
            versao = z,
            descricao = atOpcional("AT@1"),
            protocolo = atOpcional("ATDP") ?: "?",
            protocoloCodigo = dpn,
            tensao = atOpcional("ATRV"),
        )
    }

    fun tensao(): Double? = atOpcional("ATRV")?.removeSuffix("V")?.trim()?.toDoubleOrNull()

    /** Direciona os próximos pedidos a uma central (ex.: "7E1" = câmbio). null volta ao padrão. */
    fun destinar(cab: String?) {
        if (cab == destino) return
        at(if (cab == null) "ATSH7DF" else "ATSH$cab")
        destino = cab
    }

    fun pedir(hex: String, timeoutMs: Long = 1500): Resultado {
        val cmd = hex.replace(" ", "").uppercase()
        require(cmd.length >= 2 && cmd.matches(Regex("[0-9A-F]+"))) { "Pedido inválido: $hex" }
        val servico = cmd.substring(0, 2)
        require(servico in SERVICOS_LEITURA) { "Serviço $servico bloqueado: o app só lê." }
        val bruto = try {
            bruto(cmd, timeoutMs)
        } catch (e: ElmTimeoutException) {
            return Resultado.Erro(ErroElm.TEMPO_ESGOTADO, e.parcial)
        }
        return ElmParser.interpretar(bruto, cmd, cabecalho)
    }

    fun fechar() = link.close()

    companion object {
        /** 01..0A = OBD padrão (exceto 04, apagar); 21/22 = leitura de dados do fabricante. */
        val SERVICOS_LEITURA = setOf("01", "02", "03", "05", "06", "07", "09", "0A", "21", "22")
    }
}
