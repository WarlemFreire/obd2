package com.soswf.obd

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Serviço em primeiro plano durante a gravação. Não lê nada: só mantém o processo vivo
 * (notificação fixa) e a CPU acordada (wake lock parcial) com a tela apagada. A leitura
 * em si roda no ObdViewModel.
 */
class GravacaoService : Service() {

    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        criarCanal(this)
        val tipo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, ID, notificacao(this, intent?.getStringExtra(EXTRA_TEXTO) ?: "Gravando…"), tipo)
        if (wake == null) {
            wake = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "obd:gravacao")
                .apply { acquire(6 * 60 * 60 * 1000L) } // teto de 6 h, por segurança
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wake?.takeIf { it.isHeld }?.release()
        wake = null
    }

    companion object {
        private const val CANAL = "gravacao"
        private const val ID = 1
        private const val EXTRA_TEXTO = "texto"

        fun iniciar(ctx: Context) =
            ContextCompat.startForegroundService(ctx, Intent(ctx, GravacaoService::class.java))

        fun parar(ctx: Context) = ctx.stopService(Intent(ctx, GravacaoService::class.java))

        /** Atualiza o texto da notificação sem reiniciar o serviço. */
        fun atualizar(ctx: Context, texto: String) {
            runCatching { ctx.getSystemService(NotificationManager::class.java).notify(ID, notificacao(ctx, texto)) }
        }

        private fun criarCanal(ctx: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
                    NotificationChannel(CANAL, "Gravação", NotificationManager.IMPORTANCE_LOW),
                )
            }
        }

        private fun notificacao(ctx: Context, texto: String): Notification {
            val abrir = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Builder(ctx, CANAL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("OBD: gravando")
                .setContentText(texto)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(abrir)
                .build()
        }
    }
}
