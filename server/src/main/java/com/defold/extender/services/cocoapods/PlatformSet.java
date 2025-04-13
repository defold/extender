package com.defold.extender.services.cocoapods;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class PlatformSet {
    public Set<String> ios = new LinkedHashSet<>();
    public Set<String> osx = new LinkedHashSet<>();

    public void addAll(PlatformSet v) {
        ios.addAll(v.ios);
        osx.addAll(v.osx);
    }

    public void addAll(List<String> values) {
        for (String v :  values) {
            ios.add(v);
            osx.add(v);
        }
    }

    public void add(String value) {
        ios.add(value);
        osx.add(value);
    }

    public Set<String> get(String platform) {
        if (platform.contains("ios")) {
            return ios;
        }
        else if (platform.contains("osx")) {
            return osx;
        }
        return new LinkedHashSet<String>();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("ios: " + ios.toString());
        sb.append(" osx: " + osx.toString());
        return sb.toString();
    }
}
