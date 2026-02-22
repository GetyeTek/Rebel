package com.conduit.app

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "dictionary")
data class WordEntity(
    @PrimaryKey val word: String,
    val byteCode: Int
)

@Dao
interface DictionaryDao {
    @Query("SELECT * FROM dictionary WHERE word = :word LIMIT 1")
    suspend fun getWord(word: String): WordEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(word: WordEntity)

    @Query("SELECT * FROM dictionary")
    fun getAll(): Flow<List<WordEntity>>
}

@Database(entities = [WordEntity::class], version = 1)
abstract class GhostDatabase : RoomDatabase() {
    abstract fun dictionaryDao(): DictionaryDao
}

class GhostSqueezer(private val dao: DictionaryDao) {
    suspend fun compress(text: String): ByteArray {
        val output = mutableListOf<Byte>()
        text.split(" ").forEach { word ->
            val clean = word.lowercase().trim()
            if (clean.isEmpty()) return@forEach
            
            val match = dao.getWord(clean)
            if (match != null) {
                output.add((match.byteCode + 128).toByte())
            } else {
                // Literal tag for unknown word: [word]
                output.add('['.code.toByte())
                output.addAll(clean.toByteArray().toList())
                output.add(']'.code.toByte())
            }
            output.add(32.toByte()) // Space separator
        }
        return output.toByteArray()
    }

    suspend fun decompress(bytes: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            when {
                b >= 128 -> {
                    // Dictionary lookup logic would go here
                    sb.append("TOKEN_${b-128} ")
                }
                b == '['.code -> {
                    val end = bytes.indexOf(']'.code.toByte(), i)
                    if (end != -1) {
                        sb.append(String(bytes.copyOfRange(i + 1, end)) + " ")
                        i = end
                    }
                }
                else -> sb.append(b.toChar())
            }
            i++
        }
        return sb.toString()
    }
}