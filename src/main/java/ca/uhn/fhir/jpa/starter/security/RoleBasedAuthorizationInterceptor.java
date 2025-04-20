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

		// addDefaultRulesToBuilder(builder);


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

		// 2) PractitionerId aus Token holen
		IIdType practitionerId = ensurePractitioner(username, theRequestDetails);

		addPersonRules(builder, practitionerId);

		builder.allow().create().resourcesOfType(PractitionerRole.class).inCompartment("Practitioner", practitionerId);
		builder.allow().read().resourcesOfType(PractitionerRole.class).inCompartment("Practitioner", practitionerId);
		builder.allow().create().resourcesOfType(Patient.class).withAnyId().andThen();

		// 3) PractitionerRole laden und Period prüfen
		List<PractitionerRole> roles = lookupPractitionerRoles(practitionerId, theRequestDetails);


		// 4) Policies nach Rolle
		for (PractitionerRole role : roles) {
			List<IIdType> patientIds = loadPatientIds(role, theRequestDetails);
			if (!patientIds.isEmpty()) {
				String code = role.getCodeFirstRep().getCodingFirstRep().getCode();
				builder.allow().read().instances(patientIds).andThen();
				for (IIdType patientId : patientIds) {
					// alle fürfen lesen:
					builder.allow()
						.read()
						.allResources()
						.inCompartment("Patient", patientId)
						.andThen();

					switch (code) {
						case "owner":

							builder.allow().write().instances(patientIds).andThen();
							builder.allow().delete().instances(patientIds).andThen();

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


						case "vet":

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
		}

		// deny everything else:
		builder.denyAll("deny other ops");
		// 5) Liste ausgeben
		return builder.build();
	}

	private List<IIdType> loadPatientIds(PractitionerRole role, RequestDetails theRequestDetails) {
		logger.info("Loading patient IDs for practitioner role: {}", role.getId());
		IGenericClient client = buildGenericClient(theRequestDetails);
		Bundle result = client
			.search()
			.forResource(Patient.class)
			.where(Patient.GENERAL_PRACTITIONER.hasId(role.getIdElement().getIdPart()))
			.elementsSubset(Patient.SP_RES_ID)
			.cacheControl(CacheControlDirective.noCache())
			.returnBundle(Bundle.class)
			.execute();

		List<IIdType> patientIds = new ArrayList<>();
		Bundle nextBundle = result;
		do {
			nextBundle.getEntry().stream()
				.map(entry -> entry.getResource().getIdElement())
				.forEach(patientIds::add);

			if (nextBundle.getLink(Bundle.LINK_NEXT) != null) {
				nextBundle = client.loadPage().next(nextBundle).execute();
			} else {
				nextBundle = null;
			}
		} while (nextBundle != null);

		logger.info("Using {} Patients for {} ", patientIds.size(), role.getId());
		return patientIds;
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

	private static void addDefaultRulesToBuilder(RuleBuilder builder) {
		// ERLAUBE intern: Practitioner‑Create & Practitioner/PractitionerRole‑Read
		builder
			.allow()
			.create()
			.resourcesOfType(Practitioner.class)
			.withCodeInValueSet("identifier", IDENTIFIER_USERNAME)
			.andThen()
			.allow()
			.read()
			.resourcesOfType(Practitioner.class).withCodeInValueSet("identifier", IDENTIFIER_USERNAME)
			.andThen();
	}

	public IIdType ensurePractitioner(String username, RequestDetails req) {
		return practitionerCache.get(username, u -> createOrFetchPractitionerId(u, req));
	}

	private String extractUsernameFromJwt(RequestDetails req) {
		String token = extractToken(req);
		if (token == null) {
			return null;
		}
		if (token.equals("DEBUG")) {
			return "DEBUG-USER";
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

	private IIdType createOrFetchPractitionerId(String username, RequestDetails theRequestDetails) {
		logger.info("Creating or fetching practitioner ID for username: {}", username);
		IGenericClient client = buildGenericClient(theRequestDetails);

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


	private List<PractitionerRole> lookupPractitionerRoles(IIdType practitionerId, RequestDetails theRequestDetails) {
		logger.info("Looking up practitioner roles for practitioner ID: {}", practitionerId.toUnqualifiedVersionless());
		IGenericClient client = buildGenericClient(theRequestDetails);
		Bundle result = client
			.search()
			.forResource(PractitionerRole.class)
			.where(PractitionerRole.PRACTITIONER.hasId(practitionerId.getIdPart()))
			.cacheControl(CacheControlDirective.noCache())
			.returnBundle(Bundle.class)
			.execute();

		List<PractitionerRole> roles = new ArrayList<>();
		Bundle nextBundle = result;
		do {
			nextBundle.getEntry().stream()
				.map(entry -> (PractitionerRole) entry.getResource())
				.filter(this::isWithinPeriod)
				.forEach(roles::add);
			if (nextBundle.getLink(Bundle.LINK_NEXT) != null) {
				nextBundle = client.loadPage().next(nextBundle).execute();
			} else {
				nextBundle = null;
			}
		} while (nextBundle != null);
		logger.info("Roles for Practitioner ID {} are {} ",
			practitionerId,
			roles.stream().map(Resource::getId).collect(Collectors.joining())
		);
		return roles;
	}

	private boolean isWithinPeriod(PractitionerRole role) {
		Period period = role.getPeriod();
		if (period == null) {
			logger.info("Role {} has no Period", role.getId());
			return true;
		}
		Instant now = Instant.now();
		boolean withinValidPeriod = (period.getStart() == null || now.isAfter(period.getStart().toInstant())) &&
			(period.getEnd() == null || now.isBefore(period.getEnd().toInstant()));
		logger.info("Period is over for {}", role.getId());
		return withinValidPeriod;
	}

	private String extractToken(RequestDetails request) {
		String auth = request.getHeader("Authorization");
		if (auth != null && auth.startsWith("Bearer ")) {
			return auth.substring(7);
		}
		return null;
	}
}

