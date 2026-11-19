package com.soswf.obd.core

data class Explicacao(
    val codigo: String,
    val descricao: String,
    val causas: List<String>,
    val verificarPrimeiro: String?,
    /** false = código fora da base; descrição genérica pela categoria. */
    val conhecido: Boolean,
)

object CodigosFalha {

    private val base: Map<String, Explicacao> by lazy { carregar() }

    private fun carregar(): Map<String, Explicacao> {
        val stream = CodigosFalha::class.java.getResourceAsStream("dtc_ptbr.tsv")
            ?: error("Base de códigos ausente")
        return stream.bufferedReader(Charsets.UTF_8).useLines { linhas ->
            linhas.filter { it.isNotBlank() && !it.startsWith("#") }
                .map { it.split('\t') }
                .filter { it.size == 4 }
                .associate { (cod, desc, causas, verificar) ->
                    cod to Explicacao(cod, desc, causas.split('|').map { it.trim() }, verificar, true)
                }
        }
    }

    val total: Int get() = base.size

    fun explicar(dtc: Dtc): Explicacao = base[dtc.codigo] ?: generica(dtc.codigo)

    private fun generica(c: String): Explicacao {
        val sistema = when (c[0]) {
            'P' -> "Motor e câmbio"
            'C' -> "Chassi (freios, ABS, suspensão, direção)"
            'B' -> "Carroceria (airbag, travas, painel, climatização)"
            else -> "Rede de comunicação entre módulos"
        }
        // SAE J2012: x1xxx = fabricante; B2/C2/U2 = fabricante; P30xx–P33xx = fabricante.
        val fabricante = c[1] == '1' ||
            (c[0] != 'P' && c[1] == '2') ||
            (c[0] == 'P' && c[1] == '3' && c[2] in '0'..'3')
        val area = if (c[0] == 'P' && !fabricante) when (c[2]) {
            '0' -> "medição de ar/combustível e emissões auxiliares"
            '1' -> "medição de ar e combustível"
            '2' -> "medição de ar e combustível (circuito dos bicos)"
            '3' -> "ignição e falha de combustão"
            '4' -> "controle de emissões"
            '5' -> "velocidade, marcha lenta e entradas auxiliares"
            '6' -> "central e saídas"
            '7', '8', '9' -> "transmissão"
            'A' -> "sistema híbrido"
            else -> "motor"
        } else null
        val desc = buildString {
            append(sistema)
            if (area != null) append(" — ").append(area)
            if (fabricante) append(". Código específico do fabricante: significado exato só no manual de serviço Toyota")
        }
        return Explicacao(c, desc, emptyList(), null, false)
    }
}
