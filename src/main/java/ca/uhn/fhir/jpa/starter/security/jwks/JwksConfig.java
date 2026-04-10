package ca.uhn.fhir.jpa.starter.security.jwks;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.Objects;

@Configuration
public class JwksConfig {
	private final String issuerUrl;
	private final String audience;
	private final String jwksUrl;

	public JwksConfig(@Value("${jwt.jwksUrl:}") String issuerUrl, @Value("${jwt.jwksAudience:}") String audience) {
		this.issuerUrl = issuerUrl.isEmpty() ? System.getenv("JWT_JWKS_URL") : issuerUrl;
		if (this.issuerUrl == null || this.issuerUrl.isBlank()) {
			throw new IllegalArgumentException(
					"No valid JWKS-Issuer URL given. Please provide jwt.jwks.url via Properties or Environment JWT_JWKS_URL");
		}
		this.jwksUrl = this.issuerUrl + (this.issuerUrl.endsWith("/") ? "" : "/") + "protocol/openid-connect/certs";
		this.audience = audience.trim();
	}

	public String getIssuerUrl() {
		return issuerUrl;
	}

	public String getJwksUrl() {
		return jwksUrl;
	}

	public boolean hasAudience() {
		return !this.audience.isEmpty();
	}

	public String getAudience() {
		return audience;
	}

	@Override
	public String toString() {
		return "JwksConfig{" + "issuerUrl='"
				+ issuerUrl + '\'' + ", audience='"
				+ audience + '\'' + ", jwksUrl='"
				+ jwksUrl + '\'' + '}';
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		JwksConfig that = (JwksConfig) o;
		return Objects.equals(issuerUrl, that.issuerUrl)
				&& Objects.equals(audience, that.audience)
				&& Objects.equals(jwksUrl, that.jwksUrl);
	}

	@Override
	public int hashCode() {
		return Objects.hash(issuerUrl, audience, jwksUrl);
	}
}
