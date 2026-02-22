package com.conduit.app

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "dictionary")
data class WordEntity(
    @PrimaryKey val id: Int,
    val word: String
)

@Dao
interface DictionaryDao {
    @Query("SELECT id, word FROM dictionary WHERE word IN (:words)")
    suspend fun getWordsByList(words: List<String>): List<WordEntity>

    @Query("SELECT word FROM dictionary WHERE id = :id LIMIT 1")
    suspend fun getWordById(id: Int): String?
}

@Database(entities = [WordEntity::class], version = 1)
abstract class GhostDatabase : RoomDatabase() {
    abstract fun dictionaryDao(): DictionaryDao
}

class GhostSqueezer(private val dao: DictionaryDao) {
    suspend fun compress(text: String): ByteArray {
        val output = mutableListOf<Byte>()
        val tokens = text.split(Regex("[\\s\\u1361]+")).filter { it.trim().isNotEmpty() }
        
        // 1. Get unique words and chunk them for SQLite limits
        val uniqueWords = tokens.distinct()
        val wordToIdMap = mutableMapOf<String, Int>()
        
        uniqueWords.chunked(500).forEach { chunk ->
            val results = dao.getWordsByList(chunk)
            results.forEach { wordToIdMap[it.word] = it.id }
        }

        // 2. Process tokens using the map
        for (word in tokens) {
            val wordId = wordToIdMap[word]
            if (wordId != null) {
                // Varint encoding
                var v = wordId
                while (v >= 0x80) {
                    output.add(((v and 0x7F) or 0x80).toByte())
                    v = v ushr 7
                }
                output.add(v.toByte())
            } else {
                // Literal fallback
                output.add(0.toByte())
                val raw = word.toByteArray(Charsets.UTF_8)
                output.add(raw.size.coerceAtMost(255).toByte())
                output.addAll(raw.toList())
            }
        }
        return output.toByteArray()
    }

    suspend fun decompress(bytes: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            
            if (b == 0) {
                // Literal read
                val len = bytes[i + 1].toInt() and 0xFF
                val wordBytes = bytes.copyOfRange(i + 2, i + 2 + len)
                sb.append(String(wordBytes, Charsets.UTF_8)).append(" ")
                i += 2 + len
            } else {
                // Varint read
                var value = 0
                var shift = 0
                var currentByte: Int
                do {
                    currentByte = bytes[i].toInt() and 0xFF
                    value = value or ((currentByte and 0x7F) shl shift)
                    shift += 7
                    if (currentByte >= 0x80) i++
                } while (currentByte >= 0x80 && i < bytes.size)
                
                val word = dao.getWordById(value)
                sb.append(word ?: "?").append(" ")
                i++
            }
        }
        return sb.toString().trim()
    }
}