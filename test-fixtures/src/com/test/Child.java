package com.test;

/**
 * Child class to test: hierarchy (extends Base, implements Callable),
 * override graph (call overrides Callable.call), xrefs (process -> call, Helper.log),
 * fields (TAG), strings ("Child", "child").
 */
public class Child extends Base implements Callable {
    private static final String TAG = "Child";

    public Child() {
        super("child");
    }

    @Override
    public String call(int code) {
        counter++;
        return getName() + ":" + code;
    }

    public void process() {
        String result = call(42);
        Helper.log(TAG, result);
    }
}
