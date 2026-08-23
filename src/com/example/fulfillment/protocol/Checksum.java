package com.example.fulfillment.protocol;

public final class Checksum {
    private Checksum() {
    }

    public static int calculate(String lineWithoutChecksum) {
        return lineWithoutChecksum.chars().sum() % 97;
    }

    public static boolean matches(String line) {
        int separator = line.lastIndexOf('|');
        if (separator < 0 || separator == line.length() - 1) {
            return false;
        }
        String supplied = line.substring(separator + 1);
        if (!supplied.matches("[0-9]{2}")) {
            return false;
        }
        return calculate(line.substring(0, separator)) == Integer.parseInt(supplied);
    }
}
