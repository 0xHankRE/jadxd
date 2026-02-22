package com.test;

/**
 * Utility class to test: throws declaration, static methods,
 * xrefs (called by Child.process), strings ("ERROR").
 */
public class Helper {
    public static void log(String tag, String message) {
        System.out.println(tag + ": " + message);
    }

    public static int compute(int a, int b) throws ArithmeticException {
        if (b == 0) {
            throw new ArithmeticException("ERROR: division by zero");
        }
        return a / b;
    }
}
