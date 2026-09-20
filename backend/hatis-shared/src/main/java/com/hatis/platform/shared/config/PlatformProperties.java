package com.hatis.platform.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Typed platform configuration.
 *
 * <p>Setter-bound (rather than record-bound) so that every property has a safe
 * default: a missing key in a customer's environment must never start the
 * platform in a broken or insecure state.
 *
 * <p>Secrets are deliberately absent from this class. Credentials come from the
 * {@code SecretStore} port, not from Spring configuration.
 */
@ConfigurationProperties(prefix = "hatis")
public class PlatformProperties {

    /** {@code api} or {@code worker}. One image, two roles. */
    private String role = "worker";

    private final Events events = new Events();
    private final Security security = new Security();
    private final RateLimit rateLimit = new RateLimit();
    private final Uploads uploads = new Uploads();
    private final Storage storage = new Storage();
    private final Secrets secrets = new Secrets();

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Events events() {
        return events;
    }

    public Security security() {
        return security;
    }

    public RateLimit rateLimit() {
        return rateLimit;
    }

    public Uploads uploads() {
        return uploads;
    }

    public Storage storage() {
        return storage;
    }

    public Secrets secrets() {
        return secrets;
    }

    public static class Events {
        /** {@code outbox} (in-process sink) or {@code kafka}. */
        private String transport = "outbox";
        private String topic = "hatis.platform.events";
        private int relayBatchSize = 200;
        private Duration relayInterval = Duration.ofSeconds(2);

        public String getTransport() {
            return transport;
        }

        public void setTransport(String transport) {
            this.transport = transport;
        }

        public String getTopic() {
            return topic;
        }

        public void setTopic(String topic) {
            this.topic = topic;
        }

        public int relayBatchSize() {
            return relayBatchSize <= 0 ? 200 : relayBatchSize;
        }

        public void setRelayBatchSize(int relayBatchSize) {
            this.relayBatchSize = relayBatchSize;
        }

        public Duration getRelayInterval() {
            return relayInterval;
        }

        public void setRelayInterval(Duration relayInterval) {
            this.relayInterval = relayInterval;
        }
    }

    public static class Security {
        private String tokenIssuer = "https://api.hatis.example";
        private Duration accessTokenTtl = Duration.ofMinutes(15);
        private Duration refreshTokenTtl = Duration.ofDays(30);
        private String keyId = "default";
        /** When true, MFA is mandatory for any principal holding a security-sensitive role. */
        private boolean enforceMfaForAdmins = true;
        private int maxFailedSignInAttempts = 5;
        private Duration lockoutDuration = Duration.ofMinutes(15);

        public String getTokenIssuer() {
            return tokenIssuer;
        }

        public void setTokenIssuer(String tokenIssuer) {
            this.tokenIssuer = tokenIssuer;
        }

        public Duration accessTokenTtl() {
            return accessTokenTtl;
        }

        public void setAccessTokenTtl(Duration accessTokenTtl) {
            this.accessTokenTtl = accessTokenTtl;
        }

        public Duration refreshTokenTtl() {
            return refreshTokenTtl;
        }

        public void setRefreshTokenTtl(Duration refreshTokenTtl) {
            this.refreshTokenTtl = refreshTokenTtl;
        }

        public String getKeyId() {
            return keyId;
        }

        public void setKeyId(String keyId) {
            this.keyId = keyId;
        }

        public boolean isEnforceMfaForAdmins() {
            return enforceMfaForAdmins;
        }

        public void setEnforceMfaForAdmins(boolean enforceMfaForAdmins) {
            this.enforceMfaForAdmins = enforceMfaForAdmins;
        }

        public int maxFailedSignInAttempts() {
            return maxFailedSignInAttempts;
        }

        public void setMaxFailedSignInAttempts(int maxFailedSignInAttempts) {
            this.maxFailedSignInAttempts = maxFailedSignInAttempts;
        }

        public Duration lockoutDuration() {
            return lockoutDuration;
        }

        public void setLockoutDuration(Duration lockoutDuration) {
            this.lockoutDuration = lockoutDuration;
        }
    }

    public static class RateLimit {
        private boolean enabled = true;
        /** {@code redis} in a multi-replica deployment, {@code memory} for single-node. */
        private String backend = "memory";
        private int defaultRequestsPerMinute = 600;
        private int burst = 120;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBackend() {
            return backend;
        }

        public void setBackend(String backend) {
            this.backend = backend;
        }

        public int defaultRequestsPerMinute() {
            return defaultRequestsPerMinute;
        }

        public void setDefaultRequestsPerMinute(int value) {
            this.defaultRequestsPerMinute = value;
        }

        public int burst() {
            return burst;
        }

        public void setBurst(int burst) {
            this.burst = burst;
        }
    }

    public static class Uploads {
        private long maxBytes = 25L * 1024 * 1024;
        /** Files above this size are streamed to storage in parts instead of buffered. */
        private long multipartThreshold = 8L * 1024 * 1024;
        private Duration signedUrlTtl = Duration.ofMinutes(5);
        private Duration maxSignedUrlTtl = Duration.ofMinutes(15);

        public long maxBytes() {
            return maxBytes;
        }

        public void setMaxBytes(long maxBytes) {
            this.maxBytes = maxBytes;
        }

        public long multipartThreshold() {
            return multipartThreshold;
        }

        public void setMultipartThreshold(long multipartThreshold) {
            this.multipartThreshold = multipartThreshold;
        }

        public Duration signedUrlTtl() {
            return signedUrlTtl;
        }

        public void setSignedUrlTtl(Duration signedUrlTtl) {
            this.signedUrlTtl = signedUrlTtl;
        }

        public Duration maxSignedUrlTtl() {
            return maxSignedUrlTtl;
        }

        public void setMaxSignedUrlTtl(Duration maxSignedUrlTtl) {
            this.maxSignedUrlTtl = maxSignedUrlTtl;
        }
    }

    public static class Storage {
        private String provider = "s3";
        private String region = "us-east-1";
        private String bucket;
        private String endpoint;
        private boolean pathStyleAccess = false;

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }

        public String getBucket() {
            return bucket;
        }

        public void setBucket(String bucket) {
            this.bucket = bucket;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public boolean isPathStyleAccess() {
            return pathStyleAccess;
        }

        public void setPathStyleAccess(boolean pathStyleAccess) {
            this.pathStyleAccess = pathStyleAccess;
        }
    }

    public static class Secrets {
        /** {@code environment} (dev), {@code vault}, {@code aws}, {@code kubernetes}. */
        private String provider = "environment";
        private String vaultAddress;
        private String vaultTransitKey = "hatis";
        private String awsKmsKeyId;

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getVaultAddress() {
            return vaultAddress;
        }

        public void setVaultAddress(String vaultAddress) {
            this.vaultAddress = vaultAddress;
        }

        public String getVaultTransitKey() {
            return vaultTransitKey;
        }

        public void setVaultTransitKey(String vaultTransitKey) {
            this.vaultTransitKey = vaultTransitKey;
        }

        public String getAwsKmsKeyId() {
            return awsKmsKeyId;
        }

        public void setAwsKmsKeyId(String awsKmsKeyId) {
            this.awsKmsKeyId = awsKmsKeyId;
        }
    }
}
