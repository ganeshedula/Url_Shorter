# Database Schema

## `users`

| Column | Type | Notes |
|---|---|---|
| `id` | UUID | Primary key |
| `email` | VARCHAR(320) | Unique, indexed |
| `username` | VARCHAR(100) | Optional display name |
| `password` | VARCHAR(100) | BCrypt hash |
| `role` | VARCHAR(30) | `ROLE_USER` or `ROLE_ADMIN` |
| `token_version` | BIGINT | Incremented on global logout / password reset |
| `email_verified` | BOOLEAN | Account verification flag |
| `created_at` | TIMESTAMP WITH TIME ZONE | Audited |
| `updated_at` | TIMESTAMP WITH TIME ZONE | Audited |

## `url_mappings`

| Column | Type | Notes |
|---|---|---|
| `id` | UUID | Primary key |
| `original_url` | VARCHAR(2048) | Target URL |
| `short_code` | VARCHAR(20) | Unique short code |
| `click_count` | BIGINT | Incremented on redirect |
| `last_accessed_at` | TIMESTAMP WITH TIME ZONE | Last redirect time |
| `expiration_date` | TIMESTAMP WITH TIME ZONE | Optional expiration |
| `active` | BOOLEAN | Soft disable switch |
| `user_id` | UUID | FK to `users.id` |
| `created_at` | TIMESTAMP WITH TIME ZONE | Audited |
| `updated_at` | TIMESTAMP WITH TIME ZONE | Audited |

Indexes:
- `idx_url_mapping_short_code` (unique)
- `idx_url_mapping_user_created_at` (`user_id`, `created_at`)
- `idx_url_mapping_original_url` (`original_url`)

## `click_events`

| Column | Type | Notes |
|---|---|---|
| `id` | UUID | Primary key |
| `accessed_at` | TIMESTAMP WITH TIME ZONE | Click timestamp |
| `browser` | VARCHAR(100) | Parsed from user-agent |
| `operating_system` | VARCHAR(100) | Parsed from user-agent |
| `ip_address` | VARCHAR(100) | Request IP |
| `country` | VARCHAR(100) | Geo-located country name |
| `country_code` | VARCHAR(10) | ISO country code |
| `region` | VARCHAR(120) | Geo-located state / region |
| `city` | VARCHAR(120) | Geo-located city |
| `timezone` | VARCHAR(120) | Timezone identifier |
| `latitude` | DOUBLE PRECISION | Geocoordinates latitude |
| `longitude` | DOUBLE PRECISION | Geocoordinates longitude |
| `user_agent` | VARCHAR(512) | Raw user-agent |
| `url_mapping_id` | UUID | FK to `url_mappings.id` |

Indexes:
- `idx_click_event_url_accessed_at` (`url_mapping_id`, `accessed_at`)
- `idx_click_event_ip` (`ip_address`)

## `otp_verifications`

| Column | Type | Notes |
|---|---|---|
| `id` | UUID | Primary key |
| `email` | VARCHAR(320) | User email |
| `otp_hash` | VARCHAR(100) | BCrypt hash of one-time password |
| `purpose` | VARCHAR(30) | `ACCOUNT_VERIFICATION` or `PASSWORD_RESET` |
| `expires_at` | TIMESTAMP WITH TIME ZONE | Expiration instant |
| `attemptCount` | INTEGER | Failed verification attempts |
| `consumed_at` | TIMESTAMP WITH TIME ZONE | Instant OTP was used |
| `invalidated_at` | TIMESTAMP WITH TIME ZONE | Instant OTP was superseded |
| `reset_token_hash` | VARCHAR(100) | BCrypt hash of reset auth token |
| `reset_token_expires_at` | TIMESTAMP WITH TIME ZONE | Password reset validity window |
| `reset_token_used_at` | TIMESTAMP WITH TIME ZONE | Password reset consumption instant |
| `version` | BIGINT | Optimistic locking version |
| `created_at` | TIMESTAMP WITH TIME ZONE | Audited |
| `updated_at` | TIMESTAMP WITH TIME ZONE | Audited |

Indexes:
- `idx_otp_email_purpose_created_at` (`email`, `purpose`, `created_at`)

## Redis Keys

| Key Pattern | Purpose | TTL |
|---|---|---|
| `auth:session:{sessionId}` | Serialized refresh session | 7 days |
| `auth:user-sessions:{userId}` | Set of active session IDs per user | 7 days |
| `auth:blacklist:{tokenId}` | Blacklisted access token IDs | Remaining token lifetime |
| `auth:otp:{purpose}:{sha256(email)}` | BCrypt-hashed OTP code | 10 minutes |
| `auth:otp-attempts:{purpose}:{sha256(email)}` | Failed verification attempts counter | 10 minutes |
| `auth:otp-cooldown:{purpose}:{sha256(email)}` | Cooldown flag between OTP resends | 60 seconds |
| `auth:password-reset:{sha256(email)}` | BCrypt-hashed one-time password reset token | 15 minutes |
| `auth:pending-registration:{sha256(email)}` | Hashed unverified user credentials | 10 minutes |
| `auth:google-oauth-state:{state}` | PKCE code verifier for OAuth state | 10 minutes |
| `rate:auth:{identifier}` | Sliding-window auth request counter | 1 minute |
| `rate:url:{identifier}` | Sliding-window URL creation counter | 1 minute |
| `rate:redirect:{identifier}` | Sliding-window redirect request counter | 1 minute |
