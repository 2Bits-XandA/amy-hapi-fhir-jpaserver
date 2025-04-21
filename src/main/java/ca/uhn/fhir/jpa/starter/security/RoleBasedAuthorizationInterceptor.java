package ca.uhn.fhir.jpa.starter.security;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.api.CacheControlDirective;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.server.interceptor.auth.*;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class RoleBasedAuthorizationInterceptor extends AuthorizationInterceptor {
	private static final Logger logger = LoggerFactory.getLogger(RoleBasedAuthorizationInterceptor.class);
	private static final List<Class<? extends IBaseResource>> ALLOWED_RESOURCES = List.of(
		Observation.class, MedicationRequest.class, MedicationAdministration.class,
		DocumentReference.class, Immunization.class, Condition.class, Encounter.class
	);
	public static final String IDENTIFIER_USERNAME = "http://amyvet.org/fhir/identifier/username";
	private static final MessageDigest messageDigest = getMessageDigest();

	private static MessageDigest getMessageDigest()  {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			logger.error("SHA-256 must exist! Aborting");
			System.exit(2);
			throw new RuntimeException("SHA-256 must exist");
		}
	}


	private final FhirContext myFhirContext;

	private final RollingAccessToken internalAuthToken = new RollingAccessToken();

	private final Cache<String, IIdType> practitionerCache = Caffeine.newBuilder()
		.expireAfterWrite(java.time.Duration.ofMinutes(15))
		.maximumSize(10_000)
		.build();


	public RoleBasedAuthorizationInterceptor(FhirContext fhirContext) {
		logger.warn("Init Role Based Auth {}", currentInternalToken());
		myFhirContext = fhirContext;
	}

	public boolean isInternalToken(String token) {
		return internalAuthToken.isInternalToken(token);
	}

	public String currentInternalToken() {
		return internalAuthToken.currentInternalToken();
	}

	@Override
	public List<IAuthRule> buildRuleList(RequestDetails theRequestDetails) {
		final RuleBuilder builder = new RuleBuilder();
		final RuleBuilder secondWriteBuilder = new RuleBuilder();

		// 1) Token prüfen
		String jwt = extractToken(theRequestDetails);
		if (jwt == null) {
			logger.warn("Request denied - no token provided");
			builder.denyAll("Kein Token");
			return builder.build();
		}

		if (isInternalToken(jwt)) {
			logger.debug("Request authorized using internal token");
			builder.allowAll(); // TODO: Reduce the Rights here!
			return builder.build();
		}
		logger.trace("Using Token {}", jwt);

		String username = extractUsernameFromJwt(theRequestDetails);
		if (username == null || username.isBlank()) {
			logger.warn("Request denied - no username found in token");
			builder.denyAll("Kein Username");
			return builder.build();
		}

		IGenericClient client = buildGenericClient(theRequestDetails);

		// 2) PractitionerId aus Token holen
		IIdType practitionerId = ensurePractitioner(username, client).toUnqualifiedVersionless();
		// Store in Request
		theRequestDetails.getUserData().put("practitionerId", practitionerId);

		logger.debug("Building rule list for {}", practitionerId.getIdPart());

		// Allow creation of Patients:
		// Allow create must be first!
		builder.allow().create().resourcesOfType(Patient.class).withAnyId().andThen();

		// allow read of all public practitioners
		builder.allow().read().resourcesOfType(Practitioner.class).withAnyId().andThen();

		// Use Person for private Profile
		addPersonRules(builder, secondWriteBuilder, practitionerId);

		ArrayList<IIdType> allowRead = new ArrayList<>();
		ArrayList<IIdType> allowWrite = new ArrayList<>();

		// 4) Policies nach Rolle
		PatientLinkedLoader patientLinkedLoader = new PatientLinkedLoader(buildGenericClient(theRequestDetails));

		List<PatientLinkedLoader.PractitionerLink> linkedPatients = patientLinkedLoader.loadPatientIds(practitionerId);
		if (!linkedPatients.isEmpty()) {
			List<IIdType> patientIds = linkedPatients.stream().map(PatientLinkedLoader.PractitionerLink::patientId).toList();

			for (PatientLinkedLoader.PractitionerLink link : linkedPatients) {
				logger.trace("Allow Read to {} for {} as {}", link.patientId().getIdPart(), practitionerId.getIdPart(), link.linkType());
				// alle fürfen lesen:
				IIdType patientId = link.patientId();
				allowRead.add(patientId.toUnqualifiedVersionless());
				builder.allow()
					.read()
					.allResources()
					.inCompartment("Patient", patientId)
					.andThen();

				switch (link.linkType()) {
					case owner:

						logger.debug("Allow Write to Patient {}", patientId);
						allowWrite.add(patientId.toUnqualifiedVersionless());
						builder.allow().delete().instance(patientId).andThen();

						// Owner darf alles im Compartment  schreiben
						secondWriteBuilder
							.allow()
							.write()
							.allResources()
							.inCompartment("Patient", patientId)
							.andThen();
						builder
							.allow()
							.create()
							.allResources()
							.inCompartment("Patient", patientId)
							.andThen()
							.allow()
							.delete()
							.allResources()
							.inCompartment("Patient", patientId)
							.andThen();

						break;


					case vet:

						// ...und bestimmte Resources schreiben
						ALLOWED_RESOURCES.forEach(aClass -> {
							secondWriteBuilder
								.allow()
								.write()
								.resourcesOfType(aClass)
								.inCompartment("Patient", patientId)
								.andThen();
						});

						break;
				}
			}
		} else {
			logger.warn("There are no Linked Patients for {}", practitionerId.getIdPart());
		}

		// Read/Write own Practitioner (public profile)
		allowRead.add(practitionerId.toUnqualifiedVersionless());
		allowWrite.add(practitionerId.toUnqualifiedVersionless());

		// Allowing Read and write
		builder.allow().read().instances(allowRead).andThen();

		List<IAuthRule> firstList = builder.build();

		// HAPI BUG: Allow.Write and allow.create will use the same list - we need both
		// Workaround: create a second Builder and add Write - then combine both
		logger.info("Allow write for instances: {}", allowWrite);
		secondWriteBuilder.allow().write().instances(allowWrite).andThen();
		// deny everything else:
		secondWriteBuilder.denyAll("deny other ops");
		// 5) Liste ausgeben
		List<IAuthRule> secondList = secondWriteBuilder.build();

		List<IAuthRule> finalRuleList = Stream.concat(firstList.stream(), secondList.stream()).toList();
		finalRuleList.forEach(rule -> logger.info("Auth rule: {}", rule));

		logger.error("FINISHED WITH {} RULES", finalRuleList.size());
		return finalRuleList;
	}


	private void addPersonRules(RuleBuilder createBuilder,RuleBuilder writeBuilder, IIdType practitionerId) {
		logger.debug("Adding person rules for practitioner ID: {}", practitionerId);
		// 1) Person lesen (nur wenn link=Practitioner/{id})
		createBuilder
			.allow()
			.read()
			.resourcesOfType(Person.class)
			.inCompartment("Practitioner", practitionerId)
			.andThen();


		// 3) Person aktualisieren (Update), aber nur das eigene Profil
		writeBuilder
			.allow()
			.write()
			.resourcesOfType(Person.class)
			.inCompartment("Practitioner", practitionerId)
			.andThen();
	}

	private boolean isPractitionerTarget(Reference target, IIdType id) {
		if (target == null || id == null || !target.hasReference()) {
			return false;
		}
		String expectedReference = id.getResourceType() + "/" + id.getIdPart();
		return expectedReference.equals(target.getReference());
	}

	public IIdType ensurePractitioner(String username, IGenericClient client) {
		return practitionerCache.get(username, u -> createOrFetchPractitionerId(u, client));
	}

	private String extractUsernameFromJwt(RequestDetails req) {
		String token = extractToken(req);
		if (token == null) {
			return null;
		}
		if (token.startsWith("DEBUG")) {
			return "DEBUG-USER" + token.substring(5);
		}
		String[] parts = token.split("\\.");
		if (parts.length != 3) {
			return null;
		}
		try {
			String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]));
			com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload);
			return node.get("preferred_username").asText();
		} catch (Exception e) {
			logger.error("Failed to extract username from JWT", e);
			return null;
		}
	}

	private IIdType createOrFetchPractitionerId(String username, IGenericClient client) {
		String nameHash = userNameHash(username);
		logger.info("Creating or fetching practitioner ID for username: {} with hash {}", username, nameHash);

		// wie oben: build Practitioner mit Identifier = username
		Practitioner p = buildPractitionerForUser(username);

		MethodOutcome outcome = client
			.create()
			.resource(p)
			.conditional()
			.where(
				Practitioner.IDENTIFIER.exactly()
					.systemAndCode(IDENTIFIER_USERNAME, nameHash)
			)
			.cacheControl(CacheControlDirective.noCache())
			.execute();

		return outcome.getId();
	}

	private static String userNameHash(String username) {
		return java.util.Base64.getEncoder().encodeToString(messageDigest.digest(username.toLowerCase().getBytes()));
	}

	private Practitioner buildPractitionerForUser(String username) {
		return new Practitioner()
			.addIdentifier(new Identifier()
				.setSystem(IDENTIFIER_USERNAME)
				.setValue(userNameHash(username)))
			.setActive(true);
	}

	private IGenericClient buildGenericClient(RequestDetails theRequestDetails) {
		String serverBase = theRequestDetails.getFhirServerBase();
		IGenericClient client = this.myFhirContext.newRestfulGenericClient(serverBase);
		String token = currentInternalToken();
		client.registerInterceptor(new ca.uhn.fhir.rest.client.interceptor.BearerTokenAuthInterceptor(token));
		return client;
	}

	private String extractToken(RequestDetails request) {
		String auth = request.getHeader("Authorization");
		if (auth != null && auth.startsWith("Bearer ")) {
			return auth.substring(7);
		}
		return null;
	}
}