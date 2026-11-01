package com.example.smartgallery;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

public final class Fmt {
    private Fmt() {}

    public static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.getDefault(), "%.0f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.getDefault(), "%.1f MB", mb);
        return String.format(Locale.getDefault(), "%.2f GB", mb / 1024.0);
    }

    public static String date(long ms) {
        return DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(ms));
    }
}
