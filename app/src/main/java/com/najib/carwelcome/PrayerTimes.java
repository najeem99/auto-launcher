package com.najib.carwelcome;

/**
 * Offline prayer times from the sun's position (no network, no library).
 *
 * Uses the UAE method (as published for Dubai / the UAE): Fajr and Isha at
 * 18.2 degrees below the horizon, Asr when a shadow is its length plus the noon
 * shadow (standard / Shafi'i), and the UAE's precautionary minutes: Sunrise -3,
 * Dhuhr +3, Asr +3, Maghrib +3.
 *
 * All results are epoch milliseconds, so they don't depend on the phone's time zone.
 */
public final class PrayerTimes {

    public static final String[] NAMES = {"Fajr", "Sunrise", "Dhuhr", "Asr", "Maghrib", "Isha"};
    public static final int FAJR = 0, SUNRISE = 1, DHUHR = 2, ASR = 3, MAGHRIB = 4, ISHA = 5;

    private static final double FAJR_ANGLE = 18.2;
    private static final double ISHA_ANGLE = 18.2;
    /** Minutes added to each time, in NAMES order. */
    private static final int[] ADJUST_MIN = {0, -3, 3, 3, 3, 0};

    private PrayerTimes() {}

    /**
     * The six times for one calendar day.
     *
     * @param year  calendar year of the local date
     * @param month 1-12
     * @param day   1-31
     * @param lat   latitude in degrees (north positive)
     * @param lng   longitude in degrees (east positive)
     * @return epoch ms for Fajr, Sunrise, Dhuhr, Asr, Maghrib, Isha
     */
    public static long[] forDay(int year, int month, int day, double lat, double lng) {
        // Midnight UTC of the date, as epoch ms and as a Julian day.
        long dayStartMs = daysFromCivil(year, month, day) * 86_400_000L;
        double jd0 = dayStartMs / 86_400_000.0 + 2440587.5;

        // First guess (hours, UTC); then refine twice with the sun at each guessed time.
        double[] t = {5, 6, 12, 13, 18, 19};
        for (int i = 0; i < 6; i++) t[i] -= lng / 15.0;
        for (int iter = 0; iter < 3; iter++) t = compute(t, jd0, lat, lng);

        // High latitude fallback: if the sun never reaches 18.2 degrees, use a
        // seventh of the night (only matters far north; never in the UAE).
        double night = (t[SUNRISE] + 24) - t[MAGHRIB];
        if (Double.isNaN(t[FAJR]) || t[SUNRISE] - t[FAJR] > night) t[FAJR] = t[SUNRISE] - night / 7;
        if (Double.isNaN(t[ISHA]) || t[ISHA] - t[MAGHRIB] > night) t[ISHA] = t[MAGHRIB] + night / 7;

        long[] out = new long[6];
        for (int i = 0; i < 6; i++) {
            double minutes = t[i] * 60.0 + ADJUST_MIN[i];
            // Round to the nearest minute, the way timetables are printed.
            out[i] = dayStartMs + Math.round(minutes) * 60_000L;
        }
        return out;
    }

    private static double[] compute(double[] guess, double jd0, double lat, double lng) {
        double[] t = new double[6];
        double noon = midDay(jd0 + guess[DHUHR] / 24.0, lng);
        t[FAJR] = noon - hourAngle(jd0 + guess[FAJR] / 24.0, FAJR_ANGLE, lat);
        t[SUNRISE] = noon - hourAngle(jd0 + guess[SUNRISE] / 24.0, 0.833, lat);
        t[DHUHR] = noon;
        t[ASR] = noon + asrHours(jd0 + guess[ASR] / 24.0, lat);
        t[MAGHRIB] = noon + hourAngle(jd0 + guess[MAGHRIB] / 24.0, 0.833, lat);
        t[ISHA] = noon + hourAngle(jd0 + guess[ISHA] / 24.0, ISHA_ANGLE, lat);
        return t;
    }

    /** Solar noon in UTC hours. */
    private static double midDay(double jd, double lng) {
        return 12 - lng / 15.0 - sun(jd)[1];
    }

    /** Hours from noon until the sun is `angle` degrees below the horizon. */
    private static double hourAngle(double jd, double angle, double lat) {
        double decl = sun(jd)[0];
        double cos = (-sin(angle) - sin(decl) * sin(lat)) / (cos(decl) * cos(lat));
        return acos(cos) / 15.0;
    }

    /** Hours from noon until Asr (shadow = object + noon shadow). */
    private static double asrHours(double jd, double lat) {
        double decl = sun(jd)[0];
        double alt = Math.toDegrees(Math.atan(1.0 / (1 + Math.tan(Math.toRadians(Math.abs(lat - decl))))));
        double cos = (sin(alt) - sin(decl) * sin(lat)) / (cos(decl) * cos(lat));
        return acos(cos) / 15.0;
    }

    /** {declination in degrees, equation of time in hours} (US Naval Observatory approximation). */
    static double[] sun(double jd) {
        double d = jd - 2451545.0;
        double g = fix(357.529 + 0.98560028 * d, 360);
        double q = fix(280.459 + 0.98564736 * d, 360);
        double l = fix(q + 1.915 * sin(g) + 0.020 * sin(2 * g), 360);
        double e = 23.439 - 0.00000036 * d;
        double ra = Math.toDegrees(Math.atan2(cos(e) * sin(l), cos(l))) / 15.0;
        double eqt = q / 15.0 - fix(ra, 24);
        if (eqt > 12) eqt -= 24;
        if (eqt < -12) eqt += 24;
        double decl = Math.toDegrees(Math.asin(sin(e) * sin(l)));
        return new double[]{decl, eqt};
    }

    private static double sin(double deg) { return Math.sin(Math.toRadians(deg)); }
    private static double cos(double deg) { return Math.cos(Math.toRadians(deg)); }
    private static double acos(double x) { return Math.toDegrees(Math.acos(x)); }
    private static double fix(double a, double b) { a = a - b * Math.floor(a / b); return a < 0 ? a + b : a; }

    /** Days since 1970-01-01 for a proleptic Gregorian date (Howard Hinnant's algorithm). */
    static long daysFromCivil(int y, int m, int d) {
        y -= m <= 2 ? 1 : 0;
        long era = (y >= 0 ? y : y - 399) / 400;
        long yoe = y - era * 400;
        long doy = (153L * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
        long doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146097 + doe - 719468;
    }
}
