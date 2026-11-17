package io.embodit.fourdrop.domain

enum class Language { SYSTEM, ZH, EN }

/** App settings + the two win counters on the base (design dataModel.settings / progress). */
data class Settings(
    val language: Language = Language.SYSTEM,
    val sound: Boolean = true,
    /** Volume stop 0..2 = 45 / 80 / 100 % (VOLUME_PERCENT), default the middle stop. */
    val volume: Int = 1,
    val reducedMotion: Boolean = false,
    val opponent: Opponent = Opponent.EASY,
    val firstRunDone: Boolean = false,
    /** The column mouths glow for the first three drops of the very first game, then never again. */
    val firstGameLit: Boolean = false,
    val winsRed: Int = 0,
    val winsYellow: Int = 0,
)

/** What a game looks like on disk (design dataModel.storage: files/game.json, every drop). */
data class SavedGame(val position: Position, val opponent: Opponent, val pending: Opponent?)

interface GameStore {
    fun load(): SavedGame?
    fun save(game: SavedGame)
    fun clear()
}

/** `fd1|R|4435|EASY|-`: version, first seat, the 1-based column string, this game's opponent, the next game's (or -). */
object SaveCodec {
    fun encode(g: SavedGame): String = listOf(
        "fd1", if (g.position.firstSeat == Seat.RED) "R" else "Y", g.position.code(), g.opponent.name, g.pending?.name ?: "-",
    ).joinToString("|")

    fun decode(text: String): SavedGame? = runCatching {
        val f = text.trim().split("|")
        if (f.size != 5 || f[0] != "fd1") return null
        val first = when (f[1]) { "R" -> Seat.RED; "Y" -> Seat.YELLOW; else -> return null }
        val p = Rules.fromCode(f[2], first)
        if (p.code() != f[2]) return null // an illegal move string is a corrupt save
        SavedGame(p, Opponent.valueOf(f[3]), if (f[4] == "-") null else Opponent.valueOf(f[4]))
    }.getOrNull()
}
