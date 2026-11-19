package com.soswf.obd.conexao

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.soswf.obd.core.ElmLink
import com.soswf.obd.core.StreamElmLink
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

enum class Tipo(val rotulo: String) { CLASSICO("Bluetooth"), BLE("Bluetooth LE"), WIFI("Wi-Fi") }

data class Dispositivo(val nome: String, val endereco: String, val tipo: Tipo)

/**
 * Abre o canal com o adaptador. Todas as funções bloqueiam: chamar só de Dispatchers.IO,
 * nunca da thread principal (risco de ANR).
 */
object Conexoes {

    private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    @SuppressLint("MissingPermission") // permissões pedidas na tela de conexão antes de chegar aqui
    fun classico(adaptador: BluetoothAdapter, endereco: String): ElmLink {
        val dev: BluetoothDevice = adaptador.getRemoteDevice(endereco)
        runCatching { adaptador.cancelDiscovery() }
        // 1) Forma padrão. 2) Sem criptografia (alguns clones). 3) Canal 1 direto (clones que não anunciam SPP).
        val tentativas: List<() -> BluetoothSocket> = listOf(
            { dev.createRfcommSocketToServiceRecord(SPP) },
            { dev.createInsecureRfcommSocketToServiceRecord(SPP) },
            {
                dev.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    .invoke(dev, 1) as BluetoothSocket
            },
        )
        var ultimoErro: Exception? = null
        for (criar in tentativas) {
            val socket = try { criar() } catch (e: Exception) { ultimoErro = e; continue }
            try {
                socket.connect()
                return StreamElmLink(socket.inputStream, socket.outputStream) { runCatching { socket.close() } }
            } catch (e: IOException) {
                ultimoErro = e
                runCatching { socket.close() }
            }
        }
        throw IOException("Não conectou ao adaptador Bluetooth: ${ultimoErro?.message}", ultimoErro)
    }

    fun ble(contexto: Context, adaptador: BluetoothAdapter, endereco: String): ElmLink =
        BleElmLink.abrir(contexto, adaptador.getRemoteDevice(endereco))

    /**
     * Adaptador Wi-Fi cria uma rede sem internet. O Android tende a mandar o tráfego pelos dados
     * móveis nesse caso; por isso o socket é criado explicitamente pela rede Wi-Fi.
     */
    fun wifi(contexto: Context, host: String, porta: Int): ElmLink {
        val cm = contexto.getSystemService(ConnectivityManager::class.java)
        val redeWifi = cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        val socket = redeWifi?.socketFactory?.createSocket() ?: Socket()
        socket.connect(InetSocketAddress(host, porta), 5000)
        socket.tcpNoDelay = true
        return StreamElmLink(socket.getInputStream(), socket.getOutputStream()) { runCatching { socket.close() } }
    }
}
