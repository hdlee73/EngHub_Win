package com.hdlee73.englishstudy.dictionary

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

object SavedWordsStore {
    private val dataDir: File by lazy {
        val appData = System.getenv("APPDATA") ?: System.getProperty("user.home")
        File(appData, "EngHub").apply { mkdirs() }
    }

    private val saveFile: File by lazy {
        File(dataDir, "saved_words.tsv")
    }

    private val _savedWords = MutableStateFlow<List<WordEntry>>(emptyList())
    val savedWords: StateFlow<List<WordEntry>> = _savedWords.asStateFlow()

    init {
        load()
    }

    private fun load() {
        if (!saveFile.exists()) return
        val list = mutableListOf<WordEntry>()
        saveFile.forEachLine { line ->
            val parts = line.split('\t')
            if (parts.isNotEmpty() && parts[0].isNotBlank()) {
                list += WordEntry(
                    word = parts[0],
                    ipa = parts.getOrElse(1) { "" },
                    korean = parts.getOrElse(2) { "" }.replace("\\n", "\n"),
                    english = parts.getOrElse(3) { "" },
                    examples = parts.getOrElse(4) { "" }.replace("\\n", "\n")
                )
            }
        }
        _savedWords.value = list
    }

    private fun save() {
        val lines = _savedWords.value.map { w ->
            "${w.word}\t${w.ipa}\t${w.korean.replace("\n", "\\n")}\t${w.english}\t${w.examples.replace("\n", "\\n")}"
        }
        saveFile.writeText(lines.joinToString("\n"), Charsets.UTF_8)
    }

    fun isSaved(word: String): Boolean {
        return _savedWords.value.any { it.word.equals(word.trim(), ignoreCase = true) }
    }

    fun toggle(entry: WordEntry) {
        val current = _savedWords.value.toMutableList()
        val existingIndex = current.indexOfFirst { it.word.equals(entry.word.trim(), ignoreCase = true) }
        if (existingIndex >= 0) {
            current.removeAt(existingIndex)
        } else {
            current.add(0, entry)
        }
        _savedWords.value = current
        save()
    }

    fun remove(word: String) {
        val current = _savedWords.value.filterNot { it.word.equals(word.trim(), ignoreCase = true) }
        _savedWords.value = current
        save()
    }
}
