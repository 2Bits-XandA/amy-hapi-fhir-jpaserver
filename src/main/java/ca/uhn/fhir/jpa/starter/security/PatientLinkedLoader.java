package ca.uhn.fhir.jpa.starter.security;

import ca.uhn.fhir.rest.api.CacheControlDirective;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class PatientLinkedLoader {
	private final IGenericClient client;
	private static final Logger logger = LoggerFactory.getLogger(PatientLinkedLoader.class);

	public PatientLinkedLoader(IGenericClient client) {
		this.client = client;
	}

	public static boolean isReferenceForId(Reference ref, IIdType id) {
		boolean isMatch =  ref.getReferenceElement().getResourceType().equals(id.getResourceType()) &&
			ref.getReferenceElement().getIdPart()       .equals(id.getIdPart());
		PatientLinkedLoader.logger.info("Reference {} and ID {} are {}", ref.getReference(),id.getValue(), isMatch);
		return isMatch;
	}

	public List<PractitionerLink> loadPatientIds(IIdType practId) {
		logger.info("Loading patient IDs for practitioner role: {}",practId);
		Bundle result = client
			.search()
			.forResource(Patient.class)
			.where(Patient.GENERAL_PRACTITIONER.hasId(practId.getIdPart()))
			.elementsSubset("generalPractitioner", "_id")
			.cacheControl(CacheControlDirective.noCache())
			.returnBundle(Bundle.class)
			.execute();

		List<PractitionerLink> patientIds = new ArrayList<>();
		Bundle nextBundle = result;
		do {
			nextBundle.getEntry().stream()
				.map(Bundle.BundleEntryComponent::getResource)
				.filter(entry -> entry != null && entry.getResourceType().equals(ResourceType.Patient))
				.map(entry -> new PractitionerLink(
					(entry).getIdElement(),
					filterLinkType((Patient)entry, practId)
				))
				.filter(link -> link.linkType() != null)
				.forEach(patientIds::add);

			if (nextBundle.getLink(Bundle.LINK_NEXT) != null) {
				nextBundle = client.loadPage().next(nextBundle).execute();
			} else {
				nextBundle = null;
			}
		} while (nextBundle != null);

		logger.info("Using {} Patients for {} ", patientIds.size(), practId.getIdPart());
		return patientIds;
	}

	private LinkType filterLinkType(final Patient entry, final IIdType practId) {
		Optional<Reference> practitionerReference = findValidPractitionerReference(entry, practId);
		if (practitionerReference.isEmpty()) {
			return null;
		}

		LinkType linkType = extractLinkType(practitionerReference.get());
		if (linkType == null) {
			return null;
		}

		if (hasPeriodExpired(practitionerReference.get())) {
			return null;
		}

		return linkType;
	}

	private Optional<Reference> findValidPractitionerReference(Patient entry, IIdType practId) {
		return entry.getGeneralPractitioner().stream()
			.filter(reference -> isReferenceForId(reference, practId))
			.filter(reference -> reference.getExtension().stream().anyMatch(LinkType::isValidExtension))
			.findFirst();
	}

	private LinkType extractLinkType(Reference reference) {
		return reference.getExtension().stream()
			.filter(LinkType::isValidExtension)
			.findFirst()
			.map(LinkType::fromExtension)
			.orElse(null);
	}

	private boolean hasPeriodExpired(Reference reference) {
		Optional<Extension> periodExtension = reference.getExtension().stream()
			.filter(PatientLinkedLoader::isPeriodExtension)
			.findFirst();

		return periodExtension.isPresent() && !isWithinPeriod(periodExtension.get());
	}

	private static final String PERIOD_EXTENSION_URL = "http://amyvet.org/fhir/StructureDefinition/period-extension";

	public static boolean isPeriodExtension(Extension extension) {
		return extension != null &&
			PERIOD_EXTENSION_URL.equals(extension.getUrl()) &&
			extension.getValue() instanceof Period;
	}

	private boolean isWithinPeriod(Extension role) {
		Type value = role.getValue();
		if (value instanceof Period period) {
			Instant now = Instant.now();
			boolean withinValidPeriod = (period.getStart() == null || now.isAfter(period.getStart().toInstant())) &&
				(period.getEnd() == null || now.isBefore(period.getEnd().toInstant()));
			logger.info("Period is over for {}", role.getId());
			return withinValidPeriod;
		}
		return false;
	}

	public record PractitionerLink(IIdType patientId, LinkType linkType) {
	}
}
