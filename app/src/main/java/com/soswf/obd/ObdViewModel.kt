package com.soswf.obd

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.os.BatteryManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.soswf.obd.conexao.Conexoes
import com.soswf.obd.conexao.Dispositivo
import com.soswf.obd.conexao.Tipo
import com.soswf.obd.core.CodigosFalha
import com.soswf.obd.core.Descoberta
import com.soswf.obd.core.Dtc
import com.soswf.obd.core.Elm327
import com.soswf.obd.core.Gravador
import com.soswf.obd.core.InfoAdaptador
import com.soswf.obd.core.Leitor
import com.soswf.obd.core.Pids
import com.soswf.obd.core.Relatorio
import com.soswf.obd.core.RelatorioDescoberta
import com.soswf.obd.core.Valor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface EstadoConexao {
    data object Desconectado : EstadoConexao
    data class Conectando(val etapa: String) : EstadoConexao
    data class Conectado(val dispositivo: Dispositivo, val info: InfoAdaptador) : EstadoConexao
    data class Falhou(val mensagem: String) : EstadoConexao
}

data class DiagnosticoLido(
    /** null = o carro não suporta o serviço. */
    val confirmados: List<Dtc>?,
    val pendentes: List<Dtc>?,
    val permanentes: List<Dtc>?,
    val prontidao: com.soswf.obd.core.Diagnostico.Prontidao?,
    val freezeDtc: Dtc?,
    val freezeFrame: List<Valor>,
    val falhasIgnicao: List<com.soswf.obd.core.Diagnostico.FalhasCilindro>,
    val lidoEm: String,
)

data class GravacaoAtiva(val arquivo: String, val inicioMs: Long, val linhas: Int = 0)

data class UiEstado(
    val conexao: EstadoConexao = EstadoConexao.Desconectado,
    val dispositivos: List<Dispositivo> = emptyList(),
    val buscando: Boolean = false,
    val pidsSuportados: List<Int> = emptyList(),
    val aoVivo: Map<Int, List<Valor>> = emptyMap(),
    val aoVivoAtivo: Boolean = false,
    val soPrincipais: Boolean = true,
    val diagnostico: DiagnosticoLido? = null,
    val lendoDiagnostico: Boolean = false,
    val descobertaEtapa: String? = null,
    val relatorio: RelatorioDescoberta? = null,
    val relatorioTexto: String? = null,
    val mensagem: String? = null,
    val gravacao: GravacaoAtiva? = null,
    val gravacoes: List<File> = emptyList(),
)

class ObdViewModel(app: Application) : AndroidViewModel(app) {

    private val _estado = MutableStateFlow(UiEstado())
    val estado: StateFlow<UiEstado> = _estado

    /** O ELM327 só atende um pedido por vez. */
    private val trava = Mutex()
    private var elm: Elm327? = null
    private var leitor: Leitor? = null
    private var jobAoVivo: Job? = null
    private var jobGravacao: Job? = null

    private val bt get() = getApplication<Application>().getSystemService(BluetoothManager::class.java)?.adapter

    /** Parâmetros mais úteis para olhar dirigindo; o resto entra no modo "todos". */
    val principais = listOf(0x0C, 0x0D, 0x05, 0x04, 0x42, 0x06, 0x07, 0x0B, 0x10, 0x0E, 0x11, 0x0F, 0x2F, 0x52, 0x5C, 0x65, 0xA4)

    fun limparMensagem() = _estado.update { it.copy(mensagem = null) }

    fun avisar(m: String) = _estado.update { it.copy(mensagem = m) }

    @SuppressLint("MissingPermission")
    fun listarPareados() {
        val a = bt ?: return _estado.update { it.copy(mensagem = "Este celular não tem Bluetooth") }
        if (!a.isEnabled) return _estado.update { it.copy(mensagem = "Ligue o Bluetooth do celular") }
        val pareados = a.bondedDevices.orEmpty().map {
            Dispositivo(it.name ?: it.address, it.address, if (it.type == BluetoothDevice.DEVICE_TYPE_LE) Tipo.BLE else Tipo.CLASSICO)
        }
        _estado.update { s -> s.copy(dispositivos = (pareados + s.dispositivos.filter { it.tipo == Tipo.BLE }).distinctBy { it.endereco }) }
    }

    @SuppressLint("MissingPermission")
    fun buscarBle() {
        val a = bt ?: return
        val scanner = a.bluetoothLeScanner ?: return _estado.update { it.copy(mensagem = "Ligue o Bluetooth do celular") }
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, r: ScanResult) {
                val nome = r.scanRecord?.deviceName ?: r.device.name ?: return
                val d = Dispositivo(nome, r.device.address, Tipo.BLE)
                _estado.update { s -> if (s.dispositivos.any { it.endereco == d.endereco }) s else s.copy(dispositivos = s.dispositivos + d) }
            }
        }
        _estado.update { it.copy(buscando = true) }
        scanner.startScan(cb)
        viewModelScope.launch {
            delay(10_000)
            runCatching { scanner.stopScan(cb) }
            _estado.update { it.copy(buscando = false) }
        }
    }

    fun conectar(d: Dispositivo) {
        if (_estado.value.conexao is EstadoConexao.Conectando) return
        viewModelScope.launch {
            desconectarAgora()
            _estado.update { it.copy(conexao = EstadoConexao.Conectando("Abrindo ${d.tipo.rotulo}…")) }
            try {
                val e = withContext(Dispatchers.IO) {
                    val ctx = getApplication<Application>()
                    val link = when (d.tipo) {
                        Tipo.CLASSICO -> Conexoes.classico(bt!!, d.endereco)
                        Tipo.BLE -> Conexoes.ble(ctx, bt!!, d.endereco)
                        Tipo.WIFI -> d.endereco.split(":").let { (h, p) -> Conexoes.wifi(ctx, h, p.toInt()) }
                    }
                    _estado.update { it.copy(conexao = EstadoConexao.Conectando("Falando com o carro (pode levar 10 s)…")) }
                    val elm = Elm327(link)
                    val info = try { elm.inicializar() } catch (ex: Exception) { elm.fechar(); throw ex }
                    val l = Leitor(elm)
                    val pids = l.suporte("01").values.flatten().filterNot { Pids.ehMapa(it) }.distinct().sorted()
                    Triple(elm, info, pids)
                }
                elm = e.first
                leitor = Leitor(e.first)
                _estado.update {
                    it.copy(conexao = EstadoConexao.Conectado(d, e.second), pidsSuportados = e.third, aoVivo = emptyMap())
                }
                if (e.third.isEmpty()) {
                    _estado.update { it.copy(mensagem = "Adaptador conectado, mas o carro não respondeu. A ignição está ligada?") }
                }
            } catch (ex: Exception) {
                _estado.update { it.copy(conexao = EstadoConexao.Falhou(ex.message ?: ex.javaClass.simpleName)) }
            }
        }
    }

    fun desconectar() = viewModelScope.launch { desconectarAgora() }

    private suspend fun desconectarAgora() {
        jobAoVivo?.cancel()
        jobAoVivo = null
        trava.withLock {
            withContext(Dispatchers.IO) { runCatching { elm?.fechar() } }
            elm = null
            leitor = null
        }
        _estado.update { it.copy(conexao = EstadoConexao.Desconectado, aoVivoAtivo = false) }
    }

    /** Executa um bloco com o adaptador, garantindo exclusividade e thread de IO. */
    private suspend fun <T> comAdaptador(bloco: (Leitor) -> T): T? {
        val l = leitor ?: run { _estado.update { it.copy(mensagem = "Conecte ao adaptador primeiro") }; return null }
        return try {
            trava.withLock { withContext(Dispatchers.IO) { bloco(l) } }
        } catch (e: java.io.IOException) {
            _estado.update { it.copy(conexao = EstadoConexao.Falhou("Conexão perdida: ${e.message}"), aoVivoAtivo = false) }
            elm = null; leitor = null
            null
        }
    }

    fun alternarSoPrincipais() = _estado.update { it.copy(soPrincipais = !it.soPrincipais) }

    fun alternarAoVivo() {
        if (jobGravacao != null) return avisar("Gravando: o painel já mostra os valores da gravação")
        if (jobAoVivo != null) {
            jobAoVivo?.cancel(); jobAoVivo = null
            _estado.update { it.copy(aoVivoAtivo = false) }
            return
        }
        _estado.update { it.copy(aoVivoAtivo = true) }
        jobAoVivo = viewModelScope.launch {
            while (isActive) {
                val s = _estado.value
                val alvo = if (s.soPrincipais) principais.filter { it in s.pidsSuportados } else s.pidsSuportados
                if (alvo.isEmpty()) { delay(1000); continue }
                for (pid in alvo) {
                    if (!isActive) break
                    val r = comAdaptador { it.ler(pid) } ?: run { jobAoVivo = null; return@launch }
                    val valores = r.firstOrNull { it.ecu == "7E8" }?.valores ?: r.firstOrNull()?.valores ?: continue
                    _estado.update { it.copy(aoVivo = it.aoVivo + (pid to valores)) }
                }
            }
        }
    }

    fun lerDiagnostico() {
        if (_estado.value.lendoDiagnostico) return
        val estavaAoVivo = jobAoVivo != null
        if (estavaAoVivo) alternarAoVivo()
        _estado.update { it.copy(lendoDiagnostico = true) }
        viewModelScope.launch {
            val d = comAdaptador { l ->
                val conf = l.codigos("03")
                val pend = l.codigos("07")
                val perm = l.codigos("0A")
                val pront = l.prontidao()
                val (ffDtc, ff) = l.freezeFrame()
                val mids = l.suporte("06").values.flatten().filter { it in 0xA2..0xAD }
                val falhas = com.soswf.obd.core.Diagnostico.falhasIgnicao(l.modo06(mids))
                DiagnosticoLido(conf, pend, perm, pront, ffDtc, ff, falhas, agora())
            }
            _estado.update { it.copy(diagnostico = d ?: it.diagnostico, lendoDiagnostico = false) }
            if (estavaAoVivo) alternarAoVivo()
        }
    }

    fun executarDescoberta(dispositivoInfo: Map<String, String>) {
        if (jobGravacao != null) return avisar("Pare a gravação antes da descoberta")
        val e = elm ?: return _estado.update { it.copy(mensagem = "Conecte ao adaptador primeiro") }
        if (_estado.value.descobertaEtapa != null) return
        jobAoVivo?.cancel(); jobAoVivo = null
        _estado.update { it.copy(aoVivoAtivo = false, descobertaEtapa = "Começando…") }
        viewModelScope.launch {
            try {
                val r = trava.withLock {
                    withContext(Dispatchers.IO) {
                        Descoberta.executar(e) { etapa -> _estado.update { it.copy(descobertaEtapa = etapa) } }
                    }
                }
                val con = _estado.value.conexao as? EstadoConexao.Conectado
                val cab = linkedMapOf("Data" to agora()) + dispositivoInfo +
                    (con?.let { mapOf("Adaptador" to "${it.dispositivo.nome} (${it.dispositivo.tipo.rotulo})") } ?: emptyMap())
                _estado.update { it.copy(relatorio = r, relatorioTexto = Relatorio.texto(r, cab), descobertaEtapa = null) }
            } catch (ex: Exception) {
                _estado.update {
                    it.copy(descobertaEtapa = null, mensagem = "Descoberta interrompida: ${ex.message}",
                        conexao = if (ex is java.io.IOException) EstadoConexao.Falhou("Conexão perdida") else it.conexao)
                }
            }
        }
    }

    private fun pastaGravacoes(): File =
        File(getApplication<Application>().getExternalFilesDir(null), "gravacoes").apply { mkdirs() }

    fun listarGravacoes() {
        val lista = pastaGravacoes().listFiles { f -> f.extension == "csv" }.orEmpty().sortedByDescending { it.name }
        _estado.update { it.copy(gravacoes = lista) }
    }

    fun apagarGravacao(f: File) {
        if (_estado.value.gravacao?.arquivo == f.name) return avisar("Essa gravação ainda está em andamento")
        f.delete()
        listarGravacoes()
    }

    private fun bateriaCelular(): String = runCatching {
        getApplication<Application>().getSystemService(BatteryManager::class.java)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).toString()
    }.getOrDefault("")

    /**
     * Grava até [pararGravacao] ou a conexão cair. Cada linha vai para o disco na hora:
     * se o app morrer no meio, o arquivo fica com tudo o que foi lido até ali.
     */
    fun iniciarGravacao(infoCelular: Map<String, String>) {
        val l = leitor ?: return avisar("Conecte ao adaptador primeiro")
        if (jobGravacao != null) return
        jobAoVivo?.cancel(); jobAoVivo = null
        val ctx = getApplication<Application>()
        val agoraMs = System.currentTimeMillis()
        val arquivo = File(pastaGravacoes(), "gravacao-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(agoraMs)) + ".csv")
        val con = _estado.value.conexao as? EstadoConexao.Conectado
        val cabecalho = linkedMapOf("Início" to agora()) + infoCelular +
            (con?.let { mapOf("Adaptador" to "${it.dispositivo.nome} (${it.dispositivo.tipo.rotulo}) ${it.info.versao}", "Protocolo" to it.info.protocolo) } ?: emptyMap()) +
            (_estado.value.relatorio?.vin?.let { mapOf("VIN" to it) } ?: emptyMap())
        GravacaoService.iniciar(ctx)
        _estado.update { it.copy(gravacao = GravacaoAtiva(arquivo.name, agoraMs), aoVivoAtivo = false) }
        listarGravacoes()

        jobGravacao = viewModelScope.launch(Dispatchers.IO) {
            var motivo = "Gravação encerrada"
            try {
                arquivo.bufferedWriter().use { w ->
                    val g = trava.withLock {
                        val mids = l.suporte("06").values.flatten()
                        Gravador(
                            l, _estado.value.pidsSuportados, w, cabecalho,
                            extras = listOf("bateria_celular_pct"),
                            lerExtras = { mapOf("bateria_celular_pct" to bateriaCelular()) },
                            midsFalhas = mids,
                        )
                    }
                    while (isActive) {
                        val lidos = trava.withLock { g.ciclo() }
                        _estado.update { s ->
                            s.copy(aoVivo = s.aoVivo + lidos, gravacao = s.gravacao?.copy(linhas = g.linhas))
                        }
                        if (g.linhas % 20 == 0) {
                            val min = (System.currentTimeMillis() - agoraMs) / 60000
                            GravacaoService.atualizar(ctx, "$min min · ${g.linhas} linhas")
                        }
                    }
                }
            } catch (e: java.io.IOException) {
                motivo = "Gravação parou: conexão perdida (${e.message}). O arquivo foi salvo até ali."
                elm = null; leitor = null
                _estado.update { it.copy(conexao = EstadoConexao.Falhou("Conexão perdida durante a gravação")) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                motivo = "Gravação salva"
                throw e
            } catch (e: Exception) {
                motivo = "Gravação parou: ${e.message}. O arquivo foi salvo até ali."
            } finally {
                withContext(NonCancellable) {
                    GravacaoService.parar(ctx)
                    jobGravacao = null
                    _estado.update { it.copy(gravacao = null, mensagem = motivo) }
                    listarGravacoes()
                }
            }
        }
    }

    fun pararGravacao() {
        jobGravacao?.cancel()
    }

    fun explicar(d: Dtc) = CodigosFalha.explicar(d)

    private fun agora() = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.forLanguageTag("pt-BR")).format(Date())

    override fun onCleared() {
        jobAoVivo?.cancel()
        jobGravacao?.cancel()
        GravacaoService.parar(getApplication())
        runCatching { elm?.fechar() }
    }

    companion object {
        fun infoCelular(versaoApp: String) = linkedMapOf(
            "App" to versaoApp,
            "Celular" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        )
    }
}
