package com.defold;

import com.defold.localaar.LocalAar;
import com.defold.localaar.InnerJar;

class Test {
    static String doStuff() {
        return LocalAar.DoStuff() + InnerJar.DoStuff();
    }
}
