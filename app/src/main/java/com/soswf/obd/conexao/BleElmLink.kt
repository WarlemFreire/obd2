package com.soswf.obd.conexao

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import com.soswf.obd.core.ElmLink
import com.soswf.obd.core.ElmTimeoutException
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * ELM327 por Bluetooth LE. Não há padrão: cada fabricante usa um serviço GATT próprio.
 * Procura primeiro os serviços conhecidos e, se nenhum existir, qualquer serviço com uma
 * característica de escrita e uma de notificação.
 */
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT verificado antes de abrir
class BleElmLink private constructor(private val gatt: BluetoothGatt, private val estado: Estado) : ElmLink {

    class Estado {
        val conectado = CountDownLatch(1)
        val servicos = CountDownLatch(1)
        val descritor = CountDownLatch(1)
        @Volatile var escrita = CountDownLatch(1)
        @Volatile var erro: String? = null
        @Volatile var caiu = false
        val buffer = StringBuilder()
        val trava = Object()
    }

    private lateinit var escrever: BluetoothGattCharacteristic

    override fun send(text: String) {
        synchronized(estado.trava) { estado.buffer.setLength(0) }
        val bytes = text.toByteArray(Charsets.US_ASCII)
        // MTU padrão do BLE: 20 bytes de carga útil por escrita.
        for (pedaco in bytes.toList().chunked(20)) {
            estado.escrita = CountDownLatch(1)
            val dados = pedaco.toByteArray()
            val tipo = if (escrever.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(escrever, dados, tipo) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                escrever.writeType = tipo
                @Suppress("DEPRECATION")
                escrever.value = dados
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(escrever)
            }
            if (!ok) throw IOException("Falha ao escrever no adaptador BLE")
            if (!estado.escrita.await(2, TimeUnit.SECONDS)) throw IOException("Adaptador BLE não confirmou a escrita")
        }
    }

    override fun readUntilPrompt(timeoutMs: Long): String {
        val limite = System.currentTimeMillis() + timeoutMs
        synchronized(estado.trava) {
            while (true) {
                if (estado.caiu) throw IOException("Conexão BLE caiu")
                val i = estado.buffer.indexOf(">")
                if (i >= 0) {
                    val r = estado.buffer.substring(0, i)
                    estado.buffer.delete(0, i + 1)
                    return r
                }
                val resta = limite - System.currentTimeMillis()
                if (resta <= 0) throw ElmTimeoutException(estado.buffer.toString())
                estado.trava.wait(resta)
            }
        }
    }

    override fun close() {
        runCatching { gatt.disconnect() }
        runCatching { gatt.close() }
    }

    companion object {
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private fun curto(u: String) = UUID.fromString("0000$u-0000-1000-8000-00805f9b34fb")

        /** Serviços usados pelos adaptadores mais comuns (genéricos chineses, Vgate, OBDLink). */
        private val conhecidos = listOf(
            curto("fff0"), curto("ffe0"), curto("18f0"),
            UUID.fromString("e7810a71-73ae-499d-8c15-faa9aef0c3f2"),
        )
        private val ignorar = setOf(curto("1800"), curto("1801"), curto("180a"), curto("180f"))

        fun abrir(contexto: Context, dev: BluetoothDevice): BleElmLink {
            val estado = Estado()
            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, novo: Int) {
                    if (novo == BluetoothProfile.STATE_CONNECTED) {
                        estado.conectado.countDown()
                        g.discoverServices()
                    } else if (novo == BluetoothProfile.STATE_DISCONNECTED) {
                        estado.caiu = true
                        estado.erro = estado.erro ?: "desconectado (status $status)"
                        estado.conectado.countDown(); estado.servicos.countDown(); estado.descritor.countDown()
                        synchronized(estado.trava) { estado.trava.notifyAll() }
                    }
                }

                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    estado.servicos.countDown()
                }

                override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
                    estado.descritor.countDown()
                }

                override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                    estado.escrita.countDown()
                }

                override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
                    receber(value)
                }

                @Deprecated("Android 12 e anteriores")
                override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                    @Suppress("DEPRECATION")
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) receber(c.value ?: return)
                }

                private fun receber(v: ByteArray) {
                    synchronized(estado.trava) {
                        estado.buffer.append(String(v, Charsets.US_ASCII))
                        estado.trava.notifyAll()
                    }
                }
            }

            val gatt = dev.connectGatt(contexto, false, callback, BluetoothDevice.TRANSPORT_LE)
            fun falhar(msg: String): Nothing {
                runCatching { gatt.close() }
                throw IOException(msg)
            }
            if (!estado.conectado.await(10, TimeUnit.SECONDS) || estado.caiu) falhar("BLE não conectou: ${estado.erro ?: "tempo esgotado"}")
            if (!estado.servicos.await(10, TimeUnit.SECONDS) || estado.caiu) falhar("BLE: serviços não descobertos")

            val candidatos = gatt.services.filter { it.uuid !in ignorar }
                .sortedBy { s -> conhecidos.indexOf(s.uuid).let { if (it < 0) Int.MAX_VALUE else it } }
            var notif: BluetoothGattCharacteristic? = null
            var escr: BluetoothGattCharacteristic? = null
            for (s in candidatos) {
                val n = s.characteristics.firstOrNull {
                    it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                }
                val w = s.characteristics.firstOrNull {
                    it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                }
                if (n != null && w != null) { notif = n; escr = w; break }
            }
            if (notif == null || escr == null) falhar("BLE: nenhum serviço de adaptador OBD encontrado")

            gatt.setCharacteristicNotification(notif, true)
            val valor = if (notif.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            notif.getDescriptor(CCCD)?.let { d ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(d, valor)
                } else {
                    @Suppress("DEPRECATION")
                    d.value = valor
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(d)
                }
                estado.descritor.await(5, TimeUnit.SECONDS)
            }
            return BleElmLink(gatt, estado).also { it.escrever = escr }
        }
    }
}
