package com.defold.extender.services.cocoapods;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class LanguageSet {
    public Set<String> c = new LinkedHashSet<>();
    public Set<String> cpp = new LinkedHashSet<>();
    public Set<String> objc = new LinkedHashSet<>();
    public Set<String> objcpp = new LinkedHashSet<>();
    public List<String> swift = new ArrayList<>();

    public void add(String value) {
        c.add(value);
        cpp.add(value);
        objc.add(value);
        objcpp.add(value);
    }

    public void addAll(List<String> values) {
        for (String v : values) {
            add(v);
        }
    }

    public void addAll(LanguageSet set) {
        c.addAll(set.c);
        cpp.addAll(set.cpp);
        objc.addAll(set.objc);
        objcpp.addAll(set.objcpp);
        swift.addAll(set.swift);
    }

    public void remove(String value) {
        c.remove(value);
        cpp.remove(value);
        objc.remove(value);
        objcpp.remove(value);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("c: " + c);
        sb.append(" cpp: " + cpp);
        sb.append(" objc: " + objc);
        sb.append(" objcpp: " + objcpp);
        return sb.toString();
    }
}
