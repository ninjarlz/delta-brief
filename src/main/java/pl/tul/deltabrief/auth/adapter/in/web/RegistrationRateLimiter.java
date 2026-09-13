package pl.tul.deltabrief.auth.adapter.in.web;

import io.github.bucket4j.Bucket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * In-memory, per-email and per-IP token buckets guarding {@code /register}
 * and {@code /resend-verification} against abuse — no rate limiting existed
 * anywhere in this codebase before this (confirmed exhaustively; see
 * {@code context/changes/testing-auth-boundary-abuse-resistance/research.md}).
 * Single Render instance, so no distributed backend (Redis/Hazelcast) is
 * needed. Email is the tighter bound (directly targets spamming one victim's
 * inbox); IP is looser to avoid over-blocking shared/corporate networks
 * while still capping a single-source flood.
 */
@Component
class RegistrationRateLimiter {

	private static final int EMAIL_LIMIT = 5;
	private static final int IP_LIMIT = 20;
	private static final Duration WINDOW = Duration.ofMinutes(15);

	private final Map<String, Bucket> emailBuckets = new ConcurrentHashMap<>();
	private final Map<String, Bucket> ipBuckets = new ConcurrentHashMap<>();

	boolean tryConsume(String email, String clientIp) {
		return resolveBucket(emailBuckets, email, EMAIL_LIMIT).tryConsume(1)
				&& resolveBucket(ipBuckets, clientIp, IP_LIMIT).tryConsume(1);
	}

	private Bucket resolveBucket(Map<String, Bucket> buckets, String key, int limit) {
		return buckets.computeIfAbsent(key, k -> newBucket(limit));
	}

	private Bucket newBucket(int limit) {
		return Bucket.builder()
				.addLimit(bandwidth -> bandwidth.capacity(limit).refillGreedy(limit, WINDOW))
				.build();
	}

}
