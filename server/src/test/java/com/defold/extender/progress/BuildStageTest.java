package com.defold.extender.progress;

import com.defold.extender.client.ExtenderBuildStage;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class BuildStageTest {

    // Clients compare stage names against ExtenderBuildStage, so a stage added to
    // or renamed in BuildStage without updating the client constants would go unnoticed.
    @Test
    public void testClientStageConstantsMatchBuildStage() throws IllegalAccessException {
        Set<String> clientStages = new TreeSet<>();
        for (Field field : ExtenderBuildStage.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                assertEquals(field.getName(), field.get(null));
                clientStages.add((String) field.get(null));
            }
        }
        Set<String> serverStages = Arrays.stream(BuildStage.values())
                .map(Enum::name)
                .collect(Collectors.toCollection(TreeSet::new));
        assertEquals(serverStages, clientStages);
    }
}
