package com.najib.carwelcome

/** The 114 surahs, in order. Index 0 is Surah 1. */
val SURAH_NAMES = listOf(
    "Al-Fatihah", "Al-Baqarah", "Ali 'Imran", "An-Nisa", "Al-Ma'idah", "Al-An'am",
    "Al-A'raf", "Al-Anfal", "At-Tawbah", "Yunus", "Hud", "Yusuf", "Ar-Ra'd", "Ibrahim",
    "Al-Hijr", "An-Nahl", "Al-Isra", "Al-Kahf", "Maryam", "Taha", "Al-Anbiya", "Al-Hajj",
    "Al-Mu'minun", "An-Nur", "Al-Furqan", "Ash-Shu'ara", "An-Naml", "Al-Qasas",
    "Al-'Ankabut", "Ar-Rum", "Luqman", "As-Sajdah", "Al-Ahzab", "Saba", "Fatir", "Ya-Sin",
    "As-Saffat", "Sad", "Az-Zumar", "Ghafir", "Fussilat", "Ash-Shura", "Az-Zukhruf",
    "Ad-Dukhan", "Al-Jathiyah", "Al-Ahqaf", "Muhammad", "Al-Fath", "Al-Hujurat", "Qaf",
    "Adh-Dhariyat", "At-Tur", "An-Najm", "Al-Qamar", "Ar-Rahman", "Al-Waqi'ah", "Al-Hadid",
    "Al-Mujadila", "Al-Hashr", "Al-Mumtahanah", "As-Saf", "Al-Jumu'ah", "Al-Munafiqun",
    "At-Taghabun", "At-Talaq", "At-Tahrim", "Al-Mulk", "Al-Qalam", "Al-Haqqah",
    "Al-Ma'arij", "Nuh", "Al-Jinn", "Al-Muzzammil", "Al-Muddaththir", "Al-Qiyamah",
    "Al-Insan", "Al-Mursalat", "An-Naba", "An-Nazi'at", "'Abasa", "At-Takwir",
    "Al-Infitar", "Al-Mutaffifin", "Al-Inshiqaq", "Al-Buruj", "At-Tariq", "Al-A'la",
    "Al-Ghashiyah", "Al-Fajr", "Al-Balad", "Ash-Shams", "Al-Layl", "Ad-Duha", "Ash-Sharh",
    "At-Tin", "Al-'Alaq", "Al-Qadr", "Al-Bayyinah", "Az-Zalzalah", "Al-'Adiyat",
    "Al-Qari'ah", "At-Takathur", "Al-'Asr", "Al-Humazah", "Al-Fil", "Quraysh", "Al-Ma'un",
    "Al-Kawthar", "Al-Kafirun", "An-Nasr", "Al-Masad", "Al-Ikhlas", "Al-Falaq", "An-Nas",
)

/** A reciter and where to stream a full-surah MP3 from. */
data class Reciter(val id: String, val name: String, private val urlPattern: String) {
    fun url(surah: Int): String = urlPattern.format(surah)
}

val RECITERS = listOf(
    Reciter("alafasy", "Mishary Rashid Alafasy",
        "https://server8.mp3quran.net/afs/%03d.mp3"),
    Reciter("basit", "Abdul Basit (Murattal)",
        "https://server7.mp3quran.net/basit/%03d.mp3"),
    Reciter("husary", "Mahmoud Khalil Al-Husary",
        "https://server13.mp3quran.net/husr/%03d.mp3"),
    Reciter("sudais", "Abdul Rahman Al-Sudais",
        "https://server11.mp3quran.net/sds/%03d.mp3"),
)

/** Second source, used automatically if the chosen reciter's server fails. */
val FALLBACK_RECITER = Reciter("alafasy-cdn", "Mishary Rashid Alafasy",
    "https://cdn.islamic.network/quran/audio-surah/128/ar.alafasy/%d.mp3")

fun reciterById(id: String?): Reciter = RECITERS.firstOrNull { it.id == id } ?: RECITERS[0]

private fun normalize(s: String) =
    s.lowercase().replace(Regex("^(surah|sura|surat)\\s+"), "")
        .replace(Regex("^(al|an|ar|as|at|ash|az|ad|adh)[- ]"), "")
        .replace(Regex("[^a-z0-9]"), "")

/** Finds a surah from a spoken query like "surah yasin", "al kahf" or "36". */
fun findSurah(query: String): Int? {
    val q = query.trim()
    q.filter { it.isDigit() }.toIntOrNull()?.let { if (it in 1..114) return it }
    val nq = normalize(q)
    if (nq.isEmpty()) return null
    SURAH_NAMES.forEachIndexed { i, name -> if (normalize(name) == nq) return i + 1 }
    SURAH_NAMES.forEachIndexed { i, name ->
        val n = normalize(name)
        if (n.startsWith(nq) || nq.startsWith(n)) return i + 1
    }
    return null
}
