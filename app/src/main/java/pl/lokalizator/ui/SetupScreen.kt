@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package pl.lokalizator.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.lokalizator.Crypto
import pl.lokalizator.Prefs
import pl.lokalizator.TrackerService

@Composable
fun SetupScreen(prefs: Prefs, canCancel: Boolean, onDone: () -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(prefs.name) }
    var code by remember { mutableStateOf(prefs.code.ifEmpty { Prefs.newCode() }) }
    var interval by remember { mutableIntStateOf(prefs.intervalSec) }
    var findMin by remember { mutableIntStateOf(prefs.findMinutes) }
    var precise by remember { mutableStateOf(prefs.preciseNormal) }
    var broker by remember { mutableStateOf(prefs.broker) }
    var user by remember { mutableStateOf(prefs.user) }
    var pass by remember { mutableStateOf(prefs.pass) }
    var advanced by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (MainActivity.hasLocation(ctx)) {
            prefs.sharing = true
            TrackerService.send(ctx, TrackerService.ACTION_RESTART)
            onDone()
        } else {
            toast(ctx, "Bez dostępu do lokalizacji aplikacja nie zadziała.")
        }
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Lokalizator – ustawienia", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Dwa (lub więcej) telefony z tym samym kodem widzą nawzajem swoją lokalizację. " +
                "Wszystko jest szyfrowane tym kodem – nikt bez niego nic nie odczyta.",
            style = MaterialTheme.typography.bodyMedium
        )

        OutlinedTextField(name, { name = it }, label = { Text("Twoja nazwa (np. Robert)") },
            singleLine = true, modifier = Modifier.fillMaxWidth())

        OutlinedTextField(
            code, { code = it.uppercase() },
            label = { Text("Kod parowania – taki sam na obu telefonach") },
            singleLine = true,
            textStyle = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace, fontSize = 24.sp),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { code = Prefs.newCode() }) { Text("Nowy kod") }
            OutlinedButton(onClick = {
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "Kod do Lokalizatora: $code")
                }, "Wyślij kod"))
            }) { Text("Wyślij kod") }
        }
        Text("Na pierwszym telefonie wygeneruj kod, na drugim wpisz dokładnie ten sam.",
            style = MaterialTheme.typography.bodySmall)

        Spacer(Modifier.height(4.dp))
        Text("Jak często wysyłać pozycję (tryb oszczędny)", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(30 to "30 s", 60 to "1 min", 120 to "2 min", 300 to "5 min").forEach { (v, l) ->
                FilterChip(selected = interval == v, onClick = { interval = v }, label = { Text(l) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Dokładny GPS także w trybie normalnym")
                Text("Lepsza dokładność (±5–15 m zamiast ±30–100 m), ale wyraźnie więcej baterii.",
                    style = MaterialTheme.typography.bodySmall)
            }
            Switch(precise, { precise = it })
        }

        Text("Czas trybu szukania", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(3, 5, 10).forEach { m ->
                FilterChip(selected = findMin == m, onClick = { findMin = m }, label = { Text("$m min") })
            }
        }

        TextButton(onClick = { advanced = !advanced }) {
            Text(if (advanced) "Ukryj zaawansowane" else "Zaawansowane (serwer)")
        }
        if (advanced) {
            Text(
                "Telefony łączą się przez serwer MQTT. Domyślny jest darmowy i publiczny – Wasze dane są " +
                    "zaszyfrowane, ale serwer nie daje gwarancji działania. Możesz podać własny (np. darmowe konto HiveMQ Cloud). " +
                    "Oba telefony muszą mieć ten sam serwer.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(broker, { broker = it }, label = { Text("Adres serwera") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Prefs.BROKERS.forEach { b ->
                    FilterChip(selected = broker == b, onClick = { broker = b },
                        label = { Text(b.substringAfter("://").substringBefore(":")) })
                }
            }
            OutlinedTextField(user, { user = it }, label = { Text("Login (opcjonalnie)") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(pass, { pass = it }, label = { Text("Hasło (opcjonalnie)") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth())
        }

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                if (Crypto.normalize(code).length < 6) {
                    toast(ctx, "Kod musi mieć co najmniej 6 znaków."); return@Button
                }
                prefs.name = name.ifBlank { android.os.Build.MODEL }
                prefs.code = code
                prefs.intervalSec = interval
                prefs.findMinutes = findMin
                prefs.preciseNormal = precise
                prefs.broker = broker.ifBlank { Prefs.DEFAULT_BROKER }
                prefs.user = user
                prefs.pass = pass
                launcher.launch(MainActivity.neededPermissions())
            },
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text("Zapisz i włącz udostępnianie") }
        if (canCancel) {
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Anuluj") }
        }
    }
}
