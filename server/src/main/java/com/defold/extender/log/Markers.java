package com.defold.extender.log;

import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

public class Markers {
    public static final Marker SERVER_ERROR = MarkerFactory.getMarker("SERVER_ERROR");
    public static final Marker COMPILATION_ERROR = MarkerFactory.getMarker("COMPILATION_ERROR");
    public static final Marker INSTANCE_MANAGER_ERROR = MarkerFactory.getMarker("INSTANCE_MANAGER_ERROR");
    public static final Marker CACHE_ERROR = MarkerFactory.getMarker("CACHE_ERROR");
}
