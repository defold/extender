package com.defold.extender;

import com.defold.extender.Configuration;
import com.defold.extender.ManifestConfiguration;
import com.defold.extender.AppManifestConfiguration;

import org.yaml.snakeyaml.constructor.Constructor;

class ExtenderYamlSafeConstructor extends Constructor {

	public ExtenderYamlSafeConstructor() {
		this.yamlConstructors.put("com.defold.extender.Configuration", new ConstructYamlObject());
		this.yamlConstructors.put("com.defold.extender.ManifestConfiguration", new ConstructYamlObject());
		this.yamlConstructors.put("com.defold.extender.Configuration", new ConstructYamlObject());
		this.yamlConstructors.put(null, undefinedConstructor);
	}
}
