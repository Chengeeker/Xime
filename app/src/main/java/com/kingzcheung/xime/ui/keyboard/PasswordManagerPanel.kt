package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.ui.theme.hapticAction

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun PasswordManagerPanel(
    suggestions: List<*>,
    backgroundColor: androidx.compose.ui.graphics.Color,
    textColor: androidx.compose.ui.graphics.Color,
    onOpenProviderSettings: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .clipToBounds()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("当前输入会话的系统自动填充建议", style = MaterialTheme.typography.titleSmall, color = textColor)
        if (suggestions.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "当前会话没有收到凭据建议。请连接并解锁 Xime 密码库，再将 Xime 设为系统自动填充或凭据提供方。",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor,
                )
            }
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(suggestions, key = { index, _ -> index }) { _, suggestion ->
                    Card {
                        Box(
                            modifier = Modifier
                                .width(240.dp)
                                .height(56.dp),
                        ) {
                            InlineSuggestionView(suggestion, Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
        Button(onClick = hapticAction(Button@ { context.startActivity(android.content.Intent(context, com.kingzcheung.xime.password.BitwardenVaultActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) })) {
            Icon(Icons.TwoTone.VpnKey, contentDescription = null)
            Text("打开 Bitwarden 保险库", modifier = Modifier.padding(start = 8.dp))
        }
        Button(onClick = hapticAction(onOpenProviderSettings)) {
            Icon(Icons.TwoTone.VpnKey, contentDescription = null)
            Text("系统密码服务设置", modifier = Modifier.padding(start = 8.dp))
        }
        Text(
            "Xime 只显示当前 Android Autofill 会话提供的真实建议，不会主动读取目标应用的密码字段。",
            style = MaterialTheme.typography.bodySmall,
            color = textColor,
        )
    }
}
