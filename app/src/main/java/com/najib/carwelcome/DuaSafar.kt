package com.najib.carwelcome

/**
 * The dua in the bundled recording, split into the short phrases shown on the car
 * screen. The recording is the dua for riding (Abu Dawud 2602, Tirmidhi 3446):
 * Bismillah, Alhamdulillah, "Subhanalladhi sakhkhara lana hadha…", Alhamdulillah ×3,
 * Allahu akbar ×3, then "Subhanaka Allahumma inni zalamtu nafsi…".
 *
 * [Line.wordStarts] is when each word starts in the bundled recording (ms), measured
 * with speech recognition and the reciter's pauses. The words light up as they're recited.
 */
object DuaSafar {
    data class Line(
        val arabic: String,
        val transliteration: String,
        val meaning: String,
        val wordStarts: List<Int>,
    ) {
        val words: List<String> get() = arabic.split(' ')
    }

    val LINES = listOf(
        Line("بِسْمِ اللَّهِ", "Bismillah", "In the name of Allah.", listOf(690, 1_100)),
        Line("الْحَمْدُ لِلَّهِ", "Alhamdulillah", "All praise is for Allah.", listOf(3_020, 4_000)),
        Line(
            "سُبْحَانَ الَّذِي سَخَّرَ لَنَا هَذَا", "Subhanalladhi sakhkhara lana hadha",
            "Glory be to the One who placed this at our service,", listOf(6_030, 6_900, 7_300, 8_200, 9_000),
        ),
        Line(
            "وَمَا كُنَّا لَهُ مُقْرِنِينَ", "wa ma kunna lahu muqrinin",
            "for we could never have done it ourselves.", listOf(10_750, 11_480, 12_400, 13_000),
        ),
        Line(
            "وَإِنَّا إِلَى رَبِّنَا لَمُنْقَلِبُونَ", "Wa inna ila rabbina lamunqalibun",
            "And to our Lord we will surely return.", listOf(15_400, 16_450, 16_900, 17_870),
        ),
        Line("الْحَمْدُ لِلَّهِ", "Alhamdulillah", "All praise is for Allah.", listOf(22_020, 23_200)),
        Line("الْحَمْدُ لِلَّهِ", "Alhamdulillah", "All praise is for Allah.", listOf(24_650, 25_850)),
        Line("الْحَمْدُ لِلَّهِ", "Alhamdulillah", "All praise is for Allah.", listOf(27_370, 28_550)),
        Line("اللَّهُ أَكْبَرُ", "Allahu akbar", "Allah is the Greatest.", listOf(30_040, 30_750)),
        Line("اللَّهُ أَكْبَرُ", "Allahu akbar", "Allah is the Greatest.", listOf(32_130, 32_850)),
        Line("اللَّهُ أَكْبَرُ", "Allahu akbar", "Allah is the Greatest.", listOf(34_180, 34_900)),
        Line(
            "سُبْحَانَكَ اللَّهُمَّ إِنِّي ظَلَمْتُ نَفْسِي", "Subhanaka Allahumma inni zalamtu nafsi",
            "Glory be to You, O Allah. I have wronged myself,", listOf(36_360, 37_360, 38_500, 40_100, 41_200),
        ),
        Line("فَاغْفِرْ لِي", "faghfir li", "so forgive me,", listOf(42_810, 43_390)),
        Line(
            "فَإِنَّهُ لَا يَغْفِرُ الذُّنُوبَ إِلَّا أَنْتَ", "fa innahu la yaghfirudh-dhunuba illa ant",
            "for none forgives sins except You.", listOf(44_920, 46_400, 46_900, 47_700, 48_900, 50_600),
        ),
    )
}
