package com.defold;

import com.defold.localaar.InnerJar;
import com.defold.localaar.LocalAar;

class GradleHandoffTest {
    static String doStuff() {
        return LocalAar.DoStuff() + InnerJar.DoStuff() + JarDep.DoStuff();
    }
}
