package com.survivalcinema.director.film;

import java.util.Locale;

/**
 * A size on disk the way Windows Explorer shows it (1 KB = 1024 bytes): "830 KB", "12 MB", "1.4 GB".
 * One decimal below 10, whole numbers above.
 */
public record DiskSize(String number, Unit unit) {
	public enum Unit {B, KB, MB, GB, TB}

	public static DiskSize of(long bytes) {
		return of(bytes, '.');
	}

	/** @param decimalSeparator ',' in Russian */
	public static DiskSize of(long bytes, char decimalSeparator) {
		double value = Math.max(0, bytes);
		Unit unit = Unit.B;
		while (value >= 1024 && unit != Unit.TB) {
			value /= 1024;
			unit = Unit.values()[unit.ordinal() + 1];
		}
		String number;
		if (unit == Unit.B || value >= 9.95) {
			number = String.valueOf(Math.round(value));
		} else {
			number = String.format(Locale.ROOT, "%.1f", value).replace('.', decimalSeparator);
		}
		return new DiskSize(number, unit);
	}
}
