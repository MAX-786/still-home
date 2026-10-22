package dev.stillhome.tv;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/**
 * The day's namaz times for the city in Launcher settings, from the sun's position (the usual astronomical formulas,
 * as prayer-time calculators use them; no network). The list follows the owner's timetable, checked against the app
 * they use (Jabalpur, 23 October 2026: Zawal 11:55, Zuhr end 13:48, Asr end 15:42, Maghrib 17:38, Nisf-ul-lail
 * 23:55 to 00:56):
 *
 *  Morning    Sihori end (75 minutes before sunrise), Sunrise
 *  Afternoon  Zawal (the sun at its highest), then the time from Zawal to sunset in three equal parts: Zuhr ends at
 *             the first third, Asr at the second
 *  Evening    Maghrib (sunset), Nisf-ul-lail start (true midnight: halfway from sunset to the next sunrise, Zawal
 *             plus twelve hours), Nisf-ul-lail end (one hour of the night later: a twelfth of the night)
 */
final class Prayer {
    static final int MORNING = 0, AFTERNOON = 1, EVENING = 2;
    static final String[] GROUPS = {"Morning", "Afternoon", "Evening"};
    private static final long MINUTE = 60_000L;

    static final class Time {
        final String name;
        final long at;      // epoch ms
        final int group;

        Time(String name, long at, int group) {
            this.name = name;
            this.at = at;
            this.group = group;
        }
    }

    /** A day's times for the city, in order, or null without a city. */
    static List<Time> day(long now, double latitude, double longitude) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long midnight = cal.getTimeInMillis();
        double[] today = sun(midnight, latitude, longitude);
        cal.add(Calendar.DAY_OF_MONTH, 1);
        double[] tomorrow = sun(cal.getTimeInMillis(), latitude, longitude);
        if (today == null || tomorrow == null) return null;
        long sunrise = midnight + Math.round(today[0] * MINUTE), noon = midnight + Math.round(today[1] * MINUTE);
        long sunset = midnight + Math.round(today[2] * MINUTE);
        long nextSunrise = cal.getTimeInMillis() + Math.round(tomorrow[0] * MINUTE);
        long afternoon = sunset - noon, night = nextSunrise - sunset;
        long midnightTrue = sunset + night / 2;
        List<Time> out = new ArrayList<>();
        out.add(new Time("Sihori end", sunrise - 75 * MINUTE, MORNING));
        out.add(new Time("Sunrise", sunrise, MORNING));
        out.add(new Time("Zawal", noon, AFTERNOON));
        out.add(new Time("Zuhr end", noon + afternoon / 3, AFTERNOON));
        out.add(new Time("Asr end", noon + 2 * afternoon / 3, AFTERNOON));
        out.add(new Time("Maghrib", sunset, EVENING));
        out.add(new Time("Nisf-ul-lail start", midnightTrue, EVENING));
        out.add(new Time("Nisf-ul-lail end", midnightTrue + night / 12, EVENING));
        return out;
    }

    /** The first time still ahead today; past the last one, tomorrow's Sihori end. */
    static Time next(List<Time> day, long now) {
        for (Time t : day) if (t.at > now) return t;
        Time first = day.get(0);
        return new Time(first.name, first.at + 24 * 60 * MINUTE, first.group);
    }

    /**
     * Minutes after local midnight of {sunrise, solar noon, sunset} for the day starting at `midnight` (epoch ms),
     * or null in polar conditions. Equation of time and declination follow the NOAA solar formulas.
     */
    private static double[] sun(long midnight, double lat, double lon) {
        TimeZone zone = TimeZone.getDefault();
        double zoneHours = zone.getOffset(midnight + 12 * 3600_000L) / 3600_000.0;
        // Julian day at local noon, then the sun's position (iterated once: the transit time shifts it a little).
        double jd = julianDay(midnight + 12 * 3600_000L);
        double[] eqDecl = equation(jd);
        double transit = 12 + zoneHours - lon / 15 - eqDecl[0] / 60;   // hours, local
        eqDecl = equation(julianDay(midnight + Math.round(transit * 3600_000)));
        transit = 12 + zoneHours - lon / 15 - eqDecl[0] / 60;
        double decl = eqDecl[1];
        double rise = hourAngle(lat, decl, -0.833);
        if (Double.isNaN(rise)) return null;
        return new double[] {(transit - rise) * 60, transit * 60, (transit + rise) * 60};
    }

    /** Hours from solar noon at which the sun's center is at `altitude` degrees, or NaN. */
    private static double hourAngle(double lat, double decl, double altitude) {
        double phi = Math.toRadians(lat), d = Math.toRadians(decl), a = Math.toRadians(altitude);
        double cosH = (Math.sin(a) - Math.sin(phi) * Math.sin(d)) / (Math.cos(phi) * Math.cos(d));
        if (cosH < -1 || cosH > 1) return Double.NaN;
        return Math.toDegrees(Math.acos(cosH)) / 15;
    }

    private static double julianDay(long epochMs) {
        return epochMs / 86_400_000.0 + 2440587.5;
    }

    /** {equation of time (minutes), declination (degrees)} for a Julian day (NOAA). */
    private static double[] equation(double jd) {
        double t = (jd - 2451545.0) / 36525;
        double l0 = norm(280.46646 + t * (36000.76983 + t * 0.0003032));
        double m = norm(357.52911 + t * (35999.05029 - 0.0001537 * t));
        double e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t);
        double mr = Math.toRadians(m);
        double c = Math.sin(mr) * (1.914602 - t * (0.004817 + 0.000014 * t)) + Math.sin(2 * mr) * (0.019993 - 0.000101 * t) + Math.sin(3 * mr) * 0.000289;
        double trueLong = l0 + c;
        double omega = 125.04 - 1934.136 * t;
        double lambda = trueLong - 0.00569 - 0.00478 * Math.sin(Math.toRadians(omega));
        double obliquity = 23 + (26 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60) / 60;
        double eps = obliquity + 0.00256 * Math.cos(Math.toRadians(omega));
        double decl = Math.toDegrees(Math.asin(Math.sin(Math.toRadians(eps)) * Math.sin(Math.toRadians(lambda))));
        double y = Math.tan(Math.toRadians(eps / 2));
        y *= y;
        double l0r = Math.toRadians(l0);
        double eot = y * Math.sin(2 * l0r) - 2 * e * Math.sin(mr) + 4 * e * y * Math.sin(mr) * Math.cos(2 * l0r)
                - 0.5 * y * y * Math.sin(4 * l0r) - 1.25 * e * e * Math.sin(2 * mr);
        return new double[] {4 * Math.toDegrees(eot), decl};
    }

    private static double norm(double degrees) {
        double d = degrees % 360;
        return d < 0 ? d + 360 : d;
    }

    /** "in 42 min", "in 3 h 12 min", "now". */
    static String until(long at, long now) {
        long minutes = Math.max(0, (at - now + MINUTE / 2) / MINUTE);
        if (minutes == 0) return "now";
        if (minutes < 60) return "in " + minutes + " min";
        return "in " + minutes / 60 + " h " + (minutes % 60 == 0 ? "" : minutes % 60 + " min");
    }
}
