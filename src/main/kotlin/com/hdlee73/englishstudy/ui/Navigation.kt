package com.hdlee73.englishstudy.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector

enum class AppTab(val label: String, val icon: ImageVector) {
    LISTENING("리스닝", Icons.Outlined.Headphones),
    DICTIONARY("단어·사전", Icons.Outlined.MenuBook),
    READING("리딩", Icons.Outlined.Newspaper),
    FLASHCARDS("단어카드", Icons.Outlined.Style),
    QUIZ("퀴즈", Icons.Outlined.Quiz),
    PHRASES("회화표현", Icons.Outlined.Forum),
    TRANSLATE("번역", Icons.Outlined.Translate),
    DOCVOICE("자막생성", Icons.Outlined.GraphicEq)
}
