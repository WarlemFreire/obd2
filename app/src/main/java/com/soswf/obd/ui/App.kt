package com.soswf.obd.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.soswf.obd.Atualizador
import com.soswf.obd.EstadoAtualizacao
import com.soswf.obd.EstadoConexao
import com.soswf.obd.ObdViewModel
import com.soswf.obd.UiEstado
import com.soswf.obd.conexao.Dispositivo
import com.soswf.obd.conexao.Tipo
import com.soswf.obd.core.Dtc
import com.soswf.obd.core.Pids
import java.io.File

private enum class Aba(val titulo: String, val simbolo: String) {
    CONECTAR("Conectar", "🔌"), PAINEL("Painel", "📈"), GRAVAR("Gravar", "⏺"), DIAGNOSTICO("Diagnóstico", "🩺"),
    DESCOBERTA("Descoberta", "🔎")
}

@Composable
fun App(vm: ObdViewModel = viewModel()) {
    val s by vm.estado.collectAsStateWithLifecycle()
    var aba by rememberSaveable { mutableStateOf(Aba.CONECTAR) }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(s.mensagem) {
        s.mensagem?.let { snack.showSnackbar(it); vm.limparMensagem() }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            NavigationBar {
                Aba.entries.forEach { a ->
                    NavigationBarItem(
                        selected = aba == a,
                        onClick = { aba = a },
                        icon = { Text(a.simbolo, fontSize = 20.sp) },
                        label = { Text(a.titulo) },
                    )
                }
            }
        },
    ) { padding ->
        val mod = Modifier.fillMaxSize().consumeWindowInsets(padding)
        when (aba) {
            Aba.CONECTAR -> TelaConectar(s, vm, padding, mod)
            Aba.PAINEL -> TelaPainel(s, vm, padding, mod)
            Aba.GRAVAR -> TelaGravar(s, vm, padding, mod)
            Aba.DIAGNOSTICO -> TelaDiagnostico(s, vm, padding, mod)
            Aba.DESCOBERTA -> TelaDescoberta(s, vm, padding, mod)
        }
    }
}

@Composable
private fun Cartao(conteudo: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { conteudo() }
    }
}

@Composable
private fun Titulo(t: String) = Text(t, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

@Composable
private fun StatusConexao(c: EstadoConexao) {
    val (texto, cor) = when (c) {
        EstadoConexao.Desconectado -> "Desconectado" to MaterialTheme.colorScheme.outline
        is EstadoConexao.Conectando -> c.etapa to MaterialTheme.colorScheme.secondary
        is EstadoConexao.Conectado -> "Conectado: ${c.dispositivo.nome}\n${c.info.versao} · ${c.info.protocolo}" +
            (c.info.tensao?.let { " · $it" } ?: "") to MaterialTheme.colorScheme.primary
        is EstadoConexao.Falhou -> "Falhou: ${c.mensagem}" to MaterialTheme.colorScheme.error
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (c is EstadoConexao.Conectando) {
            CircularProgressIndicator(Modifier.padding(end = 10.dp).height(20.dp), strokeWidth = 2.dp)
        }
        Text(texto, color = cor)
    }
}

private fun permissoesBluetooth(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

private fun temPermissoes(ctx: Context) = permissoesBluetooth().all {
    ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
}

@Composable
private fun TelaConectar(s: UiEstado, vm: ObdViewModel, padding: PaddingValues, modifier: Modifier) {
    val ctx = LocalContext.current
    var depois by remember { mutableStateOf<(() -> Unit)?>(null) }
    val pedir = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.all { it }) depois?.invoke()
        else vm.avisar("Sem a permissão de Bluetooth o app não consegue achar o adaptador")
        depois = null
    }
    fun comPermissao(acao: () -> Unit) {
        if (temPermissoes(ctx)) acao() else { depois = acao; pedir.launch(permissoesBluetooth()) }
    }
    LaunchedEffect(Unit) { if (temPermissoes(ctx)) vm.listarPareados() }
    var wifi by rememberSaveable { mutableStateOf("192.168.0.10:35000") }

    LazyColumn(modifier, contentPadding = padding.mais(16), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Cartao {
                Titulo("Adaptador")
                StatusConexao(s.conexao)
                val versao = remember {
                    runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
                }
                Text("Versão do app: $versao", style = MaterialTheme.typography.bodySmall)
                CartaoAtualizacao(s, vm)
                if (s.conexao is EstadoConexao.Conectado) {
                    OutlinedButton(onClick = { vm.desconectar() }) { Text("Desconectar") }
                }
            }
        }
        item {
            Cartao {
                Titulo("Bluetooth")
                Text(
                    "Adaptador Bluetooth comum: pareie primeiro em Ajustes > Bluetooth do celular (PIN 1234 ou 0000). " +
                        "Adaptador BLE: não pareia no sistema, use \"Buscar BLE\".",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { comPermissao { vm.listarPareados() } }) { Text("Pareados") }
                    OutlinedButton(enabled = !s.buscando, onClick = { comPermissao { vm.buscarBle() } }) {
                        Text(if (s.buscando) "Buscando…" else "Buscar BLE")
                    }
                }
            }
        }
        items(s.dispositivos, key = { it.endereco }) { d -> LinhaDispositivo(d) { comPermissao { vm.conectar(d) } } }
        item {
            Cartao {
                Titulo("Wi-Fi")
                Text(
                    "Conecte o Wi-Fi do celular à rede do adaptador (ex.: \"WiFi_OBDII\") e toque em conectar.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(wifi, { wifi = it.trim() }, label = { Text("Endereço:porta") }, singleLine = true)
                Button(onClick = { vm.conectar(Dispositivo("Wi-Fi $wifi", wifi, Tipo.WIFI)) }) { Text("Conectar por Wi-Fi") }
            }
        }
    }
}

@Composable
private fun CartaoAtualizacao(s: UiEstado, vm: ObdViewModel) {
    val ctx = LocalContext.current
    when (val a = s.atualizacao) {
        EstadoAtualizacao.Parado, EstadoAtualizacao.EmDia, is EstadoAtualizacao.Erro -> {
            if (a == EstadoAtualizacao.EmDia) Text("Você está na versão mais nova.", style = MaterialTheme.typography.bodySmall)
            if (a is EstadoAtualizacao.Erro) Text("Atualização: ${a.msg}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { vm.verificarAtualizacao() }) { Text("Verificar atualização") }
        }
        EstadoAtualizacao.Verificando -> Text("Verificando…", style = MaterialTheme.typography.bodySmall)
        is EstadoAtualizacao.Disponivel -> {
            Text("Versão ${a.d.versao} disponível" + (a.d.notas.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""))
            Button(onClick = { vm.baixarAtualizacao(a.d) }) { Text("Baixar e instalar") }
        }
        is EstadoAtualizacao.Baixando -> Text("Baixando… ${a.pct}%")
        is EstadoAtualizacao.Pronta -> {
            Text("Download conferido. Toque para instalar:")
            Button(onClick = {
                if (Atualizador.podeInstalar(ctx)) Atualizador.instalar(ctx, a.apk)
                else {
                    vm.avisar("Permita \"instalar apps desconhecidos\" para o OBD Etios e toque em Instalar de novo")
                    Atualizador.abrirPermissaoInstalar(ctx)
                }
            }) { Text("Instalar") }
        }
    }
}

@Composable
private fun LinhaDispositivo(d: Dispositivo, aoTocar: () -> Unit) {
    Card(onClick = aoTocar, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(d.nome, fontWeight = FontWeight.Medium)
            Text("${d.tipo.rotulo} · ${d.endereco}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TelaPainel(s: UiEstado, vm: ObdViewModel, padding: PaddingValues, modifier: Modifier) {
    val conectado = s.conexao is EstadoConexao.Conectado
    val pids = if (s.soPrincipais) vm.principais.filter { it in s.pidsSuportados } else s.pidsSuportados
    LazyColumn(modifier, contentPadding = padding.mais(16), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Cartao {
                Titulo("Painel ao vivo")
                if (!conectado) Text("Conecte ao adaptador na aba Conectar.")
                else Text("${s.pidsSuportados.size} parâmetros suportados pelo carro.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(enabled = conectado, onClick = { vm.alternarAoVivo() }) {
                        Text(if (s.aoVivoAtivo) "Parar" else "Iniciar leitura")
                    }
                    FilterChip(selected = s.soPrincipais, onClick = { vm.alternarSoPrincipais() }, label = { Text("Só principais") })
                }
            }
        }
        item { CartaoConsumo(s, vm) }
        val grupos = pids.groupBy { Pids.porPid[it]?.grupo ?: "Outros" }
        grupos.forEach { (grupo, lista) ->
            item(key = "g$grupo") { Text(grupo, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp)) }
            items(lista, key = { it }) { pid ->
                val valores = s.aoVivo[pid]
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        if (valores == null) {
                            Text(Pids.porPid[pid]?.nome ?: "PID %02X".format(pid), style = MaterialTheme.typography.bodyMedium)
                            Text("—", style = MaterialTheme.typography.titleLarge)
                        } else valores.forEach { v ->
                            Text(v.nome, style = MaterialTheme.typography.bodyMedium)
                            Text(v.formatado(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

private fun br(x: Double, casas: Int = 1) = "%.${casas}f".format(x).replace('.', ',')

@Composable
private fun CartaoConsumo(s: UiEstado, vm: ObdViewModel) {
    val c = s.consumo
    Cartao {
        Titulo("Consumo (estimado)")
        when {
            c == null || c.litrosHora == null -> Text(
                if (c?.etanolPct == null && c != null) "Aguardando o teor de etanol…"
                else "Inicie a leitura (ou a gravação) para calcular.",
                style = MaterialTheme.typography.bodySmall,
            )
            c.kmPorLitroAgora != null -> {
                // Pé fora do acelerador: a injeção quase corta e o instantâneo dispara.
                Text(if (c.kmPorLitroAgora > 99) "> 99 km/l (desacelerando)" else "${br(c.kmPorLitroAgora)} km/l",
                    style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text("${br(c.litrosHora)} L/h agora", style = MaterialTheme.typography.bodySmall)
            }
            else -> {
                Text("${br(c.litrosHora)} L/h", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text("parado: gastando sem andar", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (c != null && c.minutos > 0) {
            HorizontalDivider()
            Text("Desde que começou a ler:", style = MaterialTheme.typography.labelLarge)
            Text("${br(c.km)} km · ${br(c.litros, 2)} L" + (c.kmPorLitroMedio?.let { " · média ${br(it)} km/l" } ?: ""))
            val pct = if (c.litros > 0) c.litrosParado / c.litros * 100 else 0.0
            Text("Parado: ${br(c.minutosParado, 0)} de ${br(c.minutos, 0)} min · ${br(c.litrosParado, 2)} L (${br(pct, 0)}% do combustível)")
            OutlinedButton(onClick = { vm.zerarConsumo() }) { Text("Zerar") }
        }
        Text(
            "Calculado pelo ar que o motor puxa e pelo teor de etanol. Erro típico ±10%. " +
                "A média só conta enquanto o app está lendo.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun TelaDiagnostico(s: UiEstado, vm: ObdViewModel, padding: PaddingValues, modifier: Modifier) {
    val d = s.diagnostico
    LazyColumn(modifier, contentPadding = padding.mais(16), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Cartao {
                Titulo("Diagnóstico")
                Text("Só leitura: o app não apaga códigos.", style = MaterialTheme.typography.bodySmall)
                Button(enabled = s.conexao is EstadoConexao.Conectado && !s.lendoDiagnostico, onClick = { vm.lerDiagnostico() }) {
                    Text(if (s.lendoDiagnostico) "Lendo…" else "Ler diagnóstico")
                }
                d?.let { Text("Lido em ${it.lidoEm}", style = MaterialTheme.typography.bodySmall) }
            }
        }
        if (d == null) return@LazyColumn
        d.prontidao?.let { p ->
            item {
                Cartao {
                    Titulo("Luz de injeção: ${if (p.luzAcesa) "ACESA" else "apagada"}")
                    Text("Monitores de prontidão (autotestes da central):")
                    p.monitores.filter { it.suportado }.forEach {
                        Text("${if (it.completo) "✅" else "⏳"} ${it.nome}${if (it.completo) "" else " — incompleto"}")
                    }
                    if (p.pendentes.isNotEmpty()) {
                        Text(
                            "Incompleto não é defeito: o teste ainda não rodou desde que a bateria foi desligada ou os códigos foram apagados.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        listOf(
            "Códigos confirmados (acendem a luz)" to d.confirmados,
            "Códigos pendentes (detectados uma vez)" to d.pendentes,
            "Códigos permanentes" to d.permanentes,
        ).forEach { (titulo, lista) ->
            item(key = titulo) {
                Cartao {
                    Titulo(if (lista == null) titulo else "$titulo: ${lista.size}")
                    when {
                        lista == null -> Text("O carro não suporta esta leitura.")
                        lista.isEmpty() -> Text("Nenhum.")
                        else -> lista.forEach { CartaoCodigo(it, vm) }
                    }
                }
            }
        }
        if (d.freezeDtc != null) {
            item {
                Cartao {
                    Titulo("Retrato do momento da falha (${d.freezeDtc})")
                    d.freezeFrame.forEach { Text("${it.nome}: ${it.formatado()}") }
                }
            }
        }
        item {
            Cartao {
                Titulo("Falhas de ignição por cilindro")
                if (d.falhasIgnicao.isEmpty()) Text("O carro não informou (ou não suporta o Modo 06).")
                d.falhasIgnicao.forEach {
                    Text("Cilindro ${it.cilindro}: ${it.cicloAtual ?: "—"} neste ciclo · média ${it.media10Ciclos ?: "—"} (10 ciclos)")
                }
            }
        }
    }
}

@Composable
private fun CartaoCodigo(d: Dtc, vm: ObdViewModel) {
    val e = vm.explicar(d)
    HorizontalDivider()
    Text("${e.codigo} — ${e.descricao}", fontWeight = FontWeight.SemiBold)
    if (e.causas.isNotEmpty()) {
        Text("Causas prováveis, da mais comum para a menos:", style = MaterialTheme.typography.bodySmall)
        e.causas.forEachIndexed { i, c -> Text("${i + 1}. $c") }
    }
    e.verificarPrimeiro?.let { Text("Verificar primeiro: $it", fontWeight = FontWeight.Medium) }
}

@Composable
private fun TelaDescoberta(s: UiEstado, vm: ObdViewModel, padding: PaddingValues, modifier: Modifier) {
    val ctx = LocalContext.current
    val versao = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    Column(modifier.verticalScroll(rememberScrollState()).padding(padding.mais(16)), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Cartao {
            Titulo("Relatório de descoberta")
            Text(
                "Faz a varredura completa do que o seu carro informa. Rode com o motor ligado, parado, " +
                    "depois de aquecido. Leva cerca de 1 minuto. Depois compartilhe o relatório.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                enabled = s.conexao is EstadoConexao.Conectado && s.descobertaEtapa == null,
                onClick = { vm.executarDescoberta(ObdViewModel.infoCelular(versao)) },
            ) { Text("Executar descoberta") }
            s.descobertaEtapa?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.padding(end = 10.dp).height(20.dp), strokeWidth = 2.dp)
                    Text(it)
                }
            }
        }
        s.relatorioTexto?.let { texto ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { compartilhar(ctx, texto) }) { Text("Compartilhar") }
                OutlinedButton(onClick = {
                    ctx.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("Relatório OBD", texto))
                }) { Text("Copiar") }
            }
            SelectionContainer {
                Text(texto, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

private fun compartilhar(ctx: Context, texto: String) {
    val pasta = File(ctx.cacheDir, "relatorios").apply { mkdirs() }
    val arquivo = File(pasta, "relatorio-obd-${System.currentTimeMillis() / 1000}.txt").apply { writeText(texto) }
    compartilharArquivo(ctx, arquivo, "text/plain", "Relatório de descoberta OBD")
}

private fun compartilharArquivo(ctx: Context, arquivo: File, tipo: String, assunto: String) {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.arquivos", arquivo)
    val envio = Intent(Intent.ACTION_SEND).apply {
        type = tipo
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, assunto)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(envio, "Compartilhar"))
}

@Composable
private fun TelaGravar(s: UiEstado, vm: ObdViewModel, padding: PaddingValues, modifier: Modifier) {
    val ctx = LocalContext.current
    val versao = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    // Android 13+: sem esta permissão a notificação "Gravando" não aparece (a gravação funciona igual).
    val pedirNotificacao = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.iniciarGravacao(ObdViewModel.infoCelular(versao))
    }
    LaunchedEffect(Unit) { vm.listarGravacoes() }
    var agora by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(s.gravacao != null) {
        while (s.gravacao != null) { agora = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) }
    }
    val conectado = s.conexao is EstadoConexao.Conectado
    LazyColumn(modifier, contentPadding = padding.mais(16), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Cartao {
                Titulo("Gravar viagem")
                Text(
                    "Grava todos os parâmetros, linha por linha, até você parar. Pode apagar a tela e dirigir. " +
                        "Não feche o app pela tela de apps recentes: isso interrompe a gravação.",
                    style = MaterialTheme.typography.bodySmall,
                )
                val g = s.gravacao
                if (g == null) {
                    Button(enabled = conectado, onClick = {
                        val precisa = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        if (precisa) pedirNotificacao.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else vm.iniciarGravacao(ObdViewModel.infoCelular(versao))
                    }) { Text("Iniciar gravação") }
                    if (!conectado) Text("Conecte ao adaptador primeiro.", style = MaterialTheme.typography.bodySmall)
                } else {
                    val seg = ((agora - g.inicioMs) / 1000).coerceAtLeast(0)
                    Text("⏺ Gravando: %d:%02d:%02d".format(seg / 3600, seg / 60 % 60, seg % 60), fontWeight = FontWeight.SemiBold)
                    Text("${g.linhas} linhas" + if (g.linhas > 0) " · %.1f s por linha".format(seg.toDouble() / g.linhas) else "")
                    Button(onClick = { vm.pararGravacao() }) { Text("Parar e salvar") }
                }
            }
        }
        item { Text("Gravações salvas", style = MaterialTheme.typography.labelLarge) }
        if (s.gravacoes.isEmpty()) item { Text("Nenhuma ainda.") }
        items(s.gravacoes, key = { it.name }) { f ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(f.name, fontWeight = FontWeight.Medium)
                    Text("%.1f KB".format(f.length() / 1024.0), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { compartilharArquivo(ctx, f, "text/csv", "Gravação OBD ${f.name}") }) { Text("Compartilhar") }
                        OutlinedButton(onClick = { vm.apagarGravacao(f) }) { Text("Apagar") }
                    }
                }
            }
        }
    }
}

/** Soma uma margem fixa ao padding do Scaffold (barras do sistema + barra de navegação). */
@Composable
private fun PaddingValues.mais(dp: Int): PaddingValues {
    val dir = androidx.compose.ui.platform.LocalLayoutDirection.current
    return PaddingValues(
        start = calculateStartPadding(dir) + dp.dp,
        end = calculateEndPadding(dir) + dp.dp,
        top = calculateTopPadding() + dp.dp,
        bottom = calculateBottomPadding() + dp.dp,
    )
}
