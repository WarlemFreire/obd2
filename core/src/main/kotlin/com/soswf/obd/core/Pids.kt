package com.soswf.obd.core

/**
 * Parâmetros do Modo 01 (SAE J1979). [b] são os bytes de dados depois de "41 PID":
 * b[0] = A, b[1] = B, ...
 *
 * Um PID pode gerar mais de um valor (ex.: sonda lambda dá tensão e correção).
 */
data class Valor(val nome: String, val numero: Double?, val unidade: String, val texto: String? = null) {
    fun formatado(): String {
        if (texto != null) return texto
        if (numero == null) return "—"
        val casas = when (unidade) {
            "rpm", "km", "s", "min", "", "Nm" -> 0
            "λ" -> 3
            "V" -> 2
            else -> 1
        }
        val n = String.format(java.util.Locale.ROOT, "%.${casas}f", numero).replace('.', ',')
        return if (unidade.isEmpty()) n else "$n $unidade"
    }
}

class PidDef(
    val pid: Int,
    val nome: String,
    val bytes: Int,
    /** Grupo para organizar a tela. */
    val grupo: String,
    val decodificar: (List<Int>) -> List<Valor>,
)

object Pids {

    private fun u16(b: List<Int>, i: Int = 0) = (b[i] shl 8) or b[i + 1]
    private fun s16(b: List<Int>, i: Int = 0) = u16(b, i).let { if (it >= 0x8000) it - 0x10000 else it }
    private fun pct(x: Int) = x * 100.0 / 255
    private fun trim(x: Int) = x * 100.0 / 128 - 100

    private fun um(pid: Int, nome: String, bytes: Int, grupo: String, unidade: String, f: (List<Int>) -> Double) =
        PidDef(pid, nome, bytes, grupo) { listOf(Valor(nome, f(it), unidade)) }

    private const val MOTOR = "Motor"
    private const val COMB = "Combustível e mistura"
    private const val AR = "Ar e admissão"
    private const val SONDAS = "Sondas lambda"
    private const val EMISS = "Emissões e catalisador"
    private const val TEMP = "Temperaturas"
    private const val ELET = "Elétrica"
    private const val DIAG = "Diagnóstico"
    private const val ACEL = "Acelerador e borboleta"

    val tiposCombustivel = mapOf(
        1 to "Gasolina", 2 to "Metanol", 3 to "Etanol", 4 to "Diesel", 5 to "GLP", 6 to "GNV",
        7 to "Propano", 8 to "Elétrico", 9 to "Bicombustível gasolina", 10 to "Bicombustível metanol",
        11 to "Bicombustível etanol", 12 to "Bicombustível GLP", 13 to "Bicombustível GNV",
        14 to "Bicombustível propano", 15 to "Bicombustível elétrico", 16 to "Bicombustível elétrico/combustão",
        17 to "Híbrido gasolina", 18 to "Híbrido etanol", 19 to "Híbrido diesel", 20 to "Híbrido elétrico",
        21 to "Híbrido elétrico/combustão", 22 to "Híbrido regenerativo", 23 to "Bicombustível diesel",
    )

    private fun statusCombustivel(x: Int): String = when (x) {
        0 -> "—"
        1 -> "Malha aberta: motor frio"
        2 -> "Malha fechada (normal)"
        4 -> "Malha aberta: carga alta ou desaceleração"
        8 -> "Malha aberta: falha no sistema"
        16 -> "Malha fechada com falha em sonda"
        else -> "Código $x"
    }

    private val lista: List<PidDef> = buildList {
        add(PidDef(0x01, "Status do monitor", 4, DIAG) { b ->
            listOf(
                Valor("Luz de injeção (MIL)", null, "", if (b[0] and 0x80 != 0) "ACESA" else "apagada"),
                Valor("Códigos confirmados", (b[0] and 0x7F).toDouble(), ""),
            )
        })
        add(PidDef(0x03, "Status do sistema de combustível", 2, COMB) { b ->
            listOf(Valor("Sistema de combustível", null, "", statusCombustivel(b[0])))
        })
        add(um(0x04, "Carga calculada do motor", 1, MOTOR, "%") { pct(it[0]) })
        add(um(0x05, "Temperatura do líquido de arrefecimento", 1, TEMP, "°C") { it[0] - 40.0 })
        add(um(0x06, "Correção curta de mistura (banco 1)", 1, COMB, "%") { trim(it[0]) })
        add(um(0x07, "Correção longa de mistura (banco 1)", 1, COMB, "%") { trim(it[0]) })
        add(um(0x08, "Correção curta de mistura (banco 2)", 1, COMB, "%") { trim(it[0]) })
        add(um(0x09, "Correção longa de mistura (banco 2)", 1, COMB, "%") { trim(it[0]) })
        add(um(0x0A, "Pressão de combustível", 1, COMB, "kPa") { it[0] * 3.0 })
        add(um(0x0B, "Pressão no coletor (MAP)", 1, AR, "kPa") { it[0].toDouble() })
        add(um(0x0C, "Rotação do motor", 2, MOTOR, "rpm") { u16(it) / 4.0 })
        add(um(0x0D, "Velocidade", 1, MOTOR, "km/h") { it[0].toDouble() })
        add(um(0x0E, "Avanço de ignição", 1, MOTOR, "°") { it[0] / 2.0 - 64 })
        add(um(0x0F, "Temperatura do ar admitido", 1, TEMP, "°C") { it[0] - 40.0 })
        add(um(0x10, "Fluxo de ar (MAF)", 2, AR, "g/s") { u16(it) / 100.0 })
        add(um(0x11, "Posição da borboleta", 1, ACEL, "%") { pct(it[0]) })
        for (i in 0 until 8) {
            val pid = 0x14 + i
            val banco = if (i < 4) 1 else 2
            val sensor = i % 4 + 1
            add(PidDef(pid, "Sonda B${banco}S$sensor", 2, SONDAS) { b ->
                buildList {
                    add(Valor("Sonda B${banco}S$sensor — tensão", b[0] / 200.0, "V"))
                    if (b[1] != 0xFF) add(Valor("Sonda B${banco}S$sensor — correção curta", trim(b[1]), "%"))
                }
            })
        }
        add(PidDef(0x13, "Sondas lambda instaladas", 1, SONDAS) { b ->
            val sondas = (0 until 8).filter { b[0] and (1 shl it) != 0 }.map { "B${it / 4 + 1}S${it % 4 + 1}" }
            listOf(Valor("Sondas instaladas", null, "", sondas.joinToString().ifEmpty { "nenhuma" }))
        })
        add(PidDef(0x1C, "Norma OBD do veículo", 1, DIAG) { b ->
            val n = mapOf(1 to "OBD-II (CARB)", 2 to "OBD (EPA)", 3 to "OBD e OBD-II", 4 to "OBD-I",
                6 to "EOBD (Europa)", 7 to "EOBD e OBD-II", 13 to "JOBD (Japão)", 17 to "EMD",
                28 to "OBD-Brasil fase 1 (OBDBr-1)", 29 to "OBD-Brasil fase 2 (OBDBr-2)")
            listOf(Valor("Norma OBD", null, "", n[b[0]] ?: "Código ${b[0]}"))
        })
        add(um(0x1F, "Tempo desde a partida", 2, MOTOR, "s") { u16(it).toDouble() })
        add(um(0x21, "Distância com luz de injeção acesa", 2, DIAG, "km") { u16(it).toDouble() })
        add(um(0x22, "Pressão na flauta (relativa)", 2, COMB, "kPa") { u16(it) * 0.079 })
        add(um(0x23, "Pressão na flauta", 2, COMB, "kPa") { u16(it) * 10.0 })
        for (i in 0 until 8) {
            add(PidDef(0x24 + i, "Sonda banda larga S${i + 1} (tensão)", 4, SONDAS) { b ->
                listOf(
                    Valor("Sonda larga S${i + 1} — lambda", u16(b) * 2.0 / 65536, "λ"),
                    Valor("Sonda larga S${i + 1} — tensão", u16(b, 2) * 8.0 / 65536, "V"),
                )
            })
        }
        add(um(0x2C, "EGR comandada", 1, EMISS, "%") { pct(it[0]) })
        add(um(0x2D, "Erro da EGR", 1, EMISS, "%") { trim(it[0]) })
        add(um(0x2E, "Purga do cânister comandada", 1, EMISS, "%") { pct(it[0]) })
        add(um(0x2F, "Nível de combustível", 1, COMB, "%") { pct(it[0]) })
        add(um(0x30, "Aquecimentos desde que os códigos foram apagados", 1, DIAG, "") { it[0].toDouble() })
        add(um(0x31, "Distância desde que os códigos foram apagados", 2, DIAG, "km") { u16(it).toDouble() })
        add(um(0x32, "Pressão de vapor do EVAP", 2, EMISS, "Pa") { s16(it) / 4.0 })
        add(um(0x33, "Pressão barométrica", 1, AR, "kPa") { it[0].toDouble() })
        for (i in 0 until 8) {
            add(PidDef(0x34 + i, "Sonda banda larga S${i + 1} (corrente)", 4, SONDAS) { b ->
                listOf(
                    Valor("Sonda larga S${i + 1} — lambda", u16(b) * 2.0 / 65536, "λ"),
                    Valor("Sonda larga S${i + 1} — corrente", u16(b, 2) / 256.0 - 128, "mA"),
                )
            })
        }
        add(um(0x3C, "Temperatura do catalisador B1S1", 2, EMISS, "°C") { u16(it) / 10.0 - 40 })
        add(um(0x3D, "Temperatura do catalisador B2S1", 2, EMISS, "°C") { u16(it) / 10.0 - 40 })
        add(um(0x3E, "Temperatura do catalisador B1S2", 2, EMISS, "°C") { u16(it) / 10.0 - 40 })
        add(um(0x3F, "Temperatura do catalisador B2S2", 2, EMISS, "°C") { u16(it) / 10.0 - 40 })
        add(um(0x42, "Tensão da central (bateria/alternador)", 2, ELET, "V") { u16(it) / 1000.0 })
        add(um(0x43, "Carga absoluta", 2, MOTOR, "%") { u16(it) * 100.0 / 255 })
        add(um(0x44, "Lambda comandado", 2, COMB, "λ") { u16(it) * 2.0 / 65536 })
        add(um(0x45, "Posição relativa da borboleta", 1, ACEL, "%") { pct(it[0]) })
        add(um(0x46, "Temperatura ambiente", 1, TEMP, "°C") { it[0] - 40.0 })
        add(um(0x47, "Posição absoluta da borboleta B", 1, ACEL, "%") { pct(it[0]) })
        add(um(0x48, "Posição absoluta da borboleta C", 1, ACEL, "%") { pct(it[0]) })
        add(um(0x49, "Pedal do acelerador D", 1, ACEL, "%") { pct(it[0]) })
        add(um(0x4A, "Pedal do acelerador E", 1, ACEL, "%") { pct(it[0]) })
        add(um(0x4B, "Pedal do acelerador F", 1, ACEL, "%") { pct(it[0]) })
        add(um(0x4C, "Atuador da borboleta comandado", 1, ACEL, "%") { pct(it[0]) })
        add(um(0x4D, "Tempo com luz de injeção acesa", 2, DIAG, "min") { u16(it).toDouble() })
        add(um(0x4E, "Tempo desde que os códigos foram apagados", 2, DIAG, "min") { u16(it).toDouble() })
        add(PidDef(0x51, "Tipo de combustível", 1, COMB) { b ->
            listOf(Valor("Tipo de combustível", null, "", tiposCombustivel[b[0]] ?: "Código ${b[0]}"))
        })
        add(um(0x52, "Teor de etanol no combustível", 1, COMB, "%") { pct(it[0]) })
        add(um(0x53, "Pressão absoluta do EVAP", 2, EMISS, "kPa") { u16(it) / 200.0 })
        add(um(0x54, "Pressão do EVAP", 2, EMISS, "Pa") { s16(it).toDouble() })
        add(um(0x59, "Pressão absoluta na flauta", 2, COMB, "kPa") { u16(it) * 10.0 })
        add(um(0x5A, "Posição relativa do pedal", 1, ACEL, "%") { pct(it[0]) })
        add(um(0x5B, "Carga da bateria híbrida", 1, ELET, "%") { pct(it[0]) })
        add(um(0x5C, "Temperatura do óleo do motor", 1, TEMP, "°C") { it[0] - 40.0 })
        add(um(0x5D, "Ponto de injeção", 2, COMB, "°") { u16(it) / 128.0 - 210 })
        add(um(0x5E, "Consumo instantâneo", 2, COMB, "L/h") { u16(it) / 20.0 })
        add(um(0x61, "Torque pedido pelo motorista", 1, MOTOR, "%") { it[0] - 125.0 })
        add(um(0x62, "Torque real do motor", 1, MOTOR, "%") { it[0] - 125.0 })
        add(um(0x63, "Torque de referência", 2, MOTOR, "Nm") { u16(it).toDouble() })
        add(PidDef(0xA4, "Marcha engatada", 4, MOTOR) { b ->
            // A bit0 = dado suportado; B[7:4] = marcha; C,D = relação × 1000.
            if (b[0] and 0x01 == 0) emptyList() else listOf(
                Valor("Marcha", (b[1] shr 4).toDouble(), ""),
                Valor("Relação da marcha", u16(b, 2) / 1000.0, ""),
            )
        })
        add(um(0xA6, "Odômetro", 4, MOTOR, "km") {
            ((it[0].toLong() shl 24) or (it[1].toLong() shl 16) or (it[2].toLong() shl 8) or it[3].toLong()) / 10.0
        })
    }

    val porPid: Map<Int, PidDef> = lista.associateBy { it.pid }

    /** PIDs que são só mapas de suporte (00, 20, 40, ...). */
    fun ehMapa(pid: Int) = pid % 0x20 == 0

    /** Interpreta um mapa de suporte de 4 bytes começando em [base]. */
    fun suportados(base: Int, b: List<Int>): Set<Int> {
        val r = mutableSetOf<Int>()
        for (i in 0 until 32) {
            val byte = b[i / 8]
            if (byte and (0x80 shr (i % 8)) != 0) r += base + i + 1
        }
        return r
    }

    /** Decodifica a resposta completa "41 PID A B ..." (ou "42 PID frame A B ..." do freeze frame). */
    fun decodificar(pid: Int, dados: List<Int>): List<Valor>? {
        val def = porPid[pid] ?: return null
        if (dados.size < def.bytes) return null
        return def.decodificar(dados)
    }
}
