package com.defold.extender.services.cocoapods;

import java.util.List;

public class PlatformAndLanguageSet {
    public LanguageSet ios = new LanguageSet();
    public LanguageSet osx = new LanguageSet();

    public void addAll(PlatformAndLanguageSet v) {
        ios.addAll(v.ios);
        osx.addAll(v.osx);
    }
    public void addAll(List<String> values) {
        for (String v :  values) {
            ios.add(v);
            osx.add(v);
        }
    }

    public void remove(String value) {
        ios.remove(value);
        osx.remove(value);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("ios: " + ios.toString());
        sb.append(" osx: " + osx.toString());
        return sb.toString();
    }
}
