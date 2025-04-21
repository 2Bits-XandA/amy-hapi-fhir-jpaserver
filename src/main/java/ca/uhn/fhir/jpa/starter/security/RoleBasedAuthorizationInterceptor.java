package ca.uhn.fhir.jpa.starter.security;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.api.CacheControlDirective;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.server.interceptor.auth.AuthorizationInterceptor;
import ca.uhn.fhir.rest.server.interceptor.auth.IAuthRule;
import ca.uhn.fhir.rest.server.interceptor.auth.IAuthRuleTester;
import ca.uhn.fhir.rest.server.interceptor.auth.RuleBuilder;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class RoleBasedAuthorizationInterceptor extends AuthorizationInterceptor {
	private static final Logger logger = LoggerFactory.getLogger(RoleBasedAuthorizationInterceptor.class);
	private static final List<Class<? extends IBaseResource>> ALLOWED_RESOURCES = List.of(Observation.class, MedicationRequest.class);
	public static final String IDENTIFIER_USERNAME = "http://example.org/fhir/identifier/username";


	private final FhirContext myFhirContext;

	private final String internalAuthToken = java.util.UUID.randomUUID().toString();

	private final Cache<String, IIdType> practitionerCache = Caffeine.newBuilder()
		.expireAfterWrite(java.time.Duration.ofMinutes(15))
		.maximumSize(10_000)
		.build();


	public RoleBasedAuthorizationInterceptor(FhirContext fhirContext) {
		logger.warn("Init Role Based Auth {}", currentInternalToken());
		myFhirContext = fhirContext;
	}

	// TODO: internalToken should change over time - faster then brute force is possible
	// last used code should be accepted as well.
	public boolean isInternalToken(String token) {
		return internalAuthToken.equals(token);
	}

	public String currentInternalToken() {
		return internalAuthToken;
	}

	@Override
	public List<IAuthRule> buildRuleList(RequestDetails theRequestDetails) {
		logger.warn("Building rule list");
		RuleBuilder builder = new RuleBuilder();

		// 1) Token prüfen
		String jwt = extractToken(theRequestDetails);
		if (jwt == null) {
			logger.warn("Request denied - no token provided");
			builder.denyAll("Kein Token");
			return builder.build();
		}

		if (isInternalToken(jwt)) {
			logger.info("Request authorized using internal token");
			builder.allowAll(); // TODO: Reduce the Rights here!
			return builder.build();
		}
		logger.info("Using Token {}", jwt);

		String username = extractUsernameFromJwt(theRequestDetails);
		if (username == null || username.isBlank()) {
			logger.warn("Request denied - no username found in token");
			builder.denyAll("Kein Username");
			return builder.build();
		}

		IGenericClient client = buildGenericClient(theRequestDetails);

		// 2) PractitionerId aus Token holen
		IIdType practitionerId = ensurePractitioner(username, client);

		// Read/Write own Practitioner (public profile)
		builder.allow().read().instance(practitionerId).andThen()
				.allow().write().instance(practitionerId).andThen();

		// allow read of all public practitioners
		builder.allow().read().resourcesOfType(Practitioner.class).withAnyId().andThen();

		// Use Person for private Profile
		addPersonRules(builder, practitionerId);

		// Allow creation of Patients:
		builder.allow().create().resourcesOfType(Patient.class).withAnyId().andThen();

		// 4) Policies nach Rolle
		PatientLinkedLoader patientLinkedLoader = new PatientLinkedLoader(buildGenericClient(theRequestDetails));

		List<PatientLinkedLoader.PractitionerLink> linkedPatients = patientLinkedLoader.loadPatientIds(practitionerId);
			if (!linkedPatients.isEmpty()) {
				List<IIdType> patientIds = linkedPatients.stream().map(PatientLinkedLoader.PractitionerLink::patientId).toList();
				builder.allow().read().instances(patientIds).andThen();
				for (PatientLinkedLoader.PractitionerLink link : linkedPatients) {
					// alle fürfen lesen:
					IIdType patientId = link.patientId();
					builder.allow()
						.read()
						.allResources()
						.inCompartment("Patient", patientId)
						.andThen();

					switch (link.linkType()) {
						case owner:

							builder.allow().write().instance(patientId).andThen();
							builder.allow().delete().instance(patientId).andThen();

							// Owner darf alles im Compartment  schreiben
							builder.allow()
								.write()
								.allResources()
								.inCompartment("Patient", patientId)
								.andThen();
							builder.allow()
								.create()
								.allResources()
								.inCompartment("Patient", patientId)
								.andThen();
							// Owner darf alles im Compartment lesen und schreiben
							builder.allow()
								.delete()
								.allResources()
								.inCompartment("Patient", patientId)
								.andThen();

							break;


						case vet:

							// ...und bestimmte Resources schreiben
							ALLOWED_RESOURCES.forEach(aClass -> {
								builder
									.allow()
									.write()
									.resourcesOfType(aClass)
									.inCompartment("Patient", patientId)
									.andThen();
							});

							break;
					}
				}
			}


		// deny everything else:
		builder.denyAll("deny other ops");
		// 5) Liste ausgeben
		List<IAuthRule> finalRuleList = builder.build();
		finalRuleList.forEach(rule -> logger.info("Auth rule: {}", rule));

		return finalRuleList;
	}


	private void addPersonRules(RuleBuilder builder, IIdType practitionerId) {
		logger.info("Adding person rules for practitioner ID: {}", practitionerId);
		// 1) Person lesen (nur wenn link=Practitioner/{id})
		builder
			.allow()
			.read()
			.resourcesOfType(Person.class)
			.inCompartment("Practitioner", practitionerId)
			.andThen();

		// 2) Person anlegen (Conditional‑Create) – optional, je nach Use‑Case
		builder
			.allow()
			.createConditional()
			.resourcesOfType(Person.class)
			.withTester(new IAuthRuleTester() {
				@Override
				public boolean matches(RuleTestRequest theRequest) {
					if (theRequest.resource instanceof Person person) {
						return person.hasLink() && person.getLink().stream()
							.anyMatch(personLinkComponent -> isPractitionerTarget(personLinkComponent.getTarget(), practitionerId));
					}
					return false;
				}
			})
			.andThen();

		// 3) Person aktualisieren (Update), aber nur das eigene Profil
		builder
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
		logger.info("Creating or fetching practitioner ID for username: {}", username);

		// wie oben: build Practitioner mit Identifier = username
		Practitioner p = buildPractitionerForUser(username);

		MethodOutcome outcome = client
			.create()
			.resource(p)
			.conditional()
			.where(
				Practitioner.IDENTIFIER.exactly()
					.systemAndCode(IDENTIFIER_USERNAME, username)
			)
			.cacheControl(CacheControlDirective.noCache())
			.execute();

		return outcome.getId();
	}

	private Practitioner buildPractitionerForUser(String username) {
		return new Practitioner()
			.addIdentifier(new Identifier()
				.setSystem(IDENTIFIER_USERNAME)
				.setValue(username))
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