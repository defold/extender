package com.defold.extender;

import org.yaml.snakeyaml.constructor.Construct;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.nodes.Tag;

class ExtenderYamlSafeConstructor extends Constructor {

	public ExtenderYamlSafeConstructor() {
		super();
		Construct c = this.yamlConstructors.get(null); // ConstructYamlObject

		this.yamlConstructors.put(new Tag(Tag.PREFIX + "com.defold.extender.Configuration"), c);
		this.yamlConstructors.put(new Tag(Tag.PREFIX + "com.defold.extender.ManifestConfiguration"), c);
		this.yamlConstructors.put(new Tag(Tag.PREFIX + "com.defold.extender.AppManifestConfiguration"), c);
		this.yamlConstructors.put(new Tag(Tag.PREFIX + "com.defold.extender.PlatformConfig"), c);
		this.yamlConstructors.put(new Tag(Tag.PREFIX + "com.defold.extender.AppManifestPlatformConfig"), c);
		this.yamlConstructors.put(new Tag(Tag.PREFIX + "com.defold.extender.ManifestPlatformConfig"), c);
		this.yamlConstructors.put(new Tag(Tag.PREFIX + "com.defold.extender.WhitelistConfig"), c);

		this.yamlConstructors.put(null, undefinedConstructor);
	}
}
