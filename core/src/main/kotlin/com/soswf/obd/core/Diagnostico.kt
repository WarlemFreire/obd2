package com.soswf.obd.core

/** Código de falha, ex.: "P0171". */
@JvmInline
value class Dtc(val codigo: String) {
    override fun toString() = codigo

    companion object {
        private val letras = charArrayOf('P', 'C', 'B', 'U')

        fun de(a: Int, b: Int): Dtc {
            val letra = letras[a shr 6]
            val d1 = (a shr 4) and 0x03
            val d2 = a and 0x0F
            return Dtc("$letra$d1${d2.toString(16)}${(b shr 4).toString(16)}${(b and 0x0F).toString(16)}".uppercase())
        }
    }
}

object Diagnostico {

    /**
     * Lista de códigos de uma resposta dos serviços 03, 07 ou 0A (já sem o byte 43/47/4A).
     * CAN traz um byte de contagem antes dos pares; protocolos antigos não.
     */
    fun codigos(payload: List<Int>, can: Boolean): List<Dtc> {
        val pares = if (can && payload.isNotEmpty()) payload.drop(1) else payload
        return pares.chunked(2)
            .filter { it.size == 2 && (it[0] != 0 || it[1] != 0) }
            .map { Dtc.de(it[0], it[1]) }
            .distinct()
    }

    data class Monitor(val nome: String, val suportado: Boolean, val completo: Boolean)

    data class Prontidao(
        val luzAcesa: Boolean,
        val qtdCodigos: Int,
        val ignicaoPorCompressao: Boolean,
        val monitores: List<Monitor>,
    ) {
        val pendentes get() = monitores.filter { it.suportado && !it.completo }
    }

    /** PID 01 (bytes A B C D). Nos bits de "completo" a norma usa 0 = pronto, 1 = incompleto. */
    fun prontidao(b: List<Int>): Prontidao {
        val (a, bb, c, d) = b
        val diesel = bb and 0x08 != 0
        val continuos = listOf(
            Monitor("Falha de ignição (misfire)", bb and 0x01 != 0, bb and 0x10 == 0),
            Monitor("Sistema de combustível", bb and 0x02 != 0, bb and 0x20 == 0),
            Monitor("Componentes", bb and 0x04 != 0, bb and 0x40 == 0),
        )
        val nomes = if (!diesel) listOf(
            "Catalisador", "Catalisador aquecido", "Sistema evaporativo (EVAP)", "Ar secundário",
            "Ar-condicionado (obsoleto)", "Sondas lambda", "Aquecimento das sondas", "EGR / VVT",
        ) else listOf(
            "Catalisador NMHC", "Catalisador NOx/SCR", "(reservado)", "Pressão do turbo",
            "(reservado)", "Sensor de gases de escape", "Filtro de partículas", "EGR / VVT",
        )
        val periodicos = nomes.mapIndexedNotNull { i, nome ->
            val bit = 1 shl i
            if (nome.startsWith("(")) null
            else Monitor(nome, c and bit != 0, d and bit == 0)
        }
        return Prontidao(a and 0x80 != 0, a and 0x7F, diesel, continuos + periodicos)
    }

    /** Um resultado de teste do Modo 06 (formato CAN). */
    data class Teste06(val mid: Int, val tid: Int, val uasid: Int, val valor: Int, val min: Int, val max: Int) {
        val aprovado get() = valor in min..max
    }

    /** Modo 06 CAN: 46 MID [TID UASID V V MIN MIN MAX MAX]... (recebe já sem o 46). */
    fun modo06(payload: List<Int>): List<Teste06> {
        if (payload.isEmpty()) return emptyList()
        val mid = payload[0]
        if (mid % 0x20 == 0) return emptyList() // mapa de suporte, não resultado
        return payload.drop(1).chunked(8).filter { it.size == 8 }.map { r ->
            Teste06(
                mid, r[0], r[1],
                (r[2] shl 8) or r[3], (r[4] shl 8) or r[5], (r[6] shl 8) or r[7],
            )
        }
    }

    data class FalhasCilindro(val cilindro: Int, val cicloAtual: Int?, val media10Ciclos: Int?)

    /**
     * MIDs A2..AD = cilindros 1..12. TID 0B = média (EWMA) dos últimos 10 ciclos,
     * TID 0C = contagem do ciclo atual/último. Ambos em contagens (UASID 0x24).
     */
    fun falhasIgnicao(testes: List<Teste06>): List<FalhasCilindro> =
        testes.filter { it.mid in 0xA2..0xAD }
            .groupBy { it.mid - 0xA1 }
            .map { (cil, ts) ->
                FalhasCilindro(
                    cil,
                    ts.firstOrNull { it.tid == 0x0C }?.valor,
                    ts.firstOrNull { it.tid == 0x0B }?.valor,
                )
            }.sortedBy { it.cilindro }

    /** VIN do serviço 09 PID 02 (recebe já sem "49 02"). CAN traz 1 byte de contagem antes. */
    fun vin(payload: List<Int>): String {
        val ascii = payload.filter { it in 0x20..0x7E }.map { it.toChar() }.joinToString("")
        return if (ascii.length > 17) ascii.takeLast(17) else ascii
    }

    /** Textos ASCII do serviço 09 (calibração, nome da ECU). */
    fun texto09(payload: List<Int>): String =
        payload.drop(1).map { if (it in 0x20..0x7E) it.toChar() else ' ' }.joinToString("").trim().replace(Regex(" {2,}"), " | ")
}
