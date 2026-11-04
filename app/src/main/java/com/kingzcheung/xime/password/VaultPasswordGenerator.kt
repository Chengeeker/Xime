package com.kingzcheung.xime.password

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.ui.settings.HapticStepSlider
import com.kingzcheung.xime.ui.theme.hapticAction
import java.security.SecureRandom
import kotlin.math.roundToInt

@Composable
internal fun VaultPasswordGenerator(onUse: (String) -> Unit) {
    var length by remember { mutableIntStateOf(20) }
    var password by remember { mutableStateOf(generateVaultPassword(length)) }
    Column(Modifier.fillMaxWidth().padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text("密码生成器", style = MaterialTheme.typography.titleLarge)
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Text(password, Modifier.fillMaxWidth().padding(24.dp), style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace))
        }
        Text("密码长度：$length", style = MaterialTheme.typography.titleMedium)
        HapticStepSlider(length.toFloat(), { length = it.roundToInt(); password = generateVaultPassword(length) }, valueRange = 12f..64f, steps = 51)
        Button(onClick = hapticAction { password = generateVaultPassword(length) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Autorenew, null)
            Text("重新生成", Modifier.padding(start = 8.dp))
        }
        OutlinedButton(onClick = hapticAction { onUse(password) }, modifier = Modifier.fillMaxWidth()) { Text("用于新增登录条目") }
    }
}

internal fun generateVaultPassword(length: Int): String {
    require(length in 12..64)
    val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#%+-_"
    val random = SecureRandom()
    return CharArray(length) { alphabet[random.nextInt(alphabet.length)] }.concatToString()
}
