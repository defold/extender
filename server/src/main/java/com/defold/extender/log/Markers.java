package com.defold.extender.log;

import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

public class Markers {
    public static final Marker SERVER_ERROR = MarkerFactory.getMarker("SERVER_ERROR");
    public static final Marker BUILD_ERROR = MarkerFactory.getMarker("BUILD_ERROR");
    public static final Marker CLIENT_ERROR = MarkerFactory.getMarker("CLIENT_ERROR");
    public static final Marker INSTANCE_MANAGER_ERROR = MarkerFactory.getMarker("INSTANCE_MANAGER_ERROR");
    public static final Marker CACHE_ERROR = MarkerFactory.getMarker("CACHE_ERROR");
}
