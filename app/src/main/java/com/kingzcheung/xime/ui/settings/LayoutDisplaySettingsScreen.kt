package com.kingzcheung.xime.ui.settings

import com.kingzcheung.xime.R
import com.kingzcheung.xime.i18n.tr
import com.kingzcheung.xime.ui.theme.hapticAction
import com.kingzcheung.xime.ui.theme.hapticToggle


import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.twotone.Straighten
import androidx.compose.material.icons.twotone.Storefront
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.keyboard.KeyboardResizeBounds

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayoutDisplaySettingsContent(
    onBack: () -> Unit,
    onNavigateToMarket: () -> Unit = {},
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp
    val heightBounds = remember(configuration.screenHeightDp, isLandscape) {
        KeyboardResizeBounds.heightBoundsDp(configuration.screenHeightDp, isLandscape)
    }
    val marginMax = remember(configuration.screenWidthDp) {
        KeyboardResizeBounds.maxHorizontalMarginDp(configuration.screenWidthDp)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(tr(R.string.s_bujuyuxianshi)) },
                navigationIcon = {
                    IconButton(onClick = hapticAction(onBack)) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = tr(R.string.s_fanhui)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                SettingsSection(title = "键盘尺寸", content = {
                    val heightPref = SettingsPreferences.getKeyboardHeightDp(context, isLandscape)
                        .coerceIn(heightBounds.first, heightBounds.last)
                    var keyboardHeight by remember(isLandscape, configuration.screenHeightDp) {
                        mutableStateOf(heightPref.toFloat())
                    }
                    DimensionSlider(
                        title = "键盘高度",
                        value = keyboardHeight,
                        valueLabel = "${keyboardHeight.toInt()} dp",
                        range = heightBounds.first.toFloat()..heightBounds.last.toFloat(),
                        onValueChange = { keyboardHeight = it },
                        onValueChangeFinished = { SettingsPreferences.setKeyboardHeightDp(context, keyboardHeight.toInt(), isLandscape) },
                    )

                    val bottomPref = SettingsPreferences.getKeyboardBottomPaddingDp(context)
                        .coerceIn(0, KeyboardResizeBounds.MAX_BOTTOM_PADDING_DP)
                    var bottomPadding by remember { mutableStateOf(bottomPref.toFloat()) }
                    DimensionSlider(
                        title = "键盘底部留白",
                        value = bottomPadding,
                        valueLabel = "${bottomPadding.toInt()} dp",
                        range = 0f..KeyboardResizeBounds.MAX_BOTTOM_PADDING_DP.toFloat(),
                        onValueChange = { bottomPadding = it },
                        onValueChangeFinished = { SettingsPreferences.setKeyboardBottomPaddingDp(context, bottomPadding.toInt()) },
                    )

                    val startPref = SettingsPreferences.getKeyboardMarginStartDp(context).coerceIn(0, marginMax)
                    var marginStart by remember(configuration.screenWidthDp) { mutableStateOf(startPref.toFloat()) }
                    if (marginMax > 0) {
                        DimensionSlider(
                            title = "键盘左侧边距",
                            value = marginStart,
                            valueLabel = "${marginStart.toInt()} dp",
                            range = 0f..marginMax.toFloat(),
                            onValueChange = { marginStart = it },
                            onValueChangeFinished = { SettingsPreferences.setKeyboardMarginStartDp(context, marginStart.toInt()) },
                        )
                    }

                    val endPref = SettingsPreferences.getKeyboardMarginEndDp(context).coerceIn(0, marginMax)
                    var marginEnd by remember(configuration.screenWidthDp) { mutableStateOf(endPref.toFloat()) }
                    if (marginMax > 0) {
                        DimensionSlider(
                        title = "键盘右侧边距",
                            value = marginEnd,
                            valueLabel = "${marginEnd.toInt()} dp",
                            range = 0f..marginMax.toFloat(),
                            onValueChange = { marginEnd = it },
                            onValueChangeFinished = { SettingsPreferences.setKeyboardMarginEndDp(context, marginEnd.toInt()) },
                        )
                    }
                    Text(
                        "这些尺寸会保存到键盘偏好；键盘调节层可继续直接拖动到系统导航区域。",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                })
            }

            item {
                SettingsSection(title = "键盘布局", content = {
                    var hardwareKeyboardDetectionEnabled by remember {
                        mutableStateOf(SettingsPreferences.isHardwareKeyboardDetectionEnabled(context))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = tr(R.string.s_shitijianpanjiance), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text(text = tr(R.string.s_guanbihoubujianceshitijianpan_6aaf1), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = hardwareKeyboardDetectionEnabled,
                            onCheckedChange = hapticToggle(Switch@ { enabled ->
                                hardwareKeyboardDetectionEnabled = enabled
                                SettingsPreferences.setHardwareKeyboardDetectionEnabled(context, enabled)
                            })
                        )
                    }

                    var landscapeSplitKeyboardEnabled by remember {
                        mutableStateOf(SettingsPreferences.isLandscapeSplitKeyboardEnabled(context))
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = tr(R.string.s_hengpingshiyongfentijianpan),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = tr(R.string.s_guanbihou_hengpingjiangxianshi_e98c9),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = landscapeSplitKeyboardEnabled,
                            onCheckedChange = hapticToggle(Switch@ { enabled ->
                                landscapeSplitKeyboardEnabled = enabled
                                SettingsPreferences.setLandscapeSplitKeyboardEnabled(context, enabled)
                            })
                        )
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )

                    var keyboardFlushBottom by remember {
                        mutableStateOf(SettingsPreferences.isKeyboardFlushBottom(context))
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = tr(R.string.s_jianpantiedixianshi),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = tr(R.string.s_yincangdibudaohanglanhuoshoush_daf00),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = keyboardFlushBottom,
                            onCheckedChange = { enabled ->
                                keyboardFlushBottom = enabled
                                SettingsPreferences.setKeyboardFlushBottom(context, enabled)
                            }
                        )
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    SettingsItem(
                        icon = Icons.TwoTone.Storefront,
                        title = tr(R.string.s_bujushichang),
                        subtitle = tr(R.string.s_qushangdiangengduobuju),
                        onClick = onNavigateToMarket,
                        showArrow = true,
                    )
                })
            }

            item {
                SettingsSection(title = "键盘输入", content = {
                    var numberLayout by remember { mutableStateOf(SettingsPreferences.getNumberKeyboardLayout(context)) }
                    Text("数字键盘布局", Modifier.padding(start = 16.dp, top = 16.dp), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("full" to "全键盘数字", "keypad" to "九宫格数字").forEach { (value, label) ->
                            FilterChip(selected = numberLayout == value, onClick = hapticAction {
                                numberLayout = value
                                SettingsPreferences.setNumberKeyboardLayout(context, value)
                            }, label = { Text(label) })
                        }
                    }

                    var swipeMode by remember { mutableStateOf(SettingsPreferences.getSwipeInputMode(context)) }
                    Text("上滑输入", Modifier.padding(start = 16.dp, top = 8.dp), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("off" to "关闭", "numbers_only" to "仅数字", "numbers_symbols" to "数字和符号").forEach { (value, label) ->
                            FilterChip(selected = swipeMode == value, onClick = hapticAction {
                                swipeMode = value
                                SettingsPreferences.setSwipeInputMode(context, value)
                            }, label = { Text(label) })
                        }
                    }

                    var emojiEnabled by remember { mutableStateOf(SettingsPreferences.isEmojiKeyEnabled(context)) }
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("显示表情键", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text("隐藏键盘布局中的表情入口", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = emojiEnabled, onCheckedChange = hapticToggle { enabled ->
                            emojiEnabled = enabled
                            SettingsPreferences.setEmojiKeyEnabled(context, enabled)
                        })
                    }

                    var keyBubbleEnabled by remember { mutableStateOf(SettingsPreferences.isKeyBubbleEnabled(context)) }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("全键盘按键气泡", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text("关闭后，按下字母键时不显示放大提示", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = keyBubbleEnabled, onCheckedChange = hapticToggle { enabled ->
                            keyBubbleEnabled = enabled
                            SettingsPreferences.setKeyBubbleEnabled(context, enabled)
                        })
                    }
                })
            }

            item {
                SettingsSection(title = "候选词", content = {
                    val candidateTextSizePref = SettingsPreferences.getCandidateTextSize(context)
                    var candidateTextSize by remember(candidateTextSizePref) {
                        mutableStateOf(candidateTextSizePref.toFloat())
                    }

                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = tr(R.string.s_houxuanzidaxiao),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        CandidateTextSizeCard(
                            candidateTextSize = candidateTextSize,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        HapticStepSlider(
                            value = candidateTextSize,
                            onValueChange = { candidateTextSize = it },
                            onValueChangeFinished = {
                                SettingsPreferences.setCandidateTextSize(context, candidateTextSize.toInt())
                            },
                            valueRange = 12f..22f,
                            steps = 9
                        )
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )

                    var showComments by remember {
                        mutableStateOf(SettingsPreferences.showCandidateComments(context))
                    }

                    Text(
                        text = tr(R.string.s_bianmazhushi),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
                    )
                    Text(
                        text = tr(R.string.s_zaihouxuancipangxianshiduiying_de529),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, bottom = 12.dp)
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp)
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CommentDisplayCard(
                            title = tr(R.string.s_xianshi),
                            isSelected = showComments,
                            showComment = true,
                            onClick = {
                                showComments = true
                                SettingsPreferences.setShowCandidateComments(context, true)
                            },
                            modifier = Modifier.weight(1f)
                        )
                        CommentDisplayCard(
                            title = tr(R.string.s_yincang),
                            isSelected = !showComments,
                            showComment = false,
                            onClick = {
                                showComments = false
                                SettingsPreferences.setShowCandidateComments(context, false)
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )

                    var inputTextLocation by remember {
                        mutableStateOf(SettingsPreferences.getInputTextLocation(context))
                    }

                    Text(
                        text = tr(R.string.s_bianmaxianshi),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
                    )
                    Text(
                        text = tr(R.string.s_xuanzeshurubianmadexianshiweizhi),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, bottom = 12.dp)
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CodeDisplayCard(
                            title = tr(R.string.s_xianshizaishurukuang),
                            isSelected = inputTextLocation == SettingsPreferences.INPUT_TEXT_INPUT_BOX,
                            showCodeInInputBox = true,
                            onClick = {
                                inputTextLocation = SettingsPreferences.INPUT_TEXT_INPUT_BOX
                                SettingsPreferences.setInputTextLocation(context, SettingsPreferences.INPUT_TEXT_INPUT_BOX)
                            },
                            modifier = Modifier.weight(1f)
                        )
                        CodeDisplayCard(
                            title = tr(R.string.s_xianshizaihouxuanlan),
                            isSelected = inputTextLocation == SettingsPreferences.INPUT_TEXT_CANDIDATE_BAR,
                            showCodeInInputBox = false,
                            onClick = {
                                inputTextLocation = SettingsPreferences.INPUT_TEXT_CANDIDATE_BAR
                                SettingsPreferences.setInputTextLocation(context, SettingsPreferences.INPUT_TEXT_CANDIDATE_BAR)
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )

                    val pageSizePref = SettingsPreferences.getPageSize(context)
                    val effectiveValue = if (pageSizePref == 0) 20f else pageSizePref.toFloat()
                    var pageSizeSlider by remember(effectiveValue) {
                        mutableStateOf(effectiveValue)
                    }

                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = tr(R.string.s_meiyehouxuancishu),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = tr(R.string.s_ge, pageSizeSlider.toInt()),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        HapticStepSlider(
                            value = pageSizeSlider,
                            onValueChange = { pageSizeSlider = it },
                            onValueChangeFinished = {
                                val intValue = pageSizeSlider.toInt()
                                SettingsPreferences.setPageSize(context, intValue)
                            },
                            valueRange = 5f..50f,
                            steps = 44
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = tr(R.string.s_xiugaihouxudaofanganshezhizhon_603c8),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                })
            }

            item {
                SettingsSection(title = tr(R.string.s_shurukuangshipei), content = {
                    var autoNumberKeyboard by remember {
                        mutableStateOf(SettingsPreferences.isAutoNumberKeyboardEnabled(context))
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = tr(R.string.s_shuzikuangzidongshuzijianpan),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = tr(R.string.s_jinruhaoma_yanzhengmadengshuzi_1aed9),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = autoNumberKeyboard,
                            onCheckedChange = hapticToggle(Switch@ { newValue ->
                                autoNumberKeyboard = newValue
                                SettingsPreferences.setAutoNumberKeyboardEnabled(context, newValue)
                            })
                        )
                    }
                })
            }
        }
    }
}

@Composable
private fun DimensionSlider(
    title: String,
    value: Float,
    valueLabel: String,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(valueLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        if (range.endInclusive > range.start) {
            HapticStepSlider(
                value = value,
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                valueRange = range,
                steps = (((range.endInclusive - range.start) / 8f).toInt() - 1).coerceAtLeast(0),
            )
        }
    }
}
