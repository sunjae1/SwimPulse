package com.swimpulse.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.swimpulse.user.AppUser;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
	private final JwtEncoder jwtEncoder;
	private final JwtDecoder jwtDecoder;
	private final Duration expiration;

	public JwtService(
			@Value("${swimpulse.auth.jwt-secret}") String jwtSecret,
			@Value("${swimpulse.auth.jwt-expiration-hours:168}") long expirationHours
	) {
		byte[] secret = jwtSecret.getBytes(StandardCharsets.UTF_8);
		this.jwtEncoder = new NimbusJwtEncoder(new ImmutableSecret<>(secret));
		this.jwtDecoder = NimbusJwtDecoder.withSecretKey(secretKey(secret))
				.macAlgorithm(MacAlgorithm.HS256)
				.build();
		this.expiration = Duration.ofHours(expirationHours);
	}

	public String createToken(AppUser user) {
		Instant now = Instant.now();
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.subject(user.getId().toString())
				.issuedAt(now)
				.expiresAt(now.plus(expiration))
				.claim("email", user.getEmail())
				.claim("display_name", user.getDisplayName())
				.build();
		return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
	}

	public Optional<AuthenticatedUser> parse(String token) {
		try {
			Jwt jwt = jwtDecoder.decode(token);
			return Optional.of(new AuthenticatedUser(
					Long.valueOf(jwt.getSubject()),
					jwt.getClaimAsString("email"),
					jwt.getClaimAsString("display_name")
			));
		} catch (RuntimeException exception) {
			return Optional.empty();
		}
	}

	private SecretKey secretKey(byte[] secret) {
		return new SecretKeySpec(secret, "HmacSHA256");
	}
}
