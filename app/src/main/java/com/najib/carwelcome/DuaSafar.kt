package com.najib.carwelcome

/** Dua al-Safar (Sahih Muslim 1342), split into the lines shown on the car screen. */
object DuaSafar {
    data class Line(val arabic: String, val transliteration: String, val meaning: String)

    val LINES = listOf(
        Line(
            "اللَّهُ أَكْبَرُ، اللَّهُ أَكْبَرُ، اللَّهُ أَكْبَرُ، سُبْحَانَ الَّذِي سَخَّرَ لَنَا هَذَا وَمَا كُنَّا لَهُ مُقْرِنِينَ",
            "Allahu akbar (3x). Subhanalladhi sakhkhara lana hadha wa ma kunna lahu muqrinin",
            "Allah is the Greatest. Glory be to the One who has placed this at our service, for we could never have done it ourselves.",
        ),
        Line(
            "وَإِنَّا إِلَى رَبِّنَا لَمُنْقَلِبُونَ",
            "Wa inna ila rabbina lamunqalibun",
            "And to our Lord we will surely return.",
        ),
        Line(
            "اللَّهُمَّ إِنَّا نَسْأَلُكَ فِي سَفَرِنَا هَذَا الْبِرَّ وَالتَّقْوَى، وَمِنَ الْعَمَلِ مَا تَرْضَى",
            "Allahumma inna nas'aluka fi safarina hadha al-birra wat-taqwa, wa minal-'amali ma tarda",
            "O Allah, we ask You on this journey for righteousness and piety, and deeds that please You.",
        ),
        Line(
            "اللَّهُمَّ هَوِّنْ عَلَيْنَا سَفَرَنَا هَذَا وَاطْوِ عَنَّا بُعْدَهُ",
            "Allahumma hawwin 'alayna safarana hadha watwi 'anna bu'dah",
            "O Allah, make this journey easy for us and shorten its distance.",
        ),
        Line(
            "اللَّهُمَّ أَنْتَ الصَّاحِبُ فِي السَّفَرِ، وَالْخَلِيفَةُ فِي الأَهْلِ",
            "Allahumma antas-sahibu fis-safar, wal-khalifatu fil-ahl",
            "O Allah, You are the Companion on the journey and the Guardian of the family.",
        ),
        Line(
            "اللَّهُمَّ إِنِّي أَعُوذُ بِكَ مِنْ وَعْثَاءِ السَّفَرِ، وَكَآبَةِ الْمَنْظَرِ، وَسُوءِ الْمُنْقَلَبِ فِي الْمَالِ وَالأَهْلِ",
            "Allahumma inni a'udhu bika min wa'tha'is-safar, wa ka'abatil-manzar, wa su'il-munqalabi fil-mali wal-ahl",
            "O Allah, I seek refuge in You from the hardship of travel, from distressing sights, and from an ill return to property and family.",
        ),
    )
}
