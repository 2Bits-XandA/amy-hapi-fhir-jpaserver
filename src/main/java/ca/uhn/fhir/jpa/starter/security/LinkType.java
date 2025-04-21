package ca.uhn.fhir.jpa.starter.security;

import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.StringType;

import java.util.stream.Stream;

public enum LinkType {
	owner,
	vet,
	other;

	private static final String EXTENSION_URL = "http://amyvet.org/fhir/StructureDefinition/link-type";

	public Extension toExtension() {
		return new Extension(EXTENSION_URL, new StringType(this.name()));
	}

	public static LinkType fromExtension(Extension extension) {
		if (isValidExtension(extension)) {
			String value = ((StringType) extension.getValue()).getValue();
			return LinkType.valueOf(value);
		}
		return null;
	}

	public static boolean isValidExtension(Extension extension) {
		return extension != null &&
			EXTENSION_URL.equals(extension.getUrl()) &&
			extension.getValue() instanceof StringType &&
			Stream.of(LinkType.values()).map(Enum::name).anyMatch(n -> n.equals(((StringType) extension.getValue()).getValue()));
	}
}
