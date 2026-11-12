package com.kingzcheung.xime.ui.settings
import androidx.compose.runtime.Composable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import com.kingzcheung.xime.ui.theme.hapticAction
@Composable internal fun BackupOutcomeDialog(title:String,message:String,onDismiss:()->Unit){
    AlertDialog(onDismissRequest=onDismiss,title={Text(title)},text={Text(message,Modifier.verticalScroll(rememberScrollState()))},
        confirmButton={TextButton(onClick=hapticAction(onDismiss)){Text("知道了")}})
}
