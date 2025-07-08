package ca.uhn.fhir.jpa.starter.security.jwks;

import com.fasterxml.jackson.databind.JsonNode;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class JwtPayload  {
	private final String email;
	private final String familyName;
	private final String givenName;
	private final String issuer;
	private final String subject;
	private final String id;
	private final String name;
	private final String preferred_username;

	private final HashSet<String> roles;

	public JwtPayload(String email, String familyName, String givenName, String issuer, String subject, String id, String name, String preferred_username, HashSet<String> roles) {
		this.email = email;
		this.familyName = familyName;
		this.givenName = givenName;
		this.issuer = issuer;
		this.subject = subject;
		this.id = id;
		this.name = name;
		this.preferred_username = preferred_username;
		this.roles = roles;
	}

    @NotNull
    public static JwtPayload generateFromJsonNode(JsonNode node) {
        String issuer = "";
        String subject = "";
        String id = "";
        String name = "";
        String preferred_username = "";
        String givenName = "";
        String familyName = "";
        String email = "";
        if (node.has("iss")) {
            issuer = node.get("iss").asText();
        }
        if (node.has("sub")) {
            subject = node.get("sub").asText();
        }
        if (node.has("sid")) {
            id = node.get("sid").asText();
        }

        if (node.has("name")) {
            name = node.get("name").asText();
        }
        if (node.has("preferred_username")) {
            preferred_username = node.get("preferred_username").asText();
        }
        if (node.has("given_name")) {
            givenName = node.get("given_name").asText();
        }
        if (node.has("family_name")) {
            familyName = node.get("family_name").asText();
        }
        if (node.has("email")) {
            email = node.get("email").asText();
        }
        final HashSet<String> roles = new HashSet<>();

        if (node.has("realm_access")
            && node.get("realm_access").has("roles")
            && node.get("realm_access").get("roles").isArray()) {
            node.get("realm_access").get("roles").forEach(roleNode -> {
                String e = roleNode.asText();
                if (e != null) {
                    roles.add(e.toUpperCase(Locale.ROOT));
                }
            });
        }

        if (node.has("resource_access")) {
            node.get("resource_access").forEach(resourceNode -> {
                if (resourceNode.has("roles") && resourceNode.get("roles").isArray()) {
                    resourceNode.get("roles").forEach(roleNode -> {
                        String e = roleNode.asText();
                        if (e != null) {
                            roles.add(e.toUpperCase(Locale.ROOT));
                        }
                    });
                }
            });
        }

        return new JwtPayload(
            email,
            familyName,
            givenName,
            issuer,
            subject,
            id,
            name,
            preferred_username,
            roles
        );
    }

    public String getIssuer() {
		return issuer;
	}

	public String getSubject() {
		return subject;
	}

	public String getId() {
		return id;
	}

	public String getEmail() {
		return email;
	}

	public String getFamilyName() {
		return familyName;
	}

	public String getGivenName() {
		return givenName;
	}

	public String getName() {
		return name;
	}

	public String getPreferred_username() {
		return preferred_username;
	}

	public Set<String> getRoles() {
		return roles;
	}

	public boolean hasRole(String role) {
		if (role == null || role.isBlank()) return false;
		return roles.contains(role);
	}

	@Override
	public String toString() {
		return ("'" + name + "' <" + email + "> " + (" { " + preferred_username).trim() + " " + roles + " }").trim();
	}
}
