# OAuth2 Configuration for OSM EUC World AI Features

## OAuth Redirector Setup Information

### App Identification
- **App ID:** `com.a42r.eucosmandplugin`
- **App Name:** OSM EUC World
- **OAuth Provider:** OpenRouter.ai
- **OAuth Redirector URL:** `https://oauth.a42r.com`

### Deep Link Configuration
- **Deep Link Scheme:** `osmeucworld://oauth/callback`
- **Full Deep Link Example:** `osmeucworld://oauth/callback?code=abc123&state=xyz789`

### Registration Endpoint
The app will POST to: `https://oauth.a42r.com/oauth/register`

**Request Payload:**
```json
{
  "app_id": "com.a42r.eucosmandplugin",
  "provider": "openrouter",
  "deep_link_url": "osmeucworld://oauth/callback",
  "timestamp": 1234567890,
  "signature": "hmac-sha256-signature"
}
```

**Expected Response:**
```json
{
  "callback_url": "https://oauth.a42r.com/callback/session-id-here",
  "session_id": "unique-session-id"
}
```

### Signature Verification
- **Algorithm:** HMAC-SHA256
- **Signature Input:** `{provider}|{deep_link_url}|{timestamp}|{shared_secret}`
- **Example:** `openrouter|osmeucworld://oauth/callback|1234567890|YOUR_SECRET`
- **Shared Secret:** `CHANGE_ME_TO_SECURE_SECRET` (placeholder - needs to be replaced)

### OAuth Flow

1. **App initiates OAuth:**
   - Generates PKCE code verifier and challenge (SHA-256)
   - POSTs to `https://oauth.a42r.com/oauth/register` with signature
   - Receives `callback_url` and `session_id`

2. **App opens browser:**
   - Opens `https://openrouter.ai/auth?callback_url={callback_url}&code_challenge={challenge}&code_challenge_method=S256`
   - User authenticates with OpenRouter
   - OpenRouter redirects to `callback_url` with authorization code

3. **OAuth redirector processes:**
   - Receives authorization code from OpenRouter
   - Redirects to deep link: `osmeucworld://oauth/callback?code={code}&state={session_id}`

4. **App receives deep link:**
   - Extracts authorization code
   - Exchanges code for API key at `https://openrouter.ai/api/v1/auth/keys`
   - Stores API key securely in EncryptedSharedPreferences

### Security Features
- **PKCE (RFC 7636):** Prevents authorization code interception
- **Signature Verification:** Prevents unauthorized registration requests
- **Encrypted Storage:** API keys stored with Android Security Crypto
- **Deep Link Protection:** App-specific URI scheme

### Required OAuth Redirector Configuration

You'll need to configure the following in `oauth.a42r.com`:

1. **Register the app:**
   - App ID: `com.a42r.eucosmandplugin`
   - Provider: `openrouter`
   - Deep Link: `osmeucworld://oauth/callback`
   - Shared Secret: (generate a secure secret and provide it to the app developer)

2. **Session management:**
   - Store pending sessions with PKCE state
   - Timeout sessions after 10 minutes
   - Clean up completed sessions

3. **Redirect handling:**
   - Accept authorization code from OpenRouter
   - Validate session exists
   - Redirect to deep link with code and session_id

### Implementation Files

**Configuration:**
- `app/src/main/java/com/a42r/eucosmandplugin/ai/util/NativeSecrets.kt`
  - Contains OAuth URLs and secrets
  - **TODO:** Update `getOAuthSharedSecret()` with real secret

**Service:**
- `app/src/main/java/com/a42r/eucosmandplugin/ai/service/OpenRouterOAuthService.kt`
  - Implements OAuth flow
  - Handles PKCE generation
  - Communicates with oauth.a42r.com

**Manifest:**
- `app/src/main/AndroidManifest.xml`
  - Declares deep link intent filter
  - Registers OAuthActivity

### Next Steps

1. **OAuth Redirector Admin:**
   - Set up `com.a42r.eucosmandplugin` in oauth.a42r.com
   - Generate and provide secure shared secret
   - Test registration endpoint

2. **App Developer:**
   - Update `NativeSecrets.getOAuthSharedSecret()` with provided secret
   - Test OAuth flow end-to-end
   - Consider moving secret to native code for better security

### Testing

**Test Registration Request:**
```bash
curl -X POST https://oauth.a42r.com/oauth/register \
  -H "Content-Type: application/json" \
  -d '{
    "app_id": "com.a42r.eucosmandplugin",
    "provider": "openrouter",
    "deep_link_url": "osmeucworld://oauth/callback",
    "timestamp": '$(date +%s)',
    "signature": "YOUR_HMAC_SIGNATURE"
  }'
```

**Expected Success Response:**
```json
{
  "callback_url": "https://oauth.a42r.com/callback/{session-id}",
  "session_id": "{unique-session-id}",
  "expires_at": 1234567890
}
```

### Support

For questions or issues:
- App Repository: OSM-EUC-World
- Package: com.a42r.eucosmandplugin
- OAuth Provider: openrouter.ai
- OAuth Redirector: oauth.a42r.com
