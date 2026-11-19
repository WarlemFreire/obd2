package com.soswf.obd.core

/**
 * Consumo estimado pelo ar admitido. O Etios não informa consumo (PID 5E não suportado), então:
 *
 *   combustível [g/s] = MAF [g/s] / (AFR estequiométrico × lambda comandado)
 *
 * Em malha fechada a central mantém a mistura no lambda pedido; as correções de mistura só
 * compensam erro dos bicos, por isso não entram na conta.
 *
 * AFR e densidade interpolados entre gasolina pura e etanol hidratado brasileiro pelo teor
 * informado pela central (PID 52):
 *  - gasolina: AFR 14,7; 0,745 kg/L
 *  - etanol hidratado (~93% etanol, ~7% água em massa): AFR 9,0 × 0,93 ≈ 8,4; 0,809 kg/L
 * Erro esperado ~±10% (água real do etanol, densidade, precisão do MAF). Calibrar com a bomba.
 */
object Consumo {

    const val AFR_GASOLINA = 14.7
    const val AFR_ETANOL_HIDRATADO = 8.4
    const val DENS_GASOLINA = 0.745
    const val DENS_ETANOL_HIDRATADO = 0.809

    /**
     * GNV: majoritariamente metano. AFR estequiométrico ~17 (metano puro 17,2; o gás brasileiro
     * tem um pouco de etano e inertes). Densidade ~0,74 kg/m³ a ~20 °C e 1 atm (densidade relativa
     * típica ~0,6). Os dois variam com a composição do gás: erro esperado maior que no líquido (~±15%).
     */
    const val AFR_GNV = 17.0
    const val DENS_GNV_KG_M3 = 0.74

    /** m³ de GNV por hora pelo ar admitido. No GNV a central do carro continua comandando lambda ~1. */
    fun m3PorHora(mafGs: Double, lambda: Double = 1.0): Double {
        val lam = if (lambda in 0.5..1.5) lambda else 1.0
        return mafGs / (AFR_GNV * lam) * 3600 / (DENS_GNV_KG_M3 * 1000)
    }

    /** Litros por hora. [etanolPct] 0..100 (PID 52); [lambda] comandado (PID 44), 1.0 se ausente. */
    fun litrosPorHora(mafGs: Double, etanolPct: Double, lambda: Double = 1.0): Double {
        val x = (etanolPct / 100).coerceIn(0.0, 1.0)
        val afr = AFR_GASOLINA * (1 - x) + AFR_ETANOL_HIDRATADO * x
        val dens = DENS_GASOLINA * (1 - x) + DENS_ETANOL_HIDRATADO * x
        val lam = if (lambda in 0.5..1.5) lambda else 1.0 // fora disso é corte de injeção ou dado ruim
        val gramasPorSegundo = mafGs / (afr * lam)
        return gramasPorSegundo * 3600 / (dens * 1000)
    }

    /**
     * Plano B quando o carro não informa o teor (PID 52): o tipo de combustível (PID 51).
     * Gasolina -> 0%, etanol -> 100%. Menos preciso: ignora o etanol da gasolina brasileira.
     */
    fun etanolPeloTipo(texto: String?): Double? = when (texto) {
        "Gasolina", "Bicombustível gasolina", "Híbrido gasolina" -> 0.0
        "Etanol", "Bicombustível etanol", "Híbrido etanol" -> 100.0
        else -> null
    }
}

/**
 * Acumula consumo e distância com o tempo, a partir das leituras que chegam (painel ou gravação).
 * Cada leitura de MAF soma combustível pelo intervalo desde a anterior; cada leitura de velocidade
 * soma distância. Intervalos maiores que [maxIntervaloMs] (conexão travou) não contam.
 */
class AcumuladorConsumo(private val maxIntervaloMs: Long = 5000) {

    /** true = rodando no GNV: consumo em m³, separado do líquido. */
    var gnv = false

    var litros = 0.0; private set
    var km = 0.0; private set
    var litrosParado = 0.0; private set
    var segundos = 0.0; private set
    var segundosParado = 0.0; private set

    var m3 = 0.0; private set
    var kmGnv = 0.0; private set
    var m3Parado = 0.0; private set
    var segundosGnv = 0.0; private set
    var segundosParadoGnv = 0.0; private set

    /** km rodados no líquido (km total menos o rodado no GNV). */
    val kmLiquido get() = km - kmGnv

    /** Últimos valores conhecidos. */
    var litrosHora: Double? = null; private set
    var velocidade: Double? = null; private set
    var etanolPct: Double? = null; private set
    private var lambda = 1.0
    private var temTeorReal = false
    private var tMaf: Long? = null
    private var tVel: Long? = null

    val parado get() = (velocidade ?: 0.0) < 3.0

    /** km/l instantâneo; null parado (aí vale L/h). */
    val kmPorLitroAgora: Double?
        get() {
            val v = velocidade ?: return null
            val lh = litrosHora ?: return null
            return if (v >= 3.0 && lh > 0.05) v / lh else null
        }

    val kmPorLitroMedio: Double? get() = if (litros > 0.05) kmLiquido / litros else null
    val kmPorM3Medio: Double? get() = if (m3 > 0.05) kmGnv / m3 else null

    /** m³/h instantâneo no GNV (null fora dele). */
    var m3Hora: Double? = null; private set

    /** km/m³ instantâneo; null parado ou fora do GNV. */
    val kmPorM3Agora: Double?
        get() {
            val v = velocidade ?: return null
            val q = m3Hora ?: return null
            return if (gnv && v >= 3.0 && q > 0.05) v / q else null
        }

    fun zerar() {
        litros = 0.0; km = 0.0; litrosParado = 0.0; segundos = 0.0; segundosParado = 0.0
        m3 = 0.0; kmGnv = 0.0; m3Parado = 0.0; segundosGnv = 0.0; segundosParadoGnv = 0.0
        tMaf = null; tVel = null
    }

    fun registrar(pid: Int, valores: List<Valor>, tMs: Long) {
        if (pid == 0x51 && !temTeorReal) {
            Consumo.etanolPeloTipo(valores.firstOrNull()?.texto)?.let { etanolPct = it }
            return
        }
        val n = valores.firstOrNull()?.numero ?: return
        when (pid) {
            0x52 -> { etanolPct = n; temTeorReal = true }
            0x44 -> lambda = n
            0x0D -> {
                tVel?.let { t0 ->
                    intervalo(t0, tMs)?.let { dt ->
                        val d = (velocidade ?: n) * dt / 3600
                        km += d
                        if (gnv) kmGnv += d
                    }
                }
                velocidade = n; tVel = tMs
            }
            0x10 -> if (gnv) {
                val q = Consumo.m3PorHora(n, lambda)
                tMaf?.let { t0 ->
                    intervalo(t0, tMs)?.let { dt ->
                        val v = (m3Hora ?: q) * dt / 3600
                        m3 += v; segundosGnv += dt
                        if (parado) { m3Parado += v; segundosParadoGnv += dt }
                    }
                }
                m3Hora = q; litrosHora = null; tMaf = tMs
            } else {
                m3Hora = null
                // Sem teor de etanol ainda: não chuta o combustível.
                val e = etanolPct ?: return
                val lh = Consumo.litrosPorHora(n, e, lambda)
                tMaf?.let { t0 ->
                    intervalo(t0, tMs)?.let { dt ->
                        val l = (litrosHora ?: lh) * dt / 3600
                        litros += l; segundos += dt
                        if (parado) { litrosParado += l; segundosParado += dt }
                    }
                }
                litrosHora = lh; tMaf = tMs
            }
        }
    }

    private fun intervalo(t0: Long, t: Long): Double? =
        (t - t0).takeIf { it in 1..maxIntervaloMs }?.let { it / 1000.0 }
}
