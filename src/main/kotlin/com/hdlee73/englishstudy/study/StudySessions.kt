package com.hdlee73.englishstudy.study

import com.hdlee73.englishstudy.dictionary.WordEntry

class FlashcardSession(cards: List<WordEntry>) {
    private val queue = ArrayDeque(cards)
    val total = cards.size
    var known = 0
        private set
    var missed = 0
        private set

    val current: WordEntry? get() = queue.firstOrNull()
    val remaining: Int get() = queue.size
    val finished: Boolean get() = queue.isEmpty()

    fun answer(knows: Boolean): WordEntry? {
        val card = queue.removeFirstOrNull() ?: return null
        if (knows) {
            known++
        } else {
            missed++
            queue.addLast(card) // Re-queue missed card for review
        }
        return card
    }
}

data class QuizQuestion(
    val word: String,
    val correctMeaning: String,
    val options: List<String>
)

class QuizSession(val questions: List<QuizQuestion>) {
    var currentIndex = 0
        private set
    var score = 0
        private set

    val current: QuizQuestion? get() = questions.getOrNull(currentIndex)
    val finished: Boolean get() = currentIndex >= questions.size

    fun submitAnswer(optionIndex: Int): Boolean {
        val q = current ?: return false
        val isCorrect = q.options.getOrNull(optionIndex) == q.correctMeaning
        if (isCorrect) score++
        currentIndex++
        return isCorrect
    }
}
