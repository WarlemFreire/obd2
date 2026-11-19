package com.soswf.obd.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.soswf.obd.ObdViewModel
import com.soswf.obd.UiEstado
import com.soswf.obd.core.Estado
import com.soswf.obd.core.ItemPlano
import com.soswf.obd.core.Servico
import com.soswf.obd.core.Situacao
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy")
private fun LocalDate?.br() = this?.format(DATA) ?: "data não informada"
private fun km(n: Int) = "%,d".format(n).replace(',', '.') + " km"

@Composable
fun TelaManutencao(s: UiEstado, vm: ObdViewModel, padding: PaddingValues, modifier: Modifier) {
    val ctx = LocalContext.current
    val hoje = LocalDate.now()
    val odo = s.manutencao.odometroEstimado(s.kmApp)
    val situacoes = s.manutencao.situacao(hoje, odo)
    var dialogoOdo by remember { mutableStateOf(false) }
    var servicoPara by remember { mutableStateOf<ItemPlano?>(null) }
    var servicoAvulso by remember { mutableStateOf(false) }
    var editar by remember { mutableStateOf<ItemPlano?>(null) }
    val importar = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { ctx.contentResolver.openInputStream(uri)!!.bufferedReader().readText() }
            .onSuccess { vm.importarManutencao(it) }
            .onFailure { vm.avisar("Não consegui ler o arquivo: ${it.message}") }
    }

    LazyColumn(modifier, contentPadding = padding.mais(16), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Cartao {
                Titulo("Odômetro")
                if (odo == null) Text("Informe a quilometragem do painel para o app calcular os vencimentos.")
                else {
                    Text(km(odo), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    val ult = s.manutencao.odometro.maxByOrNull { it.km }
                    Text(
                        "Última informada: ${ult?.let { km(it.km) + (it.data?.let { d -> " em " + d.br() } ?: "") }}. " +
                            "Depois disso o app soma os km que mediu enquanto estava lendo o carro.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Button(onClick = { dialogoOdo = true }) { Text("Informar odômetro") }
            }
        }
        val vencidos = situacoes.count { it.estado == Estado.VENCIDO }
        val atencao = situacoes.count { it.estado == Estado.ATENCAO }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Plano: $vencidos vencido(s) · $atencao perto de vencer", style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 6.dp))
            }
        }
        items(situacoes, key = { it.item.id }) { sit ->
            CartaoItem(sit, onServico = { servicoPara = sit.item }, onEditar = { editar = sit.item })
        }
        item {
            Cartao {
                Titulo("Histórico")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { servicoAvulso = true }) { Text("Serviço avulso") }
                    OutlinedButton(onClick = {
                        compartilharArquivo(ctx, vm.exportarManutencao(), "text/csv", "Manutenção OBD Etios")
                    }) { Text("Exportar") }
                    OutlinedButton(onClick = { importar.launch(arrayOf("text/*", "application/*")) }) { Text("Importar") }
                }
                val hist = s.manutencao.servicos.sortedByDescending { s.manutencao.posicao(it) }
                if (hist.isEmpty()) Text("Nenhum serviço registrado.")
                hist.forEach { sv ->
                    androidx.compose.material3.HorizontalDivider()
                    Text("${sv.data.br()}${sv.km?.let { " · " + km(it) } ?: ""}", style = MaterialTheme.typography.bodySmall)
                    Text(sv.descricao, fontWeight = FontWeight.Medium)
                    val det = listOfNotNull(sv.custo?.let { "R$ " + br(it, 2) }, sv.local.ifBlank { null }, sv.obs.ifBlank { null })
                    if (det.isNotEmpty()) Text(det.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { vm.apagarServico(sv) }) { Text("Apagar") }
                }
            }
        }
    }

    if (dialogoOdo) DialogoNumero("Odômetro do painel (km)", odo?.toString() ?: "", onFechar = { dialogoOdo = false }) {
        vm.informarOdometro(it); dialogoOdo = false
    }
    servicoPara?.let { item ->
        DialogoServico(item.nome, item.id, odo, onFechar = { servicoPara = null }) { vm.registrarServico(it); servicoPara = null }
    }
    if (servicoAvulso) DialogoServico(null, null, odo, onFechar = { servicoAvulso = false }) { vm.registrarServico(it); servicoAvulso = false }
    editar?.let { item ->
        DialogoIntervalo(item, onFechar = { editar = null }, onRemover = { vm.removerItem(item.id); editar = null }) {
            vm.editarItem(it); editar = null
        }
    }
}

@Composable
private fun CartaoItem(sit: Situacao, onServico: () -> Unit, onEditar: () -> Unit) {
    val (selo, cor) = when (sit.estado) {
        Estado.VENCIDO -> "🔴 VENCIDO" to MaterialTheme.colorScheme.error
        Estado.ATENCAO -> "🟠 Perto de vencer" to MaterialTheme.colorScheme.secondary
        Estado.OK -> "🟢 Em dia" to MaterialTheme.colorScheme.primary
        Estado.SEM_HISTORICO -> "⚪ Sem registro" to MaterialTheme.colorScheme.outline
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(sit.item.nome, fontWeight = FontWeight.SemiBold)
            Text(selo, color = cor)
            val iv = listOfNotNull(sit.item.intervaloKm?.let { km(it) }, sit.item.intervaloMeses?.let { "$it meses" })
            Text("A cada " + iv.joinToString(" ou "), style = MaterialTheme.typography.bodySmall)
            sit.ultimo?.let { u -> Text("Último: ${u.data.br()}${u.km?.let { " · " + km(it) } ?: ""}", style = MaterialTheme.typography.bodySmall) }
            val prox = listOfNotNull(
                sit.proximoKm?.let { p -> km(p) + (sit.faltamKm?.let { f -> if (f >= 0) " (faltam ${km(f)})" else " (passou ${km(-f)})" } ?: "") },
                sit.proximaData?.let { d -> d.br() + (sit.faltamDias?.let { f -> if (f >= 0) " (faltam $f dias)" else " (passou ${-f} dias)" } ?: "") },
            )
            if (prox.isNotEmpty()) Text("Próximo: " + prox.joinToString(" ou "), style = MaterialTheme.typography.bodySmall)
            if (sit.item.obs.isNotBlank()) Text(sit.item.obs, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onServico) { Text("Registrar serviço") }
                TextButton(onClick = onEditar) { Text("Intervalo") }
            }
        }
    }
}

@Composable
private fun DialogoNumero(titulo: String, inicial: String, onFechar: () -> Unit, onOk: (Int) -> Unit) {
    var t by remember { mutableStateOf(inicial) }
    val n = t.filter { it.isDigit() }.toIntOrNull()
    AlertDialog(
        onDismissRequest = onFechar,
        title = { Text(titulo) },
        text = { OutlinedTextField(t, { t = it }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)) },
        confirmButton = { TextButton(enabled = n != null, onClick = { n?.let(onOk) }) { Text("Salvar") } },
        dismissButton = { TextButton(onClick = onFechar) { Text("Cancelar") } },
    )
}

@Composable
private fun DialogoServico(nomeItem: String?, itemId: String?, odo: Int?, onFechar: () -> Unit, onOk: (Servico) -> Unit) {
    var data by remember { mutableStateOf(LocalDate.now().br()) }
    var kmTxt by remember { mutableStateOf(odo?.toString() ?: "") }
    var desc by remember { mutableStateOf(nomeItem ?: "") }
    var custo by remember { mutableStateOf("") }
    var local by remember { mutableStateOf("") }
    var obs by remember { mutableStateOf("") }
    val dataOk = runCatching { LocalDate.parse(data.trim(), DATA) }.getOrNull()
    AlertDialog(
        onDismissRequest = onFechar,
        title = { Text(nomeItem ?: "Serviço avulso") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(data, { data = it }, label = { Text("Data (dd/mm/aaaa)") }, singleLine = true, isError = dataOk == null)
                OutlinedTextField(kmTxt, { kmTxt = it }, label = { Text("Km no painel") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(desc, { desc = it }, label = { Text("O que foi feito (peça, marca)") })
                OutlinedTextField(custo, { custo = it }, label = { Text("Custo R$ (opcional)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(local, { local = it }, label = { Text("Onde (opcional)") }, singleLine = true)
                OutlinedTextField(obs, { obs = it }, label = { Text("Observação (opcional)") })
            }
        },
        confirmButton = {
            TextButton(enabled = dataOk != null && desc.isNotBlank(), onClick = {
                onOk(Servico(dataOk!!, kmTxt.filter { it.isDigit() }.toIntOrNull(), itemId, desc.trim(),
                    custo.replace(',', '.').toDoubleOrNull(), local.trim(), obs.trim()))
            }) { Text("Salvar") }
        },
        dismissButton = { TextButton(onClick = onFechar) { Text("Cancelar") } },
    )
}

@Composable
private fun DialogoIntervalo(item: ItemPlano, onFechar: () -> Unit, onRemover: () -> Unit, onOk: (ItemPlano) -> Unit) {
    var confirmarRemocao by remember { mutableStateOf(false) }
    var kmTxt by remember { mutableStateOf(item.intervaloKm?.toString() ?: "") }
    var mesesTxt by remember { mutableStateOf(item.intervaloMeses?.toString() ?: "") }
    AlertDialog(
        onDismissRequest = onFechar,
        title = { Text(item.nome) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Vence pelo que chegar primeiro. Deixe em branco para não usar.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(kmTxt, { kmTxt = it }, label = { Text("A cada quantos km") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(mesesTxt, { mesesTxt = it }, label = { Text("A cada quantos meses") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                if (item.obs.isNotBlank()) Text(item.obs, style = MaterialTheme.typography.bodySmall)
                if (!confirmarRemocao) TextButton(onClick = { confirmarRemocao = true }) { Text("Remover este item do plano") }
                else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Remover? O histórico continua guardado.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onRemover) { Text("Sim, remover") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onOk(item.copy(intervaloKm = kmTxt.filter { it.isDigit() }.toIntOrNull(), intervaloMeses = mesesTxt.filter { it.isDigit() }.toIntOrNull()))
            }) { Text("Salvar") }
        },
        dismissButton = { TextButton(onClick = onFechar) { Text("Cancelar") } },
    )
}
