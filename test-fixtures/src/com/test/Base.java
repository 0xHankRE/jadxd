package com.test;

import java.lang.Deprecated;

/**
 * Base class to test: annotations (@Deprecated), fields, hierarchy (super_class).
 */
@Deprecated
public class Base {
    protected int counter;
    protected String name;

    public Base(String name) {
        this.name = name;
        this.counter = 0;
    }

    public String getName() {
        return name;
    }

    public int getCounter() {
        return counter;
    }
}
